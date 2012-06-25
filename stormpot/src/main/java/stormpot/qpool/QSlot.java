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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import stormpot.Poolable;
import stormpot.Slot;
import stormpot.SlotInfo;

class QSlot<T extends Poolable> implements Slot, SlotInfo<T> {
  static final int LIVE = 0;
  static final int CLAIMED = 1;
  final BlockingQueue<QSlot<T>> live;
  final AtomicInteger claimed;
  final AtomicReference<Thread> owner;
  T obj;
  Exception poison;
  long created;
  long claims;
  boolean tlrClaimed = false;
  
  public QSlot(BlockingQueue<QSlot<T>> live) {
    this.live = live;
    this.claimed = new AtomicInteger(CLAIMED);
    this.owner = new AtomicReference<Thread>();
  }
  
  public boolean claim() {
    boolean success = claimed.compareAndSet(LIVE, CLAIMED);
    if (success) {
      claims++;
    }
    return success;
  }

  public void release(Poolable obj) {
    if (claimed.compareAndSet(CLAIMED, LIVE) && !tlrClaimed) {
      live.offer(this);
    }
  }
  
  public boolean takeOwnership() {
    return owner.compareAndSet(null, Thread.currentThread());
  }
  
  public boolean makeLive() {
    owner.set(null);
    return claimed.compareAndSet(CLAIMED, LIVE);
  }

  @Override
  public long getAgeMillis() {
    return System.currentTimeMillis() - created;
  }

  @Override
  public long getClaimCount() {
    return claims;
  }

  @Override
  public T getPoolable() {
    return obj;
  }

  public boolean isOurs() {
    return owner.get() == Thread.currentThread();
  }
  
  public void transferOwnership(Thread th) {
    owner.compareAndSet(Thread.currentThread(), th);
  }
  
  public boolean isOwnedBy(Thread th) {
    return owner.get() == th;
  }
}