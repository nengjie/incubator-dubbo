/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.rpc.cluster.loadbalance;

import org.apache.dubbo.rpc.Invoker;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class RoundRobinLoadBalanceTest extends LoadBalanceBaseTest {

    private void assertStrictWRRResult(int loop, Map<Invoker, InvokeResult> resultMap) {
        int invokeCount = 0;
        for (InvokeResult invokeResult : resultMap.values()) {
            int count = (int) invokeResult.getCount().get();
            // Because it's a strictly round robin, so the abs delta should be < 10 too
            Assert.assertTrue("delta with expected count should < 10",
                    Math.abs(invokeResult.getExpected(loop) - count) < 10);
            invokeCount += count;
        }
        Assert.assertEquals("select failed!", invokeCount, loop);
    }

    @Test
    public void testRoundRobinLoadBalanceSelect() {
        int runs = 10000;
        Map<Invoker, AtomicLong> counter = getInvokeCounter(runs, RoundRobinLoadBalance.NAME);
        for (Invoker minvoker : counter.keySet()) {
            Long count = counter.get(minvoker).get();
            Assert.assertTrue("abs diff should < 1", Math.abs(count - runs / (0f + invokers.size())) < 1f);
        }
    }

    @Test
    public void testSelectByWeight() {
        final Map<Invoker, InvokeResult> totalMap = new HashMap<Invoker, InvokeResult>();
        final AtomicBoolean shouldBegin = new AtomicBoolean(false);
        final int runs = 10000;
        List<Thread> threads = new ArrayList<Thread>();
        int threadNum = 10;
        for (int i = 0; i < threadNum; i++) {
            threads.add(new Thread() {
                @Override
                public void run() {
                    while (!shouldBegin.get()) {
                        try {
                            sleep(5);
                        } catch (InterruptedException e) {
                        }
                    }
                    Map<Invoker, InvokeResult> resultMap = getWeightedInvokeResult(runs, RoundRobinLoadBalance.NAME);
                    synchronized (totalMap) {
                        for (Entry<Invoker, InvokeResult> entry : resultMap.entrySet()) {
                            if (!totalMap.containsKey(entry.getKey())) {
                                totalMap.put(entry.getKey(), entry.getValue());
                            } else {
                                totalMap.get(entry.getKey()).getCount().addAndGet(entry.getValue().getCount().get());
                            }
                        }
                    }
                }
            });
        }
        for (Thread thread : threads) {
            thread.start();
        }
        // let's rock it!
        shouldBegin.set(true);
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
            }
        }
        assertStrictWRRResult(runs * threadNum, totalMap);
    }

    @Test
    public void testNodeCacheShouldNotRecycle() {
        int loop = 10000;
        //tmperately add a new invoker
        weightInvokers.add(weightInvokerTmp);
        try {
            Map<Invoker, InvokeResult> resultMap = getWeightedInvokeResult(loop, RoundRobinLoadBalance.NAME);
            assertStrictWRRResult(loop, resultMap);

            // inner nodes cache judgement
            RoundRobinLoadBalance lb = (RoundRobinLoadBalance) getLoadBalance(RoundRobinLoadBalance.NAME);
            Assert.assertEquals(weightInvokers.size(), lb.getInvokerAddrList(weightInvokers, weightTestInvocation).size());

            weightInvokers.remove(weightInvokerTmp);

            resultMap = getWeightedInvokeResult(loop, RoundRobinLoadBalance.NAME);
            assertStrictWRRResult(loop, resultMap);

            Assert.assertNotEquals(weightInvokers.size(), lb.getInvokerAddrList(weightInvokers, weightTestInvocation).size());
        } finally {
            //prevent other UT's failure
            weightInvokers.remove(weightInvokerTmp);
        }
    }

    @Test
    public void testNodeCacheShouldRecycle() {
        {
            Field recycleTimeField = null;
            try {
                //change recycle time to 1 ms
                recycleTimeField = RoundRobinLoadBalance.class.getDeclaredField("RECYCLE_PERIOD");
                recycleTimeField.setAccessible(true);
                recycleTimeField.setInt(RoundRobinLoadBalance.class, 10);
            } catch (NoSuchFieldException e) {
                Assert.assertTrue("getField failed", true);
            } catch (SecurityException e) {
                Assert.assertTrue("getField failed", true);
            } catch (IllegalArgumentException e) {
                Assert.assertTrue("getField failed", true);
            } catch (IllegalAccessException e) {
                Assert.assertTrue("getField failed", true);
            }
        }

        int loop = 10000;
        //tmperately add a new invoker
        weightInvokers.add(weightInvokerTmp);
        try {
            Map<Invoker, InvokeResult> resultMap = getWeightedInvokeResult(loop, RoundRobinLoadBalance.NAME);
            assertStrictWRRResult(loop, resultMap);

            // inner nodes cache judgement
            RoundRobinLoadBalance lb = (RoundRobinLoadBalance) getLoadBalance(RoundRobinLoadBalance.NAME);
            Assert.assertEquals(weightInvokers.size(), lb.getInvokerAddrList(weightInvokers, weightTestInvocation).size());

            weightInvokers.remove(weightInvokerTmp);

            resultMap = getWeightedInvokeResult(loop, RoundRobinLoadBalance.NAME);
            assertStrictWRRResult(loop, resultMap);

            Assert.assertEquals(weightInvokers.size(), lb.getInvokerAddrList(weightInvokers, weightTestInvocation).size());
        } finally {
            //prevent other UT's failure
            weightInvokers.remove(weightInvokerTmp);
        }
    }

}
