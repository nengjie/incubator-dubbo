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
package org.apache.dubbo.common.extension;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.support.ActivateComparator;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ClassHelper;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.Holder;
import org.apache.dubbo.common.utils.StringUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

/**
 * Load dubbo extensions
 * <ul>
 * <li>auto inject dependency extension </li>
 * <li>auto wrap extension in wrapper </li>
 * <li>default extension is an adaptive instance</li>
 * </ul>
 *
 * @see <a href="http://java.sun.com/j2se/1.5.0/docs/guide/jar/jar.html#Service%20Provider">Service Provider in Java 5</a>
 * @see org.apache.dubbo.common.extension.SPI
 * @see org.apache.dubbo.common.extension.Adaptive
 * @see org.apache.dubbo.common.extension.Activate
 */
/**
 * @Description:    拓展加载器   Dubbo SPI 的核心
 * @Author:         nengjie
 * @CreateDate:     2018年12月21日00:40:46 
 */
public class ExtensionLoader<T> {

    private static final Logger logger = LoggerFactory.getLogger(ExtensionLoader.class);

    // ==============================  静态属性
    /**
     * 【静态属性】一方面，ExtensionLoader 是 ExtensionLoader 的管理容器。一个拓展( 拓展接口 )对应一个 ExtensionLoader 对象。
     * 例如，Protocol 和 Filter 分别对应一个 ExtensionLoader 对象。
     */

    private static final String SERVICES_DIRECTORY = "META-INF/services/";

    private static final String DUBBO_DIRECTORY = "META-INF/dubbo/";

    private static final String DUBBO_INTERNAL_DIRECTORY = DUBBO_DIRECTORY + "internal/";

    private static final Pattern NAME_SEPARATOR = Pattern.compile("\\s*[,]+\\s*");

    /**
     * 拓展加载器集合
     * key:拓展接口
     */
    private static final ConcurrentMap<Class<?>, ExtensionLoader<?>> EXTENSION_LOADERS = new ConcurrentHashMap<Class<?>, ExtensionLoader<?>>();

    /**
     *拓展实现类集合
     * key:拓展实现类
     * value:拓展对象
     */
    private static final ConcurrentMap<Class<?>, Object> EXTENSION_INSTANCES = new ConcurrentHashMap<Class<?>, Object>();

    // ============================== 对象属性
    /**
     * 【对象属性】另一方面，一个拓展通过其 ExtensionLoader 对象，加载它的拓展实现们。我们会发现多个属性都是 “cached“ 开头。
     * ExtensionLoader 考虑到性能和资源的优化，读取拓展配置后，会首先进行缓存。
     * 等到 Dubbo 代码真正用到对应的拓展实现时，进行拓展实现的对象的初始化。并且，初始化完成后，也会进行缓存。也就是说：
     *   1. 缓存加载的拓展配置
     *   2. 缓存创建的拓展实现对象
     */

    /**
     * 拓展接口 如：Protocol
     */
    private final Class<?> type;

    /**
     * 对象工厂
     *  用于调用 {@link #injectExtension(Object)} 方法，向拓展对象注入依赖属性
     *
     *  例如，StubProxyFactoryWrapper 中有 `Protocol protocol` 属性
     */
    private final ExtensionFactory objectFactory;

    /**
     * 缓存的拓展名与拓展类的映射
     *
     * 通过 {@link #loadExtensionClasses} 加载
     */
    private final ConcurrentMap<Class<?>, String> cachedNames = new ConcurrentHashMap<Class<?>, String>();

    /**
     *缓存的拓展实现类集合
     *
     * 不包含如下两种类型：
     * 1.自适应拓展实现类。例如 AdaptiveExtensionFactory
     * 2.带唯一参数为拓展接口的构造方法的实现类，或者说拓展 Wrapper实现类。例如，ProtocolFilterWrapper
     *    拓展 Wrapper 实现类，会添加到 {@link #cachedWrapperClasses} 中
     *
     *
     *    通过 {@link #loadExtensionClasses} 加载
     */
    private final Holder<Map<String, Class<?>>> cachedClasses = new Holder<Map<String, Class<?>>>();

    /**
     * 拓展名与 @Activate 的映射
     *
     * 例如，AccessLogFilter
     * 用于 {@link #getActivateExtension(URL, String)}
     */
    private final Map<String, Object> cachedActivates = new ConcurrentHashMap<String, Object>();

    /**
     * 缓存的拓展对象集合
     *
     * key：拓展名
     * value：拓展对象
     *
     * 例如，Protocol 拓展  key：dubbo value：DubboProtocol
     *
     */
    private final ConcurrentMap<String, Holder<Object>> cachedInstances = new ConcurrentHashMap<String, Holder<Object>>();


    /**
     *缓存的自适应( Adaptive )拓展对象
     */
    private final Holder<Object> cachedAdaptiveInstance = new Holder<Object>();

    /**
     * 缓存的自适应拓展对象的类
     * {@link #getAdaptiveExtensionClass()}
     */
    private volatile Class<?> cachedAdaptiveClass = null;

    /**
     * 缓存的默认拓展名
     * 通过 {@link SPI} 注解获得
     */
    private String cachedDefaultName;

    /**
     *创建 {@link #cachedAdaptiveInstance} 时发生的异常
     * 发生异常后，不再创建，参见 {@link #createAdaptiveExtension()}
     */
    private volatile Throwable createAdaptiveInstanceError;

    /**
     * 拓展 Wrapper 实现类集合
     *
     * 带唯一参数为拓展接口的构造方法的实现类
     *
     * 通过 {@link #loadExtensionClasses} 加载
     */
    private Set<Class<?>> cachedWrapperClasses;

    /**
     * 拓展名 与 加载对应拓展类发生的异常 的 映射
     *
     * key：拓展名
     * value：异常
     *
     *
     */
    private Map<String, IllegalStateException> exceptions = new ConcurrentHashMap<String, IllegalStateException>();

    /**
     * 对象工厂
     *
     * @param type
     */
    private ExtensionLoader(Class<?> type) {
        this.type = type;

        // objectFactory 用于调用 #injectExtension(instance) 方法时，向创建的拓展注入其依赖的属性。例如，CacheFilter.cacheFactory 属性。
        // 当拓展接口非 ExtensionFactory 时( 如果不加这个判断，会是一个死循环 )，调用 ExtensionLoader#getAdaptiveExtension() 方法，
        // 获得 ExtensionFactory 拓展接口的自适应拓展实现对象。为什么呢？
        objectFactory = (type == ExtensionFactory.class ? null : ExtensionLoader.getExtensionLoader(ExtensionFactory.class).getAdaptiveExtension());
    }

    private static <T> boolean withExtensionAnnotation(Class<T> type) {
        return type.isAnnotationPresent(SPI.class);
    }

    /**
     * 根据拓展点的接口，获得拓展加载器
     * @param type
     * @param <T>
     * @return
     */
    @SuppressWarnings("unchecked")
    public static <T> ExtensionLoader<T> getExtensionLoader(Class<T> type) {
        if (type == null) {
            throw new IllegalArgumentException("Extension type == null");
        }
        // 必须是接口
        if (!type.isInterface()) {
            throw new IllegalArgumentException("Extension type(" + type + ") is not interface!");
        }
        // 必须包含 @SPI 注解
        if (!withExtensionAnnotation(type)) {
            throw new IllegalArgumentException("Extension type(" + type +
                    ") is not extension, because WITHOUT @" + SPI.class.getSimpleName() + " Annotation!");
        }

        // 获得接口对应的拓展点加载器 从 EXTENSION_LOADERS 静态中获取拓展接口对应的 ExtensionLoader 对象。若不存在，则创建 ExtensionLoader 对象，并添加到 EXTENSION_LOADERS
        ExtensionLoader<T> loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);
        if (loader == null) {
            EXTENSION_LOADERS.putIfAbsent(type, new ExtensionLoader<T>(type));
            loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);
        }
        return loader;
    }

    // For testing purposes only
    public static void resetExtensionLoader(Class type) {
        ExtensionLoader loader = EXTENSION_LOADERS.get(type);
        if (loader != null) {
            // Remove all instances associated with this loader as well
            Map<String, Class<?>> classes = loader.getExtensionClasses();
            for (Map.Entry<String, Class<?>> entry : classes.entrySet()) {
                EXTENSION_INSTANCES.remove(entry.getValue());
            }
            classes.clear();
            EXTENSION_LOADERS.remove(type);
        }
    }

    private static ClassLoader findClassLoader() {
        return ClassHelper.getClassLoader(ExtensionLoader.class);
    }

    public String getExtensionName(T extensionInstance) {
        return getExtensionName(extensionInstance.getClass());
    }

    public String getExtensionName(Class<?> extensionClass) {
        getExtensionClasses();// load class
        return cachedNames.get(extensionClass);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, key, null)}
     *
     * @param url url
     * @param key url parameter key which used to get extension point names
     * @return extension list which are activated.
     * @see #getActivateExtension(org.apache.dubbo.common.URL, String, String)
     */
    public List<T> getActivateExtension(URL url, String key) {
        return getActivateExtension(url, key, null);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, values, null)}
     *
     * @param url    url
     * @param values extension point names
     * @return extension list which are activated
     * @see #getActivateExtension(org.apache.dubbo.common.URL, String[], String)
     */
    public List<T> getActivateExtension(URL url, String[] values) {
        return getActivateExtension(url, values, null);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, url.getParameter(key).split(","), null)}
     * 获得符合自动激活条件的拓展对象数组
     * @param url   url
     * @param key   url parameter key which used to get extension point names
     *              Dubbo URL 参数名
     * @param group group
     *              过滤分组名
     * @return extension list which are activated.
     * @see #getActivateExtension(org.apache.dubbo.common.URL, 0[], String)
     */
    public List<T> getActivateExtension(URL url, String key, String group) {
        // 从 Dubbo URL 获得参数值
        String value = url.getParameter(key);
        // 获得符合自动激活条件的拓展对象数组
        return getActivateExtension(url, value == null || value.length() == 0 ? null : Constants.COMMA_SPLIT_PATTERN.split(value), group);
    }

    /**
     * Get activate extensions.
     * 获得符合自动激活条件的拓展对象数组
     * @param url    url
     * @param values extension point names
     * @param group  group
     * @return extension list which are activated
     * @see org.apache.dubbo.common.extension.Activate
     */
    public List<T> getActivateExtension(URL url, String[] values, String group) {
        List<T> exts = new ArrayList<T>();
        List<String> names = values == null ? new ArrayList<String>(0) : Arrays.asList(values);
        // 处理自动激活的拓展对象们

        // 判断不存在配置 `"-name"` 。例如，<dubbo:service filter="-default" /> ，代表移除所有默认过滤器。
        if (!names.contains(Constants.REMOVE_VALUE_PREFIX + Constants.DEFAULT_KEY)) {
            // 获得拓展实现类数组
            getExtensionClasses();
            // 循环
            for (Map.Entry<String, Object> entry : cachedActivates.entrySet()) {
                String name = entry.getKey();
                Object activate = entry.getValue();

                String[] activateGroup, activateValue;

                // 判断是否 激活类接口的对象
                if (activate instanceof Activate) {
                    activateGroup = ((Activate) activate).group();
                    activateValue = ((Activate) activate).value();
                } else if (activate instanceof com.alibaba.dubbo.common.extension.Activate) {
                    activateGroup = ((com.alibaba.dubbo.common.extension.Activate) activate).group();
                    activateValue = ((com.alibaba.dubbo.common.extension.Activate) activate).value();
                } else {
                    continue;
                }
                // 匹配分组
                if (isMatchGroup(group, activateGroup)) {
                    // 获得拓展对象
                    T ext = getExtension(name);
                    // 不包含在自定义配置里。如果包含，会在下面的代码处理。
                    if (!names.contains(name)
                            && !names.contains(Constants.REMOVE_VALUE_PREFIX + name)// 判断是否配置移除。例如 <dubbo:service filter="-monitor" />，则 MonitorFilter 会被移除
                            && isActive(activateValue, url)) { // 判断是否激活
                        exts.add(ext);
                    }
                }
            }

            // 排序
            Collections.sort(exts, ActivateComparator.COMPARATOR);
        }
        // 处理自定义配置的拓展对象们。例如在 <dubbo:service filter="demo" /> ，代表需要加入 DemoFilter （这个是笔者自定义的）。
        List<T> usrs = new ArrayList<T>();
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            if (!name.startsWith(Constants.REMOVE_VALUE_PREFIX)
                    && !names.contains(Constants.REMOVE_VALUE_PREFIX + name)) {
                // 将配置的自定义在自动激活的拓展对象们前面。例如，<dubbo:service filter="demo,default,demo2" /> ，则 DemoFilter 就会放在默认的过滤器前面。
                if (Constants.DEFAULT_KEY.equals(name)) {
                    if (!usrs.isEmpty()) {
                        exts.addAll(0, usrs);
                        usrs.clear();
                    }
                } else {
                    // 获得拓展对象
                    T ext = getExtension(name);
                    usrs.add(ext);
                }
            }
        }
        // 添加到结果集
        if (!usrs.isEmpty()) {
            exts.addAll(usrs);
        }
        return exts;
    }

    /**
     * 匹配分组
     * @param group 过滤的分组条件。若为空，无需过滤
     * @param groups 配置的分组
     * @return 是否匹配
     */
    private boolean isMatchGroup(String group, String[] groups) {
        // 为空，无需过滤
        if (group == null || group.length() == 0) {
            return true;
        }
        // 匹配
        if (groups != null && groups.length > 0) {
            for (String g : groups) {
                if (group.equals(g)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 是否激活，通过 Dubbo URL 中是否存在参数名为 `@Activate.value` ，并且参数值非空。
     * @param keys 自动激活注解
     * @param url Dubbo URL
     * @return 是否激活
     */
    private boolean isActive(String[] keys, URL url) {
        if (keys.length == 0) {
            return true;
        }
        for (String key : keys) {
            for (Map.Entry<String, String> entry : url.getParameters().entrySet()) {
                String k = entry.getKey();
                String v = entry.getValue();
                if ((k.equals(key) || k.endsWith("." + key))
                        && ConfigUtils.isNotEmpty(v)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Get extension's instance. Return <code>null</code> if extension is not found or is not initialized. Pls. note
     * that this method will not trigger extension load.
     * <p>
     * In order to trigger extension load, call {@link #getExtension(String)} instead.
     *
     * @see #getExtension(String)
     */
    @SuppressWarnings("unchecked")
    public T getLoadedExtension(String name) {
        //
        if (name == null || name.length() == 0) {
            throw new IllegalArgumentException("Extension name == null");
        }

        //
        Holder<Object> holder = cachedInstances.get(name);

        //
        if (holder == null) {
            cachedInstances.putIfAbsent(name, new Holder<Object>());
            holder = cachedInstances.get(name);
        }

        return (T) holder.get();
    }

    /**
     * Return the list of extensions which are already loaded.
     * <p>
     * Usually {@link #getSupportedExtensions()} should be called in order to get all extensions.
     *
     * @see #getSupportedExtensions()
     */
    public Set<String> getLoadedExtensions() {
        return Collections.unmodifiableSet(new TreeSet<String>(cachedInstances.keySet()));
    }

    /**
     * Find the extension with the given name. If the specified name is not found, then {@link IllegalStateException}
     * will be thrown.
     * 返回指定名字的扩展对象。如果指定名字的扩展不存在，则抛异常 IllegalStateException
     */
    @SuppressWarnings("unchecked")
    public T getExtension(String name) {
        if (name == null || name.length() == 0) {
            throw new IllegalArgumentException("Extension name == null");
        }
        // 查找 默认的 拓展对象
        if ("true".equals(name)) {
            // 获取默认的拓展实现类
            return getDefaultExtension();
        }
        // 从 缓存中 获得对应的拓展对象 Holder，顾名思义，用于持有目标对象
        Holder<Object> holder = cachedInstances.get(name);
        if (holder == null) {
            cachedInstances.putIfAbsent(name, new Holder<Object>());
            holder = cachedInstances.get(name);
        }
        Object instance = holder.get();
        if (instance == null) {
            // 双重检查
            synchronized (holder) {
                instance = holder.get();
                // 从 缓存中 未获取到，进行创建缓存对象。
                if (instance == null) {
                    // 创建拓展实例
                    instance = createExtension(name);
                    // 设置实例到 holder 中
                    holder.set(instance);
                }
            }
        }
        return (T) instance;
    }

    /**
     * Return default extension, return <code>null</code> if it's not configured.
     */
    public T getDefaultExtension() {
        getExtensionClasses();
        if (null == cachedDefaultName || cachedDefaultName.length() == 0
                || "true".equals(cachedDefaultName)) {
            return null;
        }
        return getExtension(cachedDefaultName);
    }

    public boolean hasExtension(String name) {
        if (name == null || name.length() == 0) {
            throw new IllegalArgumentException("Extension name == null");
        }
        Class<?> c = this.getExtensionClass(name);
        return c != null;
    }

    public Set<String> getSupportedExtensions() {
        Map<String, Class<?>> clazzes = getExtensionClasses();
        return Collections.unmodifiableSet(new TreeSet<String>(clazzes.keySet()));
    }

    /**
     * Return default extension name, return <code>null</code> if not configured.
     */
    public String getDefaultExtensionName() {
        getExtensionClasses();
        return cachedDefaultName;
    }

    /**
     * Register new extension via API
     *
     * @param name  extension name
     * @param clazz extension class
     * @throws IllegalStateException when extension with the same name has already been registered.
     */
    public void addExtension(String name, Class<?> clazz) {
        getExtensionClasses(); // load classes

        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Input type " +
                    clazz + "not implement Extension " + type);
        }
        if (clazz.isInterface()) {
            throw new IllegalStateException("Input type " +
                    clazz + "can not be interface!");
        }

        if (!clazz.isAnnotationPresent(Adaptive.class)) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalStateException("Extension name is blank (Extension " + type + ")!");
            }
            if (cachedClasses.get().containsKey(name)) {
                throw new IllegalStateException("Extension name " +
                        name + " already existed(Extension " + type + ")!");
            }

            cachedNames.put(clazz, name);
            cachedClasses.get().put(name, clazz);
        } else {
            if (cachedAdaptiveClass != null) {
                throw new IllegalStateException("Adaptive Extension already existed(Extension " + type + ")!");
            }

            cachedAdaptiveClass = clazz;
        }
    }

    /**
     * Replace the existing extension via API
     *
     * @param name  extension name
     * @param clazz extension class
     * @throws IllegalStateException when extension to be placed doesn't exist
     * @deprecated not recommended any longer, and use only when test
     */
    @Deprecated
    public void replaceExtension(String name, Class<?> clazz) {
        getExtensionClasses(); // load classes

        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Input type " +
                    clazz + "not implement Extension " + type);
        }
        if (clazz.isInterface()) {
            throw new IllegalStateException("Input type " +
                    clazz + "can not be interface!");
        }

        if (!clazz.isAnnotationPresent(Adaptive.class)) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalStateException("Extension name is blank (Extension " + type + ")!");
            }
            if (!cachedClasses.get().containsKey(name)) {
                throw new IllegalStateException("Extension name " +
                        name + " not existed(Extension " + type + ")!");
            }

            cachedNames.put(clazz, name);
            cachedClasses.get().put(name, clazz);
            cachedInstances.remove(name);
        } else {
            if (cachedAdaptiveClass == null) {
                throw new IllegalStateException("Adaptive Extension not existed(Extension " + type + ")!");
            }

            cachedAdaptiveClass = clazz;
            cachedAdaptiveInstance.set(null);
        }
    }

    /**
     * 获得自适应拓展对象
     * getAdaptiveExtension 方法首先会检查缓存，缓存未命中，则调用 createAdaptiveExtension 方法创建自适应拓展。
     * @return
     */
    @SuppressWarnings("unchecked")
    public T getAdaptiveExtension() {
        // 从缓存中获取自适应拓展
        Object instance = cachedAdaptiveInstance.get();
        // 缓存未命中
        if (instance == null) {
            if (createAdaptiveInstanceError == null) {
                synchronized (cachedAdaptiveInstance) {
                    instance = cachedAdaptiveInstance.get();
                    if (instance == null) {
                        try {
                            // 创建自适应拓展
                            instance = createAdaptiveExtension();
                            // 设置自适应拓展到缓存中
                            cachedAdaptiveInstance.set(instance);
                        } catch (Throwable t) {
                            // 记录异常
                            createAdaptiveInstanceError = t;
                            throw new IllegalStateException("fail to create adaptive instance: " + t.toString(), t);
                        }
                    }
                }
               // 若之前创建报错，则抛出异常 IllegalStateException
            } else {
                throw new IllegalStateException("fail to create adaptive instance: " + createAdaptiveInstanceError.toString(), createAdaptiveInstanceError);
            }
        }

        return (T) instance;
    }

    private IllegalStateException findException(String name) {
        for (Map.Entry<String, IllegalStateException> entry : exceptions.entrySet()) {
            if (entry.getKey().toLowerCase().contains(name.toLowerCase())) {
                return entry.getValue();
            }
        }
        StringBuilder buf = new StringBuilder("No such extension " + type.getName() + " by name " + name);


        int i = 1;
        for (Map.Entry<String, IllegalStateException> entry : exceptions.entrySet()) {
            if (i == 1) {
                buf.append(", possible causes: ");
            }

            buf.append("\r\n(");
            buf.append(i++);
            buf.append(") ");
            buf.append(entry.getKey());
            buf.append(":\r\n");
            buf.append(StringUtils.toString(entry.getValue()));
        }
        return new IllegalStateException(buf.toString());
    }

    /**
     * 创建拓展名的拓展对象，并缓存。
     *
     * 包含了如下的步骤：
     * 1.通过 getExtensionClasses 获取所有的拓展类
     * 2.通过反射创建拓展对象
     * 3.向拓展对象中注入依赖
     * 4.将拓展对象包裹在相应的 Wrapper 对象中
     *
     * @param name
     * @return
     */
    @SuppressWarnings("unchecked")
    private T createExtension(String name) {
        // 从配置文件中加载所有的拓展类，可得到“配置项名称”到“配置类”的映射关系表
        Class<?> clazz = getExtensionClasses().get(name);
        // 如果为空，则抛出异常
        if (clazz == null) {
            throw findException(name);
        }
        try {
            // 从缓存中，获得拓展对象。
            T instance = (T) EXTENSION_INSTANCES.get(clazz);
            // 当缓存不存在时，创建拓展对象，并添加到缓存中。
            if (instance == null) {
                // 通过反射创建实例
                EXTENSION_INSTANCES.putIfAbsent(clazz, clazz.newInstance());
                instance = (T) EXTENSION_INSTANCES.get(clazz);
            }
            // 向实例中注入依赖
            injectExtension(instance);

            // 循环创建 Wrapper 实例
            /**
             * Wrapper 类同样实现了扩展点接口，但是 Wrapper 不是扩展点的真正实现。它的用途主要是用于从 ExtensionLoader 返回扩展点时，
             * 包装在真正的扩展点实现外。即从 ExtensionLoader 中返回的实际上是 Wrapper 类的实例，Wrapper 持有了实际的扩展点实现类。
             * 扩展点的 Wrapper 类可以有多个，也可以根据需要新增。
             * 通过 Wrapper 类可以把所有扩展点公共逻辑移至 Wrapper 中。新加的 Wrapper 在所有的扩展点上添加了逻辑，有些类似 AOP，即 Wrapper 代理了扩展点。
             */
            Set<Class<?>> wrapperClasses = cachedWrapperClasses;
            if (wrapperClasses != null && !wrapperClasses.isEmpty()) {
                for (Class<?> wrapperClass : wrapperClasses) {
                    // 将当前 instance 作为参数传给 Wrapper 的构造方法，并通过反射创建 Wrapper 实例。
                    // 然后向 Wrapper 实例中注入依赖，最后将 Wrapper 实例再次赋值给 instance 变量
                    instance = injectExtension((T) wrapperClass.getConstructor(type).newInstance(instance));
                }
            }
            return instance;
        } catch (Throwable t) {
            throw new IllegalStateException("Extension instance(name: " + name + ", class: " +
                    type + ")  could not be instantiated: " + t.getMessage(), t);
        }
    }

    /**
     * 注入依赖的属性
     * Dubbo IOC 是通过 setter 方法注入依赖。
     * Dubbo 首先会通过反射获取到实例的所有方法，然后再遍历方法列表，检测方法名是否具有 setter 方法特征。
     * 若有，则通过 ObjectFactory 获取依赖对象，最后通过反射调用 setter 方法将依赖设置到目标对象中。
     * @param instance 拓展对象
     * @return 拓展对象
     */
    private T injectExtension(T instance) {
        try {
            if (objectFactory != null) {
                // 遍历目标类的所有方法
                for (Method method : instance.getClass().getMethods()) {
                    // 检测方法是否以 set 开头，且方法仅有一个参数，且方法访问级别为 public
                    if (method.getName().startsWith("set")
                            && method.getParameterTypes().length == 1
                            && Modifier.isPublic(method.getModifiers())) {
                        /**
                         * Check {@link DisableInject} to see if we need auto injection for this property
                         */
                        if (method.getAnnotation(DisableInject.class) != null) {
                            continue;
                        }
                        // 获得属性的类型
                        // 获取 setter 方法参数类型
                        Class<?> pt = method.getParameterTypes()[0];
                        try {
                            // 获取属性名，比如 setName 方法对应属性名 name
                            String property = method.getName().length() > 3 ? method.getName().substring(3, 4).toLowerCase() + method.getName().substring(4) : "";
                            // 从 ObjectFactory 中获取依赖对象
                            Object object = objectFactory.getExtension(pt, property);
                            // 设置属性值
                            if (object != null) {
                                // 通过反射调用 setter 方法设置依赖
                                method.invoke(instance, object);
                            }
                        } catch (Exception e) {
                            logger.error("fail to inject via method " + method.getName()
                                    + " of interface " + type.getName() + ": " + e.getMessage(), e);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        return instance;
    }


    private Class<?> getExtensionClass(String name) {
        if (type == null) {
            throw new IllegalArgumentException("Extension type == null");
        }
        if (name == null) {
            throw new IllegalArgumentException("Extension name == null");
        }
        return getExtensionClasses().get(name);
    }

    /**
     * 获取所有的拓展类
     *
     * cachedClasses 属性，缓存的拓展实现类集合
     *   它不包含如下两种类型的拓展实现：
     *      1. 自适应拓展实现类。例如 AdaptiveExtensionFactory
     *               拓展 Adaptive 实现类，会添加到 cachedAdaptiveClass 属性中
     *      2. 带唯一参数为拓展接口的构造方法的实现类，或者说拓展 Wrapper 实现类。例如，ProtocolFilterWrapper
     *               拓展 Wrapper 实现类，会添加到 cachedWrapperClasses 属性中
     *
     *  总结来说，cachedClasses + cachedAdaptiveClass + cachedWrapperClasses 才是完整缓存的拓展实现类的配置
     *
     *  这里也是先检查缓存，若缓存未命中，则通过 synchronized 加锁。加锁后再次检查缓存，并判空。
     *  此时如果 classes 仍为 null，则通过 loadExtensionClasses 加载拓展类。
     *
     * @return 拓展实现类数组
     */
    private Map<String, Class<?>> getExtensionClasses() {
        // 从缓存中获取已加载的拓展类
        Map<String, Class<?>> classes = cachedClasses.get();

        // 双重检查
        if (classes == null) {
            synchronized (cachedClasses) {
                // 再次尝试从缓存中获取
                classes = cachedClasses.get();

                if (classes == null) {
                    // 从配置文件中，加载拓展类
                    classes = loadExtensionClasses();
                    // 设置到缓存中
                    cachedClasses.set(classes);
                }
            }
        }
        return classes;
    }

    /**
     * 从多个配置文件中，加载拓展实现类数组。
     * loadExtensionClasses 方法总共做了两件事情，
     * 一是对 SPI 注解进行解析，
     * 二是调用 loadDirectory 方法加载指定文件夹配置文件。
     * @return
     */
    // synchronized in getExtensionClasses
    private Map<String, Class<?>> loadExtensionClasses() {
        // 获取 SPI 注解，这里的 type 变量是在调用 getExtensionLoader 方法时传入的
        final SPI defaultAnnotation = type.getAnnotation(SPI.class);
        if (defaultAnnotation != null) {
            String value = defaultAnnotation.value();
            if ((value = value.trim()).length() > 0) {
                // 对 SPI 注解内容进行切分
                String[] names = NAME_SEPARATOR.split(value);
                // 检测 SPI 注解内容是否合法，不合法则抛出异常
                if (names.length > 1) {
                    throw new IllegalStateException("more than 1 default extension name on extension " + type.getName()
                            + ": " + Arrays.toString(names));
                }
                // 设置默认名称，参考 getDefaultExtension 方法
                if (names.length == 1) {
                    cachedDefaultName = names[0];
                }
            }
        }

        // 从配置文件中，加载拓展实现类数组   注意，此处配置文件的加载顺序。
        Map<String, Class<?>> extensionClasses = new HashMap<String, Class<?>>();
        // 加载指定文件夹下的配置文件
        loadDirectory(extensionClasses, DUBBO_INTERNAL_DIRECTORY, type.getName());
        loadDirectory(extensionClasses, DUBBO_INTERNAL_DIRECTORY, type.getName().replace("org.apache", "com.alibaba"));
        loadDirectory(extensionClasses, DUBBO_DIRECTORY, type.getName());
        loadDirectory(extensionClasses, DUBBO_DIRECTORY, type.getName().replace("org.apache", "com.alibaba"));
        loadDirectory(extensionClasses, SERVICES_DIRECTORY, type.getName());
        loadDirectory(extensionClasses, SERVICES_DIRECTORY, type.getName().replace("org.apache", "com.alibaba"));
        return extensionClasses;
    }


    /**
     * 从一个配置文件中，加载拓展实现类数组。
     * loadDirectory 方法先通过 classLoader 获取所有资源链接，
     * 然后再通过 loadResource 方法加载资源。
     * @param extensionClasses 拓展类名数组
     * @param dir 文件名
     * @param type
     */
    private void loadDirectory(Map<String, Class<?>> extensionClasses, String dir, String type) {
        // 完整的文件名  例如："META-INF/dubbo/internal/com.alibaba.dubbo.common.extension.ExtensionFactory"
        // fileName = 文件夹路径 + type 全限定名
        String fileName = dir + type;
        try {
            Enumeration<java.net.URL> urls;
            // 获得文件名对应的所有文件数组
            // 根据文件名加载所有的同名文件
            ClassLoader classLoader = findClassLoader();
            if (classLoader != null) {
                urls = classLoader.getResources(fileName);
            } else {
                urls = ClassLoader.getSystemResources(fileName);
            }

            // 遍历文件数组
            if (urls != null) {
                while (urls.hasMoreElements()) {
                    java.net.URL resourceURL = urls.nextElement();
                    // 加载资源
                    loadResource(extensionClasses, classLoader, resourceURL);
                }
            }
        } catch (Throwable t) {
            logger.error("Exception when load extension class(interface: " +
                    type + ", description file: " + fileName + ").", t);
        }
    }

    /**
     * 加载资源
     * loadResource 方法用于读取和解析配置文件，并通过反射加载类，最后调用 loadClass 方法进行其他操作。
     * @param extensionClasses
     * @param classLoader
     * @param resourceURL
     */
    private void loadResource(Map<String, Class<?>> extensionClasses, ClassLoader classLoader, java.net.URL resourceURL) {
        try {
            // 读取一个文件流
            BufferedReader reader = new BufferedReader(new InputStreamReader(resourceURL.openStream(), "utf-8"));
            try {
                String line;
                // 按行读取配置内容
                while ((line = reader.readLine()) != null) {
                    // 跳过当前被注释掉的情况，例如 #spring=xxxxxxxxx
                    final int ci = line.indexOf('#');
                    if (ci >= 0) {
                        // 截取 # 之前的字符串，# 之后的内容为注释，需要忽略
                        line = line.substring(0, ci);
                    }

                    line = line.trim();
                    if (line.length() > 0) {
                        try {

                            String name = null;
                            int i = line.indexOf('=');
                            if (i > 0) {

                                // 以等于号 = 为界，截取键与值
                                name = line.substring(0, i).trim();
                                line = line.substring(i + 1).trim();
                            }
                            if (line.length() > 0) {

                                // 加载类，并通过 loadClass 方法对类进行缓存
                                loadClass(extensionClasses, resourceURL, Class.forName(line, true, classLoader), name);
                            }

                            // 发生异常，记录到异常集合
                        } catch (Throwable t) {
                            IllegalStateException e = new IllegalStateException("Failed to load extension class(interface: " + type + ", class line: " + line + ") in " + resourceURL + ", cause: " + t.getMessage(), t);
                            exceptions.put(line, e);
                        }
                    }
                }
                // end of while read lines
            } finally {
                reader.close();
            }
            // end of while urls
        } catch (Throwable t) {
            logger.error("Exception when load extension class(interface: " +
                    type + ", class file: " + resourceURL + ") in " + resourceURL, t);
        }
    }

    /**
     * loadClass 方法操作了不同的缓存，比如 cachedAdaptiveClass、cachedWrapperClasses 和 cachedNames 等等。
     * @param extensionClasses
     * @param resourceURL
     * @param clazz
     * @param name
     * @throws NoSuchMethodException
     */
    private void loadClass(Map<String, Class<?>> extensionClasses, java.net.URL resourceURL, Class<?> clazz, String name) throws NoSuchMethodException {
        // 判断拓展实现，是否实现拓展接口
        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Error when load extension class(interface: " +
                    type + ", class line: " + clazz.getName() + "), class "
                    + clazz.getName() + "is not subtype of interface.");
        }

        // 检测目标类上是否有 Adaptive 注解
        if (clazz.isAnnotationPresent(Adaptive.class)) {
            if (cachedAdaptiveClass == null) {
                // 设置 cachedAdaptiveClass缓存
                cachedAdaptiveClass = clazz;
            } else if (!cachedAdaptiveClass.equals(clazz)) {
                throw new IllegalStateException("More than 1 adaptive class found: "
                        + cachedAdaptiveClass.getClass().getName()
                        + ", " + clazz.getClass().getName());
            }

            // 检测 clazz 是否是 Wrapper 类型
        } else if (isWrapperClass(clazz)) {
            Set<Class<?>> wrappers = cachedWrapperClasses;
            if (wrappers == null) {
                cachedWrapperClasses = new ConcurrentHashSet<Class<?>>();
                wrappers = cachedWrapperClasses;
            }

            // 存储 clazz 到 cachedWrapperClasses 缓存中
            wrappers.add(clazz);

            // 程序进入此分支，表明 clazz 是一个普通的拓展类
        } else {
            // 检测 clazz 是否有默认的构造方法，如果没有，则抛出异常
            clazz.getConstructor();

            if (name == null || name.length() == 0) {
                // 如果 name 为空，则尝试从 Extension 注解中获取 name，或使用小写的类名作为 name
                name = findAnnotationName(clazz);

                if (name.length() == 0) {
                    throw new IllegalStateException("No such extension name for the class " + clazz.getName() + " in the config " + resourceURL);
                }
            }
            // 切分 name   获得拓展名，可以是数组，有多个拓展名。
            String[] names = NAME_SEPARATOR.split(name);
            if (names != null && names.length > 0) {
                // 缓存 @Activate 到 `cachedActivates`
                Activate activate = clazz.getAnnotation(Activate.class);
                if (activate != null) {

                    // 如果类上有 Activate 注解，则使用 names 数组的第一个元素作为键，
                    // 存储 name 到 Activate 注解对象的映射关系
                    cachedActivates.put(names[0], activate);
                } else {
                    // support com.alibaba.dubbo.common.extension.Activate
                    com.alibaba.dubbo.common.extension.Activate oldActivate = clazz.getAnnotation(com.alibaba.dubbo.common.extension.Activate.class);
                    if (oldActivate != null) {
                        cachedActivates.put(names[0], oldActivate);
                    }
                }
                for (String n : names) {
                    // 缓存到 `cachedNames`
                    if (!cachedNames.containsKey(clazz)) {
                        // 存储 Class 到名称的映射关系
                        cachedNames.put(clazz, n);
                    }
                    // 缓存拓展实现类到 `extensionClasses`
                    Class<?> c = extensionClasses.get(n);
                    if (c == null) {
                        // 存储名称到 Class 的映射关系
                        extensionClasses.put(n, clazz);
                    } else if (c != clazz) {
                        throw new IllegalStateException("Duplicate extension " + type.getName() + " name " + n + " on " + c.getName() + " and " + clazz.getName());
                    }
                }
            }
        }
    }

    private boolean isWrapperClass(Class<?> clazz) {
        try {
            clazz.getConstructor(type);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private String findAnnotationName(Class<?> clazz) {
        org.apache.dubbo.common.Extension extension = clazz.getAnnotation(org.apache.dubbo.common.Extension.class);
        if (extension == null) {
            String name = clazz.getSimpleName();
            if (name.endsWith(type.getSimpleName())) {
                name = name.substring(0, name.length() - type.getSimpleName().length());
            }
            return name.toLowerCase();
        }
        return extension.value();
    }

    /**
     * 创建自适应拓展对象。
     * 包含了三个逻辑，分别如下：
     * 1.调用 getAdaptiveExtensionClass 方法获取自适应拓展 Class 对象
     * 2.通过反射进行实例化
     * 3.调用 injectExtension 方法向拓展实例中注入依赖
     *
     * 前两个逻辑比较好理解，第三个逻辑用于向自适应拓展对象中注入依赖。这个逻辑看似多余，但有存在的必要，这里简单说明一下。
     * 前面说过，Dubbo 中有两种类型的自适应拓展，一种是手工编码的，一种是自动生成的。手工编码的自适应拓展中可能存在着一些依赖，
     * 而自动生成的 Adaptive 拓展则不会依赖其他类。这里调用 injectExtension 方法的目的是为手工编码的自适应拓展注入依赖，
     * @return
     */
    @SuppressWarnings("unchecked")
    private T createAdaptiveExtension() {
        try {
            // 获取自适应拓展类，并通过反射实例化
            return injectExtension((T) getAdaptiveExtensionClass().newInstance());
        } catch (Exception e) {
            throw new IllegalStateException("Can not create adaptive extension " + type + ", cause: " + e.getMessage(), e);
        }
    }

    /**
     * 获得自适应拓展类。
     * 包含了三个逻辑，如下：
     * 1.调用 getExtensionClasses 获取所有的拓展类
     * 2.检查缓存，若缓存不为空，则返回缓存
     * 3.若缓存为空，则调用 createAdaptiveExtensionClass 创建自适应拓展类
     * 这三个逻辑看起来平淡无奇，似乎没有多讲的必要。但是这些平淡无奇的代码中隐藏了着一些细节，需要说明一下。
     * 首先从第一个逻辑说起，getExtensionClasses 这个方法用于获取某个接口的所有实现类。
     * 比如该方法可以获取 Protocol 接口的 DubboProtocol、HttpProtocol、InjvmProtocol 等实现类。在获取实现类的过程中，
     * 如果某个某个实现类被 Adaptive 注解修饰了，那么该类就会被赋值给 cachedAdaptiveClass 变量。此时，上面步骤中的第二步条件成立（缓存不为空），
     * 直接返回 cachedAdaptiveClass 即可。如果所有的实现类均未被 Adaptive 注解修饰，那么执行第三步逻辑，创建自适应拓展类。
     * @return
     */
    private Class<?> getAdaptiveExtensionClass() {
        // 通过 SPI 获取所有的拓展类
        getExtensionClasses();
        // 检查缓存，若缓存不为空，则直接返回缓存
        if (cachedAdaptiveClass != null) {
            return cachedAdaptiveClass;
        }
        // 调用 #createAdaptiveExtensionClass() 方法，自动生成自适应拓展的代码实现，并编译后返回该类。
        // 创建自适应拓展类
        return cachedAdaptiveClass = createAdaptiveExtensionClass();
    }

    /**
     * 创建自适应拓展类
     * 该方法用于生成自适应拓展类，该方法首先会生成自适应拓展类的源码，
     * 然后通过 Compiler 实例（Dubbo 默认使用 javassist 作为编译器）编译源码，得到代理类 Class 实例。
     * @return
     */
    private Class<?> createAdaptiveExtensionClass() {
        // 自动生成自适应拓展的代码实现的字符串
        // 构建自适应拓展代码
        String code = createAdaptiveExtensionClassCode();
        // 编译代码，并返回该类
        ClassLoader classLoader = findClassLoader();
        // 获取编译器实现类
        org.apache.dubbo.common.compiler.Compiler compiler = ExtensionLoader.getExtensionLoader(org.apache.dubbo.common.compiler.Compiler.class).getAdaptiveExtension();
        // 编译代码，生成 Class
        return compiler.compile(code, classLoader);
    }

    /**
     * 自适应拓展类代码生成
     * @return
     */
    private String createAdaptiveExtensionClassCode() {
        StringBuilder codeBuilder = new StringBuilder();

        /**
         *  Adaptive 注解检测
         */
        // 通过反射获取所有的方法
        Method[] methods = type.getMethods();
        boolean hasAdaptiveAnnotation = false;
        // 遍历方法列表
        for (Method m : methods) {
            // 检测方法上是否有 Adaptive 注解
            if (m.isAnnotationPresent(Adaptive.class)) {
                hasAdaptiveAnnotation = true;
                break;
            }
        }
        // no need to generate adaptive class since there's no adaptive method found.
        // 若所有的方法上均无 Adaptive 注解，则抛出异常
        if (!hasAdaptiveAnnotation) {
            throw new IllegalStateException("No adaptive method on extension " + type.getName() + ", refuse to create the adaptive class!");
        }

        /**
         *  生成类
         */

        // 生成 package 代码：package + type 所在包
        codeBuilder.append("package ").append(type.getPackage().getName()).append(";");
        // 生成 import 代码：import + ExtensionLoader 全限定名
        codeBuilder.append("\nimport ").append(ExtensionLoader.class.getName()).append(";");
        // 生成类代码：public class + type简单名称 + $Adaptive + implements + type全限定名 + {
        codeBuilder.append("\npublic class ").append(type.getSimpleName()).append("$Adaptive").append(" implements ").append(type.getCanonicalName()).append(" {");

        codeBuilder.append("\nprivate static final org.apache.dubbo.common.logger.Logger logger = org.apache.dubbo.common.logger.LoggerFactory.getLogger(ExtensionLoader.class);");
        codeBuilder.append("\nprivate java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger(0);\n");

        // ${生成方法}
        for (Method method : methods) {
            // 返回类型
            Class<?> rt = method.getReturnType();
            // 参数类型数组
            Class<?>[] pts = method.getParameterTypes();
            // 异常类型数组
            Class<?>[] ets = method.getExceptionTypes();

            Adaptive adaptiveAnnotation = method.getAnnotation(Adaptive.class);
            // 方法体的代码
            StringBuilder code = new StringBuilder(512);
            // 非 @Adaptive 注解，生成代码：生成的方法为直接抛出异常。因为，非自适应的接口不应该被调用。

            // 一个方法可以被 Adaptive 注解修饰，也可以不被修饰。这里将未被 Adaptive 注解修饰的方法称为“无 Adaptive 注解方法”，
            // 如果方法上无 Adaptive 注解，则生成 throw new UnsupportedOperationException(...) 代码
            if (adaptiveAnnotation == null) {
                // 生成的代码格式如下：
                // throw new UnsupportedOperationException(
                //     "method " + 方法签名 + of interface + 全限定接口名 + is not adaptive method!”)
                code.append("throw new UnsupportedOperationException(\"method ")
                        .append(method.toString()).append(" of interface ")
                        .append(type.getName()).append(" is not adaptive method!\");");
                // @Adaptive 注解，生成方法体的代码
            } else {
                // 获取 URL 数据，并为之生成判空和赋值代码。
                // 寻找 Dubbo URL 参数的位置
                int urlTypeIndex = -1;
                // 遍历参数列表，确定 URL 参数位置
                for (int i = 0; i < pts.length; ++i) {
                    if (pts[i].equals(URL.class)) {
                        urlTypeIndex = i;
                        break;
                    }
                }
                // found parameter in URL type
                // 有类型为URL的参数，生成代码：生成校验 URL 非空的代码
                // urlTypeIndex != -1，表示参数列表中存在 URL 参数
                if (urlTypeIndex != -1) {
                    // 为 URL 类型参数生成判空代码，格式如下：
                    // if (arg + urlTypeIndex == null)
                    //     throw new IllegalArgumentException("url == null");
                    String s = String.format("\nif (arg%d == null) throw new IllegalArgumentException(\"url == null\");",
                            urlTypeIndex);
                    code.append(s);

                    // 为 URL 类型参数生成赋值代码，形如 URL url = arg1
                    s = String.format("\n%s url = arg%d;", URL.class.getName(), urlTypeIndex);
                    code.append(s);
                }
                // did not find parameter in URL type
                // 参数列表中不存在 URL 类型参数
                else {
                    String attribMethod = null;

                    // find URL getter method
                    // 找到参数的URL属性 。例如，Invoker 有 `#getURL()` 方法。
                    LBL_PTS:
                    // 遍历方法的参数类型列表
                    for (int i = 0; i < pts.length; ++i) {
                        // 获取某一类型参数的全部方法
                        Method[] ms = pts[i].getMethods();
                        // 遍历方法列表，寻找可返回 URL 的 getter 方法
                        for (Method m : ms) {
                            String name = m.getName();
                            // 1. 方法名以 get 开头，或方法名大于3个字符
                            // 2. 方法的访问权限为 public
                            // 3. 非静态方法
                            // 4. 方法参数数量为0
                            // 5. 方法返回值类型为 URL
                            if ((name.startsWith("get") || name.length() > 3)
                                    && Modifier.isPublic(m.getModifiers())
                                    && !Modifier.isStatic(m.getModifiers())
                                    && m.getParameterTypes().length == 0
                                    && m.getReturnType() == URL.class) {
                                urlTypeIndex = i;
                                attribMethod = name;
                                // 结束 for (int i = 0; i < pts.length; ++i) 循环
                                break LBL_PTS;
                            }
                        }
                    }
                    // 如果所有参数中均不包含可返回 URL 的 getter 方法，则抛出异常
                    if (attribMethod == null) {
                        throw new IllegalStateException("fail to create adaptive class for interface " + type.getName()
                                + ": not found url parameter or url attribute in parameters of method " + method.getName());
                    }

                    // Null point check
                    // 为可返回 URL 的参数生成判空代码，格式如下：
                    // if (arg + urlTypeIndex == null)
                    //     throw new IllegalArgumentException("参数全限定名 + argument == null");
                    String s = String.format("\nif (arg%d == null) throw new IllegalArgumentException(\"%s argument == null\");",
                            urlTypeIndex, pts[urlTypeIndex].getName());
                    code.append(s);

                    // 为 getter 方法返回的 URL 生成判空代码，格式如下：
                    // if (argN.getter方法名() == null)
                    //     throw new IllegalArgumentException(参数全限定名 + argument getUrl() == null);
                    s = String.format("\nif (arg%d.%s() == null) throw new IllegalArgumentException(\"%s argument %s() == null\");",
                            urlTypeIndex, attribMethod, pts[urlTypeIndex].getName(), attribMethod);
                    code.append(s);

                    // 生成赋值语句，格式如下：
                    // URL全限定名 url = argN.getter方法名()，比如
                    // com.alibaba.dubbo.common.URL url = invoker.getUrl();
                    s = String.format("%s url = arg%d.%s();", URL.class.getName(), urlTypeIndex, attribMethod);
                    code.append(s);
                }

                /**
                 * Adaptive 注解值 value 类型为 String[]，可填写多个值，默认情况下为空数组。
                 * 若 value 为非空数组，直接获取数组内容即可。若 value 为空数组，则需进行额外处理。
                 */

                String[] value = adaptiveAnnotation.value();
                // value is not set, use the value generated from class name as the key
                // value 为空数组
                if (value.length == 0) {
                    // 获取类名，并将类名转换为字符数组
                    String splitName = StringUtils.camelToSplitName(type.getSimpleName(), ".");
                    value = new String[]{splitName};
                }

                /**
                 * 检测方法列表中是否存在 Invocation 类型的参数，若存在，则为其生成判空代码和其他一些代码。
                 */

                // 判断是否有 Invocation 参数
                boolean hasInvocation = false;

                // 遍历参数类型列表
                for (int i = 0; i < pts.length; ++i) {
                    // 判断当前参数名称是否等于 com.alibaba.dubbo.rpc.Invocation
                    if (("org.apache.dubbo.rpc.Invocation").equals(pts[i].getName())) {
                        // Null Point check
                        // 为 Invocation 类型参数生成判空代码
                        String s = String.format("\nif (arg%d == null) throw new IllegalArgumentException(\"invocation == null\");", i);
                        code.append(s);
                        // 生成 getMethodName 方法调用代码，格式为：
                        //    String methodName = argN.getMethodName();
                        s = String.format("\nString methodName = arg%d.getMethodName();", i);
                        code.append(s);
                        // 设置 hasInvocation 为 true
                        hasInvocation = true;
                        break;
                    }
                }

                // 设置默认拓展名，cachedDefaultName 源于 SPI 注解值，默认情况下，
                // SPI 注解值为空串，此时 cachedDefaultName = null
                String defaultExtName = cachedDefaultName;
                // 获得最终拓展名的代码字符串，例如：
                // 【简单】1. url.getParameter("proxy", "javassist")
                // 【复杂】2. url.getParameter(key1, url.getParameter(key2, defaultExtName))

                // 遍历 value，这里的 value 是 Adaptive 的注解值，
                // 此处循环目的是生成从 URL 中获取拓展名的代码，生成的代码会赋值给 getNameCode 变量。注意这
                // 个循环的遍历顺序是由后向前遍历的。
                String getNameCode = null;
                for (int i = value.length - 1; i >= 0; --i) { // 倒序的原因，因为是顺序获取参数，参见【复杂】2. 的例子
                    // 当 i 为最后一个元素的坐标时
                    if (i == value.length - 1) {
                        // 默认拓展名非空
                        if (null != defaultExtName) {
                            // protocol 是 url 的一部分，可通过 getProtocol 方法获取，其他的则是从
                            // URL 参数中获取。因为获取方式不同，所以这里要判断 value[i] 是否为 protocol
                            if (!"protocol".equals(value[i])) {
                                // hasInvocation 用于标识方法参数列表中是否有 Invocation 类型参数
                                if (hasInvocation) { // 当【有】 Invocation 参数时，使用 `URL#getMethodParameter()` 方法。

                                    // 生成的代码功能等价于下面的代码：
                                    //   url.getMethodParameter(methodName, value[i], defaultExtName)
                                    // 以 LoadBalance 接口的 select 方法为例，最终生成的代码如下：
                                    //   url.getMethodParameter(methodName, "loadbalance", "random")
                                    getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);

                                    // 生成的代码功能等价于下面的代码：
                                    //   url.getParameter(value[i], defaultExtName)
                                } else { // 当【非】 Invocation 参数时，使用 `URL#getParameter()` 方法。
                                    getNameCode = String.format("url.getParameter(\"%s\", \"%s\")", value[i], defaultExtName);
                                }
                                // 生成的代码功能等价于下面的代码：
                                //   ( url.getProtocol() == null ? defaultExtName : url.getProtocol() )
                            } else { // 当属性名是 "protocol" ，使用 `URL#getProtocl()` 方法获取。
                                getNameCode = String.format("( url.getProtocol() == null ? \"%s\" : url.getProtocol() )", defaultExtName);
                            }

                            // 默认拓展名为空
                        } else {
                            if (!"protocol".equals(value[i])) {
                                if (hasInvocation) {
                                    // 生成代码格式同上
                                    getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                                } else {
                                    // 生成的代码功能等价于下面的代码：
                                    //   url.getParameter(value[i])
                                    getNameCode = String.format("url.getParameter(\"%s\")", value[i]);
                                }
                            } else {
                                // 生成从 url 中获取协议的代码，比如 "dubbo"
                                getNameCode = "url.getProtocol()";
                            }
                        }
                    } else {
                        if (!"protocol".equals(value[i])) {
                            // 生成代码格式同上
                            if (hasInvocation) {
                                getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                            } else {

                                // 生成的代码功能等价于下面的代码：
                                //   url.getParameter(value[i], getNameCode)
                                // 以 Transporter 接口的 connect 方法为例，最终生成的代码如下：
                                //   url.getParameter("client", url.getParameter("transporter", "netty"))
                                getNameCode = String.format("url.getParameter(\"%s\", %s)", value[i], getNameCode);
                            }
                        } else {

                            // 生成的代码功能等价于下面的代码：
                            //   url.getProtocol() == null ? getNameCode : url.getProtocol()
                            // 以 Protocol 接口的 connect 方法为例，最终生成的代码如下：
                            //   url.getProtocol() == null ? "dubbo" : url.getProtocol()
                            getNameCode = String.format("url.getProtocol() == null ? (%s) : url.getProtocol()", getNameCode);
                        }
                    }
                }
                // 生成代码：获取参数的代码。例如：String extName = url.getParameter("proxy", "javassist");
                // 生成 extName 赋值代码
                code.append("\nString extName = ").append(getNameCode).append(";");
                // check extName == null?
                // 生成 extName 判空代码
                String s = String.format("\nif(extName == null) " +
                                "throw new IllegalStateException(\"Fail to get extension(%s) name from url(\" + url.toString() + \") use keys(%s)\");",
                        type.getName(), Arrays.toString(value));
                code.append(s);

                // 生成拓展获取代码，格式如下：
                // type全限定名 extension = (type全限定名)ExtensionLoader全限定名
                //     .getExtensionLoader(type全限定名.class).getExtension(extName);
                // Tips: 格式化字符串中的 %<s 表示使用前一个转换符所描述的参数，即 type 全限定名
                /**
                 * `com.alibaba.dubbo.rpc.ProxyFactory extension = (com.alibaba.dubbo.rpc.ProxyFactory) ExtensionLoader.
                 *           getExtensionLoader(com.alibaba.dubbo.rpc.ProxyFactory.class).getExtension(extName);` 。
                 */
                code.append(String.format("\n%s extension = null;\n try {\nextension = (%<s)%s.getExtensionLoader(%s.class).getExtension(extName);\n}catch(Exception e){\n",
                        type.getName(), ExtensionLoader.class.getSimpleName(), type.getName()));
                code.append(String.format("if (count.incrementAndGet() == 1) {\nlogger.warn(\"Failed to find extension named \" + extName + \" for type %s, will use default extension %s instead.\", e);\n}\n",
                        type.getName(), defaultExtName));
                code.append(String.format("extension = (%s)%s.getExtensionLoader(%s.class).getExtension(\"%s\");\n}",
                        type.getName(), ExtensionLoader.class.getSimpleName(), type.getName(), defaultExtName));

                // return statement
                // 如果方法返回值类型非 void，则生成 return 语句。
                if (!rt.equals(void.class)) {
                    code.append("\nreturn ");
                }

                // 生成目标方法调用逻辑，格式为：
                //     extension.方法名(arg0, arg2, ..., argN);
                s = String.format("extension.%s(", method.getName());
                code.append(s);
                for (int i = 0; i < pts.length; i++) {
                    if (i != 0) {
                        code.append(", ");
                    }
                    code.append("arg").append(i);
                }
                code.append(");");
            }

            // public + 返回值全限定名 + 方法名 + (
            codeBuilder.append("\npublic ").append(rt.getCanonicalName()).append(" ").append(method.getName()).append("(");
            // 添加参数列表代码
            for (int i = 0; i < pts.length; i++) {
                if (i > 0) {
                    codeBuilder.append(", ");
                }
                codeBuilder.append(pts[i].getCanonicalName());
                codeBuilder.append(" ");
                codeBuilder.append("arg").append(i);
            }
            codeBuilder.append(")");

            // 添加异常抛出代码
            if (ets.length > 0) {
                // 添加异常
                codeBuilder.append(" throws ");
                for (int i = 0; i < ets.length; i++) {
                    if (i > 0) {
                        codeBuilder.append(", ");
                    }
                    codeBuilder.append(ets[i].getCanonicalName());
                }
            }
            codeBuilder.append(" {");
            codeBuilder.append(code.toString());
            codeBuilder.append("\n}");
        }
        // 生成类末尾的 `}`
        codeBuilder.append("\n}");
        // 调试，打印生成的代码
        if (logger.isDebugEnabled()) {
            logger.debug(codeBuilder.toString());
        }
        return codeBuilder.toString();
    }

    @Override
    public String toString() {
        return this.getClass().getName() + "[" + type.getName() + "]";
    }

}
