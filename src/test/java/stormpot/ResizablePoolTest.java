/*
 * Copyright 2012 Chris Vest
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

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.theories.DataPoint;
import org.junit.experimental.theories.Theories;
import org.junit.experimental.theories.Theory;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;
import stormpot.bpool.BlazePoolFixture;
import stormpot.qpool.QueuePoolFixture;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThat;
import static stormpot.UnitKit.shutdown;

@RunWith(Theories.class)
public class ResizablePoolTest {
  @Rule public final TestRule failurePrinter = new FailurePrinterTestRule();
  
  private static final Timeout longTimeout = new Timeout(1, TimeUnit.SECONDS);
  private static final Timeout shortTimeout = new Timeout(1, TimeUnit.MILLISECONDS);
  
  @DataPoint public static PoolFixture queuePool = new QueuePoolFixture();
  @DataPoint public static PoolFixture blazePool = new BlazePoolFixture();
  
  @DataPoint public static ExecutorConfig cleanDefaultExecutor =
      ExecutorConfigs.cleanDefault();
  @DataPoint public static ExecutorConfig sharedCachingExecutor =
      ExecutorConfigs.constantly(Executors.newCachedThreadPool());
  @DataPoint public static ExecutorConfig singleThreadedExecutor =
      ExecutorConfigs.singleThreaded();

  private Config<GenericPoolable> config;
  
  @Before public void
  setUp() {
    CountingAllocator allocator = new CountingAllocator();
    config = new Config<GenericPoolable>().setAllocator(allocator).setSize(1);
  }

  private ResizablePool<GenericPoolable> resizable(
      PoolFixture fixture, ExecutorConfig ec) {
    return (ResizablePool<GenericPoolable>) fixture.initPool(config, ec);
  }
  
  @Theory public void
  mustImplementResizablePool(PoolFixture fixture, ExecutorConfig ec) {
    assertThat(fixture.initPool(config, ec), instanceOf(ResizablePool.class));
  }
  
  @Test(expected = IllegalArgumentException.class)
  @Theory public void
  targetSizeMustBeGreaterThanZero(PoolFixture fixture, ExecutorConfig ec) {
    ResizablePool<GenericPoolable> pool = resizable(fixture, ec);
    pool.setTargetSize(0);
  }
  
  @Theory public void
  targetSizeMustBeConfiguredSizeByDefault(
      PoolFixture fixture, ExecutorConfig ec) {
    config.setSize(23);
    ResizablePool<GenericPoolable> pool = resizable(fixture, ec);
    assertThat(pool.getTargetSize(), is(23));
  }
  
  @Theory public void
  getTargetSizeMustReturnLastSetTargetSize(
      PoolFixture fixture, ExecutorConfig ec) {
    ResizablePool<GenericPoolable> pool = resizable(fixture, ec);
    pool.setTargetSize(3);
    assertThat(pool.getTargetSize(), is(3));
  }

  @Theory public void
  settingTargetSizeOnPoolThatHasBeenShutDownDoesNothing(
      PoolFixture fixture, ExecutorConfig ec) {
    config.setSize(3);
    ResizablePool<GenericPoolable> pool = resizable(fixture, ec);
    shutdown(pool);
    pool.setTargetSize(10); // this should do nothing, because it's shut down
    assertThat(pool.getTargetSize(), is(3));
  }
  
  /**
   * When we increase the size of a depleted pool, it should be possible to
   * make claim again and get out newly allocated objects.
   * 
   * We test for this by depleting a pool, upping the size and then claiming
   * again with a timeout that is longer than the timeout of the test. The test
   * pass if it does not timeout.
   */
  @Test(timeout = 300)
  @Theory public void
  increasingSizeMustAllowMoreAllocations(
      PoolFixture fixture, ExecutorConfig ec) throws Exception {
    ResizablePool<GenericPoolable> pool = resizable(fixture, ec);
    pool.claim(longTimeout); // depleted
    pool.setTargetSize(2);
    // now this mustn't block:
    pool.claim(longTimeout);
  }
  
  /**
   * We must somehow ensure that the pool starts deallocating more than it
   * allocates, when the pool is shrunk. This is difficult because the pool
   * cannot tell us when it reaches the target size, so we have to figure this
   * out by using a special allocator.
   * 
   * We test for this by configuring a CountingAllocator that also unparks a
   * thread (namely ours, the main thread for the test) at every allocation
   * and deallocation. We also configure the pool to have a somewhat large
   * initial size, so we can shrink it later. Then we deplete the pool, and
   * set a smaller target size. After setting the new target size, we release
   * just enough objects for the pool to reach it, and then we wait for the
   * allocator to register that same number of deallocations. This has to
   * happen before the test times out. After that, we check that the difference
   * between the allocations and the deallocations matches the new target size.
   */
  @Test(timeout = 300)
  @Theory public void
  decreasingSizeMustEventuallyDeallocateSurplusObjects(
      PoolFixture fixture, ExecutorConfig ec) throws Exception {
    int startingSize = 5;
    int newSize = 1;
    final Thread main = Thread.currentThread();
    CountingAllocator allocator = new UnparkingCountingAllocator(main);
    config.setSize(startingSize);
    config.setAllocator(allocator);
    ResizablePool<GenericPoolable> pool = resizable(fixture, ec);
    List<GenericPoolable> objs = new ArrayList<GenericPoolable>();
    
    while (allocator.allocations() != startingSize) {
      objs.add(pool.claim(longTimeout)); // force the pool to do work
    }
    pool.setTargetSize(newSize);
    while (allocator.deallocations() != startingSize - newSize) {
      if (objs.size() > 0) {
        objs.remove(0).release(); // give the pool objects to deallocate
      } else {
        pool.claim(longTimeout).release(); // prod it & poke it
      }
      LockSupport.parkNanos(10000000); // 10 millis
    }
    assertThat(
        allocator.allocations() - allocator.deallocations(), is(newSize));
  }
  
  /**
   * Similar to the decreasingSizeMustEventuallyDeallocateSurplusObjects test
   * above, but this time the objects are all expired after the pool has been
   * shrunk.
   * 
   * Again, we deplete the pool. Once depleted, our expiration has been
   * configured such, that all subsequent items one tries to claim, will be
   * expired.
   * 
   * Then we set the new lower target size, and release just enough for the
   * pool to reach the new target.
   * 
   * Then we try to claim an object from the pool with a very short timeout.
   * This will return null because the pool is still depleted. We also check
   * that the pool has not made any new allocations, even though we have been
   * releasing objects. We don't check the deallocations because it's
   * complicated and we did it in the
   * decreasingSizeMustEventuallyDeallocateSurplusObjects test above.
   */
  @Test(timeout = 300)
  @Theory public void
  mustNotReallocateWhenReleasingExpiredObjectsIntoShrunkPool(
      PoolFixture fixture, ExecutorConfig ec) throws Exception {
    int startingSize = 5;
    int newSize = 1;
    CountingAllocator allocator = new CountingAllocator();
    Expiration<Poolable> expiration = new CountingExpiration(
        // our 5 items are not expired when we deplete the pool
        false, false, false, false, false,
        // but all items we try to claim after that *are* expired.
        true
        );
    config.setExpiration(expiration).setAllocator(allocator);
    config.setSize(startingSize);
    ResizablePool<GenericPoolable> pool = resizable(fixture, ec);
    List<GenericPoolable> objs = new ArrayList<GenericPoolable>();
    for (int i = 0; i < startingSize; i++) {
      objs.add(pool.claim(longTimeout));
    }
    assertThat(objs.size(), is(startingSize));
    pool.setTargetSize(newSize);
    for (int i = 0; i < startingSize - newSize; i++) {
      // release the surplus expired objects back into the pool
      objs.remove(0).release();
    }
    // now the released objects should not cause reallocations, so claim
    // returns null (it's still depleted) and allocation count stays put
    assertThat(pool.claim(shortTimeout), nullValue());
    assertThat(allocator.allocations(), is(startingSize));
  }

  // TODO increasing and decreasing size in quick succession must eventually settle on target size
}
