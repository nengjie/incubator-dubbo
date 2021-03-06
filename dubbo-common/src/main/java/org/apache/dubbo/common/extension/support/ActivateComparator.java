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
package org.apache.dubbo.common.extension.support;

import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.extension.SPI;

import java.util.Comparator;

/**
 * OrderComparator
 */
public class ActivateComparator implements Comparator<Object> {

    /**
     * 单例模式
     */
    public static final Comparator<Object> COMPARATOR = new ActivateComparator();

    @Override
    public int compare(Object o1, Object o2) {
        // 基本排序
        if (o1 == null && o2 == null) {
            return 0;
        }
        if (o1 == null) {
            return -1;
        }
        if (o2 == null) {
            return 1;
        }
        if (o1.equals(o2)) {
            return 0;
        }

        // to support com.alibab.dubbo.common.extension.Activate
        String[] a1Before, a2Before, a1After, a2After;
        int a1Order, a2Order;
        Class<?> inf = null;
        if (o1.getClass().getInterfaces().length > 0) {
            inf = o1.getClass().getInterfaces()[0];

            if (inf.getInterfaces().length > 0) {
                inf = inf.getInterfaces()[0];
            }
        }

        Activate a1 = o1.getClass().getAnnotation(Activate.class);
        if (a1 != null) {
            a1Before = a1.before();
            a1After = a1.after();
            a1Order = a1.order();
        } else {
            com.alibaba.dubbo.common.extension.Activate oa1 = o1.getClass().getAnnotation(com.alibaba.dubbo.common.extension.Activate.class);
            a1Before = oa1.before();
            a1After = oa1.after();
            a1Order = oa1.order();
        }
        Activate a2 = o2.getClass().getAnnotation(Activate.class);
        if (a2 != null) {
            a2Before = a2.before();
            a2After = a2.after();
            a2Order = a2.order();
        } else {
            com.alibaba.dubbo.common.extension.Activate oa2 = o2.getClass().getAnnotation(com.alibaba.dubbo.common.extension.Activate.class);
            a2Before = oa2.before();
            a2After = oa2.after();
            a2Order = oa2.order();
        }
        if ((a1Before.length > 0 || a1After.length > 0
                || a2Before.length > 0 || a2After.length > 0)
                && inf != null && inf.isAnnotationPresent(SPI.class)) {
            ExtensionLoader<?> extensionLoader = ExtensionLoader.getExtensionLoader(inf);
            if (a1Before.length > 0 || a1After.length > 0) {
                String n2 = extensionLoader.getExtensionName(o2.getClass());
                for (String before : a1Before) {
                    if (before.equals(n2)) {
                        return -1;
                    }
                }
                for (String after : a1After) {
                    if (after.equals(n2)) {
                        return 1;
                    }
                }
            }
            if (a2Before.length > 0 || a2After.length > 0) {
                String n1 = extensionLoader.getExtensionName(o1.getClass());
                for (String before : a2Before) {
                    if (before.equals(n1)) {
                        return 1;
                    }
                }
                for (String after : a2After) {
                    if (after.equals(n1)) {
                        return -1;
                    }
                }
            }
        }
        // 使用注解的 `order` 属性，排序。
        int n1 = a1 == null ? 0 : a1Order;
        int n2 = a2 == null ? 0 : a2Order;
        // never return 0 even if n1 equals n2, otherwise, o1 and o2 will override each other in collection like HashSet
        return n1 > n2 ? 1 : -1;
    }

}
