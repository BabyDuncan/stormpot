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
import java.util.concurrent.ArrayBlockingQueue;

import stormpot.Completion;
import stormpot.Config;
import stormpot.Expiration;
import stormpot.LifecycledPool;
import stormpot.PoolException;
import stormpot.Poolable;
import stormpot.Timeout;

/**
 * This is a non-resizable implementation of {@link QueuePool}.
 * 
 * @author Chris Vest &lt;mr.chrisvest@gmail.com&gt;
 * @param <T> The type of {@link Poolable} managed by this pool.
 */
public final class FixedQueuePool<T extends Poolable>
implements LifecycledPool<T> {
  private final BlockingQueue<QSlot<T>> live;
  private final BlockingQueue<QSlot<T>> dead;
  private final QAllocThread<T> allocThread;
  private final Expiration<? super T> deallocRule;
  private volatile boolean shutdown = false;
  
  /**
   * Construct a new QueuePool instance based on the given {@link Config}.
   * @param config The pool configuration to use.
   */
  public FixedQueuePool(Config<T> config) {
    synchronized (config) {
      int size = config.getSize();
      size++; // make room for poison pill — trickiest off-by-one error yet!
      live = new ArrayBlockingQueue<QSlot<T>>(size);
      dead = new ArrayBlockingQueue<QSlot<T>>(size);
      config.validate();
      allocThread = new QAllocThread<T>(live, dead, config);
      deallocRule = config.getExpiration();
    }
    allocThread.start();
  }

  private void checkForPoison(QSlot<T> slot) {
    if (slot == allocThread.POISON_PILL) {
      live.offer(allocThread.POISON_PILL);
      throw new IllegalStateException("pool is shut down");
    }
    if (slot.poison != null) {
      Exception poison = slot.poison;
      dead.offer(slot);
      throw new PoolException("allocation failed", poison);
    }
    if (shutdown) { // TODO racy coverage
      dead.offer(slot);
      throw new IllegalStateException("pool is shut down");
    }
  }

  private boolean isInvalid(QSlot<T> slot) {
    boolean invalid = true;
    RuntimeException exception = null;
    try {
      invalid = deallocRule.hasExpired(slot);
    } catch (RuntimeException ex) {
      exception = ex;
    }
    if (invalid) {
      // it's invalid - into the dead queue with it and continue looping
      dead.offer(slot);
      if (exception != null) {
        throw exception;
      }
    } else {
      // it's valid - claim it and stop looping
      slot.claim();
    }
    return invalid;
  }

  public T claim(Timeout timeout) throws PoolException,
      InterruptedException {
    if (timeout == null) {
      throw new IllegalArgumentException("timeout cannot be null");
    }
    QSlot<T> slot;
    long deadline = timeout.getDeadline();
    do {
      long timeoutLeft = timeout.getTimeLeft(deadline);
      slot = live.poll(timeoutLeft, timeout.getBaseUnit());
      if (slot == null) {
        // we timed out while taking from the queue - just return null
        return null;
      }
      checkForPoison(slot);
    } while (isInvalid(slot));
    return slot.obj;
  }

  public Completion shutdown() {
    shutdown = true;
    allocThread.interrupt();
    return new QPoolShutdownCompletion(allocThread);
  }
}
