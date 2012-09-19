/*
 * Copyright 2011 Chris Vest
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package stormpot.qpool;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import stormpot.Completion;
import stormpot.Config;
import stormpot.Expiration;
import stormpot.LifecycledPool;
import stormpot.PoolException;
import stormpot.Poolable;
import stormpot.ResizablePool;
import stormpot.Timeout;

/**
 * QueuePool is a fairly simple {@link LifecycledPool} and
 * {@link ResizablePool} implementation that basically consists of a queue of
 * Poolable instances, and a Thread to allocate them.
 * <p>
 * This means that the object allocation always happens in a dedicated thread.
 * This means that no thread that calls any of the claim methods, will incur
 * the overhead of allocating Poolables. This should lead to reduced deviation
 * in the times it takes claim method to complete, provided the pool is not
 * depleted.
 * @author Chris Vest &lt;mr.chrisvest@gmail.com&gt;
 * @param <T> The type of {@link Poolable} managed by this pool.
 */
public final class QueuePool<T extends Poolable>
implements LifecycledPool<T>, ResizablePool<T> {
  private final BlockingQueue<QSlot<T>> live;
  private final BlockingQueue<QSlot<T>> dead;
  private final QAllocThread<T> allocThread;
  private final Expiration<? super T> deallocRule;
  // TODO consider making it a ThreadLocal of Ref<QSlot>, hoping that maybe
  // writes to it will be faster in not requiring a ThreadLocal look-up.
  private final ThreadLocal<QSlot<T>> tlr;
  private volatile boolean shutdown = false;
  
  /**
   * Construct a new QueuePool instance based on the given {@link Config}.
   * @param config The pool configuration to use.
   */
  public QueuePool(Config<T> config) {
    live = new LinkedBlockingQueue<QSlot<T>>();
    dead = new LinkedBlockingQueue<QSlot<T>>();
    tlr = new ThreadLocal<QSlot<T>>();
    synchronized (config) {
      config.validate();
      allocThread = new QAllocThread<T>(live, dead, config);
      deallocRule = config.getExpiration();
    }
    allocThread.start();
  }

  public T claim(Timeout timeout) throws PoolException,
      InterruptedException {
    if (timeout == null) {
      throw new IllegalArgumentException("timeout cannot be null");
    }
    QSlot<T> slot = tlr.get();
//    System.out.println("tlr.poll " + slot);
    // Note that the TLR slot at this point might have been tried by another
    // thread, found to be expired, put on the dead-queue and deallocated.
    // We handle this because slots always transition to the dead state before
    // they are put on the dead-queue, and if they are dead, then the
    // slot.claim() call will fail.
    // Then we will eventually find another slot from the live-queue that we
    // can claim and make our new TLR slot.
    if (slot != null && slot.live2claimTlr()) {
      // Attempt the claim before checking the validity, because we might
      // already have claimed it.
      // If we checked validity before claiming, then we might find that it
      // had expired, and throw it in the dead queue, causing a claimed
      // Poolable to be deallocated before it is released.
      if (!isInvalid(slot)) {
        slot.incrementClaims();
        return slot.obj;
      }
      // We managed to tlr-claim the slot, but it turned out to be no good.
      // That means we now have to transition it from tlr-claimed to dead.
      // However, since we didn't pull it off of the live-queue, it might still
      // be in the live-queue. And since it might be in the live-queue, it
      // can't be put on the dead-queue. And since it can't be put on the
      // dead-queue, it also cannot transition to the dead state.
      // This effectively means that we have to transition it back to the live
      // state, and then let some pull it off of the live-queue, check it
      // again, and only then put it on the dead-queue.
      // It's cumbersome, but we have to do it this way, in order to prevent
      // duplicate entries in the queues. Otherwise we'd have a nasty memory
      // leak on our hands.
      slot.claimTlr2live();
    }
    long deadline = timeout.getDeadline();
    boolean notClaimed = true;
    do {
      long timeoutLeft = timeout.getTimeLeft(deadline);
      slot = live.poll(timeoutLeft, timeout.getBaseUnit());
//      System.out.println("live.poll " + slot);
      if (slot == null) {
        // we timed out while taking from the queue - just return null
        return null;
      }
      // Again, attempt to claim before checking validity. We mustn't kill
      // objects that are already claimed by someone else.
      
      // TODO tlr-claimed slots have been pulled from the live-queue but
      // cannot be claimed. They must somehow re-enter the queue system, so
      // that we don't forget about them.
      do {
        notClaimed = !slot.live2claim();
      } while (notClaimed && !slot.claimTlr2claim());
      /*
      if (notClaimed) {
        // transition from a tlr-claim to a normal claim, so that the
        // subsequent release will let the slot re-enter the live-queue.
        slot.claimTlr2claim();
      }//*/
    } while (notClaimed || isInvalid(slot));
    slot.incrementClaims();
    tlr.set(slot);
    return slot.obj;
  }

  private boolean isInvalid(QSlot<T> slot) {
    checkForPoison(slot);
    boolean invalid = true;
    RuntimeException exception = null;
    try {
      invalid = deallocRule.hasExpired(slot);
    } catch (RuntimeException ex) {
      exception = ex;
    }
    if (invalid) {
      // it's invalid - into the dead queue with it and continue looping
      kill(slot);
      if (exception != null) {
        throw exception;
      }
    }
    return invalid;
  }

  private void checkForPoison(QSlot<T> slot) {
    if (slot == allocThread.POISON_PILL) {
      // The poison pill means the pool has been shut down. The pill was
      // transitioned from live to claimed just prior to this check, so we
      // must transition it back to live and put it back into the live-queue
      // before throwing our exception.
      // Because we always throw when we see it, it will never become a
      // tlr-slot, and so we don't need to worry about transitioning from
      // tlr-claimed to live.
      slot.claim2live();
      live.offer(allocThread.POISON_PILL);
      throw new IllegalStateException("pool is shut down");
    }
    if (slot.poison != null) {
      Exception poison = slot.poison;
      kill(slot);
      throw new PoolException("allocation failed", poison);
    }
    if (shutdown) { // TODO racy coverage
      kill(slot);
      throw new IllegalStateException("pool is shut down");
    }
  }

  protected void kill(QSlot<T> slot) {
    // The use of claim2dead() here ensures that we don't put slots into the
    // dead-queue more than once. Many threads might have this as their
    // TLR-slot and try to tlr-claim it, but only when a slot has been normally
    // claimed, that is, pulled off the live-queue, can it be put into the
    // dead-queue. This helps ensure that a slot will only ever be in at most
    // one queue.
    if (slot.claim2dead()) {
//      System.out.println("dead.offer " + slot);
      dead.offer(slot);
    }
  }

  public Completion shutdown() {
    shutdown = true;
    allocThread.interrupt();
    return new QPoolShutdownCompletion(allocThread);
  }

  public void setTargetSize(int size) {
    if (size < 1) {
      throw new IllegalArgumentException("target size must be at least 1");
    }
    allocThread.setTargetSize(size);
  }

  public int getTargetSize() {
    return allocThread.getTargetSize();
  }
}
