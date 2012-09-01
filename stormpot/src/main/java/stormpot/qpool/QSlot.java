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
import java.util.concurrent.atomic.AtomicReference;

import stormpot.Poolable;
import stormpot.Slot;
import stormpot.SlotInfo;

class QSlot<T extends Poolable> implements Slot, SlotInfo<T> {
  static final Object LIVE = new Object();
  static final Object CLAIMED = new Object();
  static final Object DEAD = new Object();
  final BlockingQueue<QSlot<T>> live;
  private final AtomicReference<Object> state;
  T obj;
  Exception poison;
  long created;
  long claims;
  boolean tlrClaimed = false;
  
  public QSlot(BlockingQueue<QSlot<T>> live) {
    this.live = live;
    this.state = new AtomicReference<Object>(DEAD);
  }
  
  public boolean claim() {
    boolean success = live2claim();
    if (success) {
      claims++;
    }
    return success;
  }

  public void release(Poolable obj) {
    if (claim2live() && !tlrClaimed) {
      live.offer(this);
    }
  }
  
  public boolean claim2live() {
    return cas(CLAIMED, LIVE);
  }
  
  public boolean live2claim() {
    return cas(LIVE, CLAIMED);
  }
  
  public boolean claim2dead() {
    return cas(CLAIMED, DEAD);
  }
  
  public boolean dead2live() {
    return cas(DEAD, LIVE);
  }
  
  public boolean live2dead() {
    return cas(LIVE, DEAD);
  }

  private boolean cas(Object expected, Object update) {
    return state.compareAndSet(expected, update);
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

  public boolean isDead() {
    return state.get() == DEAD;
  }
}