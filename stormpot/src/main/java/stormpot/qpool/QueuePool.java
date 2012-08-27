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

  private void checkForPoison(QSlot<T> slot) {
    if (slot == allocThread.POISON_PILL) {
      slot.state.set(QSlot.LIVE);
      live.offer(allocThread.POISON_PILL);
      throw new IllegalStateException("pool is shut down");
    }
    // TODO access to poison isn't thread-safe because we might be racing
    // with the allocation thread if this is our TLR slot.
    // The case:
    //  - Thread1 claim a slot and it is now its TLR slot
    //  - then it expires and Thread1 release it
    //  - the slot goes into the dead-queue
    //  - the reallocation fails and the slot gains poison
    //  - Thread1 read the poison field
    //  - Thread2 tries to claim it from the live-queue
    //  - Thread2 then throws it into the dead-queue because it has poison
    //  - the slot is successfully reallocated
    //  - Thread2 claims the slot
    //  - now Thread1 comes back to life
    //  - Thread1 throws the slot into the dead-queue because of the poison
    //  - the slot is deallocated even though Thread2 has it claimed.
    if (slot.poison != null) {
      Exception poison = slot.poison;
      kill(slot);
      throw new PoolException("allocation failed", poison);
    }
    if (shutdown) { // TODO racy coverage
      kill(slot); // TODO might be TLR-claimed by someone; mustn't kill it!!!
      throw new IllegalStateException("pool is shut down");
    }
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

  protected void kill(QSlot<T> slot) {
    if (slot.kill()) {
      dead.offer(slot);
    }
  }

  public T claim(Timeout timeout) throws PoolException,
      InterruptedException {
    if (timeout == null) {
      throw new IllegalArgumentException("timeout cannot be null");
    }
    QSlot<T> slot = tlr.get();
    if (slot != null && slot.claim()) {
//      checkForPoison(slot);
      // Attempt the claim before checking the validity, because we might
      // already have claimed it.
      // If we checked validity before claiming, then we might find that it
      // had expired, and throw it in the dead queue, causing a claimed
      // Poolable to be deallocated before it is released.
      if (!isInvalid(slot)) {
        slot.tlrClaimed = true;
        return slot.obj;
      }
    }
    long deadline = timeout.getDeadline();
    do {
      long timeoutLeft = timeout.getTimeLeft(deadline);
      slot = live.poll(timeoutLeft, timeout.getBaseUnit());
      if (slot == null) {
        // we timed out while taking from the queue - just return null
        return null;
      }
//      checkForPoison(slot);
      // Again, attempt to claim before checking validity. We mustn't kill
      // objects that are already claimed by someone else.
    } while (!slot.claim() || isInvalid(slot));
    slot.tlrClaimed = false;
    tlr.set(slot);
    return slot.obj;
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
