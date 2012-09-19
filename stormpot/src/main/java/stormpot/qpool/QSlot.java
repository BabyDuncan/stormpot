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

import static stormpot.qpool.QSlotState.*;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

import stormpot.PoolException;
import stormpot.Poolable;
import stormpot.Slot;
import stormpot.SlotInfo;

class QSlot<T extends Poolable> implements Slot, SlotInfo<T> {
  final BlockingQueue<QSlot<T>> live;
  private final AtomicReference<QSlotState> state;
  T obj;
  Exception poison;
  long created;
  long claims;
  
  public QSlot(BlockingQueue<QSlot<T>> live) {
    this.live = live;
    this.state = new AtomicReference<QSlotState>(dead);
  }
  
  public void release(Poolable obj) {
    QSlotState qSlotState = state.get();
    if (qSlotState == tlrClaimed) {
      if (!claimTlr2live()) {
        throw new AssertionError(
            "transition from tlr-claimed to live must not fail");
      }
    } else if (claim2live()) {
      live.offer(this);
    } else {
      throw new PoolException("Slot release from bad state: " + qSlotState);
    }
  }
  
  public boolean claim2live() {
    return cas(claimed, living);
  }
  
  public boolean claimTlr2live() {
    return cas(tlrClaimed, living);
  }
  
  public boolean live2claim() {
    return cas(living, claimed);
  }
  
  public boolean live2claimTlr() {
    return cas(living, tlrClaimed);
  }
  
  public boolean claim2dead() {
    return cas(claimed, dead);
  }
  
  public boolean claimTlr2dead() {
    return cas(tlrClaimed, dead);
  }
  
  public boolean dead2live() {
    return cas(dead, living);
  }
  
  public boolean live2dead() {
    return cas(living, dead);
  }

  private boolean cas(QSlotState expected, QSlotState update) {
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
    return state.get() == dead;
  }

  public void incrementClaims() {
    claims++;
  }
}