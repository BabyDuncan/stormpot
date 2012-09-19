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
package stormpot;

/**
 * Objects contained in a {@link Pool} must implement the Poolable interface
 * and adhere to its contract.
 * <p>
 * Pools call {@link Allocator#allocate(Slot) allocate} on Allocators with the
 * specific {@link Slot} that they want a Poolable allocated for. The Slot
 * represents a location in the pool, that can fit an object and make it
 * available for others to claim.
 * <p>
 * The contract of the Poolable interface is, that when {@link #release()} is
 * called on the Poolable, it must in turn call {@link Slot#release(Poolable)}
 * on the specific Slot object that it was allocated with, giving itself as the
 * Poolable parameter.
 * <p>
 * The simplest possible correct implementation of the Poolable interface looks
 * like this:
 * <pre><code> public class GenericPoolable implements Poolable {
 *   private final Slot slot;
 *   public GenericPoolable(Slot slot) {
 *     this.slot = slot;
 *   }
 *   
 *   public void release() {
 *     slot.release(this);
 *   }
 * }</code></pre>
 * <p>
 * See also <a href="package-summary.html#memory-effects-and-threading">
 * Memory Effects and Threading</a> for details of the behaviour in concurrent
 * programs.
 * 
 * @author Chris Vest &lt;mr.chrisvest@gmail.com&gt;
 *
 */
public interface Poolable {
  /**
   * Release this Poolable object back into the pool from where it came,
   * so that others can claim it, or the pool deallocate it if it has
   * expired.
   * <p>
   * A call to this method MUST delegate to a call to
   * {@link Slot#release(Poolable)} on the Slot object for which this
   * Poolable was allocated, giving itself as the Poolable parameter.
   * <p>
   * Note that it is an error to release a Poolable that is not claimed (has
   * already been released) or is or was claimed by another thread. Pools
   * are free to throw {@link PoolException} if they can detect this, but this
   * is not guaranteed. In fact, no specific behaviour is specified for this
   * particular situation, so infinite loops and dead-locks are possible
   * reactions as well.
   * @see Slot#release(Poolable)
   */
  void release();
}
