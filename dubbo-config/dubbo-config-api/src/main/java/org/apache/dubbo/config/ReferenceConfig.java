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
package org.apache.dubbo.config;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.bytecode.Wrapper;
import org.apache.dubbo.common.config.AsyncFor;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.config.annotation.Reference;
import org.apache.dubbo.config.support.Parameter;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.ProxyFactory;
import org.apache.dubbo.rpc.cluster.Cluster;
import org.apache.dubbo.rpc.cluster.directory.StaticDirectory;
import org.apache.dubbo.rpc.cluster.support.AvailableCluster;
import org.apache.dubbo.rpc.cluster.support.ClusterUtils;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.ConsumerModel;
import org.apache.dubbo.rpc.protocol.injvm.InjvmProtocol;
import org.apache.dubbo.rpc.service.GenericService;
import org.apache.dubbo.rpc.support.ProtocolUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.apache.dubbo.common.utils.NetUtils.isInvalidLocalHost;

/**
 * ReferenceConfig
 *
 * @export
 */
public class ReferenceConfig<T> extends AbstractReferenceConfig {

    private static final long serialVersionUID = -5864351140409987595L;

    private static final Protocol refprotocol = ExtensionLoader.getExtensionLoader(Protocol.class).getAdaptiveExtension();

    private static final Cluster cluster = ExtensionLoader.getExtensionLoader(Cluster.class).getAdaptiveExtension();

    private static final ProxyFactory proxyFactory = ExtensionLoader.getExtensionLoader(ProxyFactory.class).getAdaptiveExtension();
    private final List<URL> urls = new ArrayList<URL>();
    // interface name
    private String interfaceName;
    private Class<?> interfaceClass;
    private Class<?> asyncInterfaceClass;
    // client type
    private String client;
    // url for peer-to-peer invocation
    private String url;
    // method configs
    private List<MethodConfig> methods;
    // default config
    private ConsumerConfig consumer;
    private String protocol;
    // interface proxy reference
    private transient volatile T ref;
    private transient volatile Invoker<?> invoker;
    private transient volatile boolean initialized;
    private transient volatile boolean destroyed;
    @SuppressWarnings("unused")
    private final Object finalizerGuardian = new Object() {
        @Override
        protected void finalize() throws Throwable {
            super.finalize();

            if (!ReferenceConfig.this.destroyed) {
                logger.warn("ReferenceConfig(" + url + ") is not DESTROYED when FINALIZE");

                /* don't destroy for now
                try {
                    ReferenceConfig.this.destroy();
                } catch (Throwable t) {
                        logger.warn("Unexpected err when destroy invoker of ReferenceConfig(" + url + ") in finalize method!", t);
                }
                */
            }
        }
    };

    public ReferenceConfig() {
    }

    public ReferenceConfig(Reference reference) {
        appendAnnotation(Reference.class, reference);
    }

    public URL toUrl() {
        return urls.isEmpty() ? null : urls.iterator().next();
    }

    public List<URL> toUrls() {
        return urls;
    }

    public synchronized T get() {
        if (destroyed) {
            throw new IllegalStateException("Already destroyed!");
        }
        // 检测 ref 是否为空，为空则通过 init 方法创建
        if (ref == null) {
            // init 方法主要用于处理配置，以及调用 createProxy 生成代理类
            init();
        }
        return ref;
    }

    public synchronized void destroy() {
        if (ref == null) {
            return;
        }
        if (destroyed) {
            return;
        }
        destroyed = true;
        try {
            invoker.destroy();
        } catch (Throwable t) {
            logger.warn("Unexpected err when destroy invoker of ReferenceConfig(" + url + ").", t);
        }
        invoker = null;
        ref = null;
    }

    /**
     * 配置解析逻辑
     * <p>
     * 首先是方法开始到分割线1之间的代码。这段代码主要用于检测 ConsumerConfig 实例是否存在，如不存在则创建一个新的实例，然后通过系统变量或 dubbo.properties 配置文件填充 ConsumerConfig 的字段。接着是检测泛化配置，并根据配置设置 interfaceClass 的值。
     * 接着来看分割线1到分割线2之间的逻辑。这段逻辑用于从系统属性或配置文件中加载与接口名相对应的配置，并将解析结果赋值给 url 字段。url 字段的作用一般是用于点对点调用。
     * 继续向下看，分割线2和分割线3之间的代码用于检测几个核心配置类是否为空，为空则尝试从其他配置类中获取。分割线3与分割线4之间的代码主要用于收集各种配置，并将配置存储到 map 中。
     * 分割线4和分割线5之间的代码用于处理 MethodConfig 实例。该实例包含了事件通知配置，比如 onreturn、onthrow、oninvoke 等。
     * 分割线5到方法结尾的代码主要用于解析服务消费者 ip，以及调用 createProxy 创建代理对象。
     */
    private void init() {
        // 避免重复初始化
        if (initialized) {
            return;
        }
        initialized = true;
        // 检测接口名合法性
        if (interfaceName == null || interfaceName.length() == 0) {
            throw new IllegalStateException("<dubbo:reference interface=\"\" /> interface not allow null!");
        }
        // get consumer's global configuration
        // 检测 consumer 变量是否为空，为空则创建
        checkDefault();
        appendProperties(this);

        // 设置 generic
        if (getGeneric() == null && getConsumer() != null) {
            setGeneric(getConsumer().getGeneric());
        }

        // 检测是否为泛化接口
        if (ProtocolUtils.isGeneric(getGeneric())) {
            interfaceClass = GenericService.class;
        } else {
            try {
                // 加载类
                interfaceClass = Class.forName(interfaceName, true, Thread.currentThread()
                        .getContextClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            checkInterfaceAndMethods(interfaceClass, methods);
        }

        // -------------------------------✨ 分割线1 ✨------------------------------

        // 从系统变量中获取与接口名对应的属性值
        String resolve = System.getProperty(interfaceName);
        String resolveFile = null;
        if (resolve == null || resolve.length() == 0) {
            // 从系统属性中获取解析文件路径
            resolveFile = System.getProperty("dubbo.resolve.file");
            if (resolveFile == null || resolveFile.length() == 0) {
                // 从指定位置加载配置文件
                File userResolveFile = new File(new File(System.getProperty("user.home")), "dubbo-resolve.properties");
                if (userResolveFile.exists()) {
                    // 获取文件绝对路径
                    resolveFile = userResolveFile.getAbsolutePath();
                }
            }
            if (resolveFile != null && resolveFile.length() > 0) {
                Properties properties = new Properties();
                FileInputStream fis = null;
                try {
                    fis = new FileInputStream(new File(resolveFile));
                    // 从文件中加载配置
                    properties.load(fis);
                } catch (IOException e) {
                    throw new IllegalStateException("Unload " + resolveFile + ", cause: " + e.getMessage(), e);
                } finally {
                    try {
                        if (null != fis) {
                            fis.close();
                        }
                    } catch (IOException e) {
                        logger.warn(e.getMessage(), e);
                    }
                }
                // 获取与接口名对应的配置
                resolve = properties.getProperty(interfaceName);
            }
        }
        if (resolve != null && resolve.length() > 0) {
            // 将 resolve 赋值给 url
            url = resolve;
            if (logger.isWarnEnabled()) {
                if (resolveFile != null) {
                    logger.warn("Using default dubbo resolve file " + resolveFile + " replace " + interfaceName + "" + resolve + " to p2p invoke remote service.");
                } else {
                    logger.warn("Using -D" + interfaceName + "=" + resolve + " to p2p invoke remote service.");
                }
            }
        }

        // -------------------------------✨ 分割线2 ✨------------------------------
        if (consumer != null) {
            // 从 consumer 中获取 Application 实例，下同
            if (application == null) {
                application = consumer.getApplication();
            }
            if (module == null) {
                module = consumer.getModule();
            }
            if (registries == null) {
                registries = consumer.getRegistries();
            }
            if (monitor == null) {
                monitor = consumer.getMonitor();
            }
        }
        if (module != null) {
            if (registries == null) {
                registries = module.getRegistries();
            }
            if (monitor == null) {
                monitor = module.getMonitor();
            }
        }
        if (application != null) {
            if (registries == null) {
                registries = application.getRegistries();
            }
            if (monitor == null) {
                monitor = application.getMonitor();
            }
        }
        // 检测 Application 合法性
        checkApplication();
        // 检测本地存根配置合法性
        checkStub(interfaceClass);
        checkMock(interfaceClass);

        // -------------------------------✨ 分割线3 ✨------------------------------
        Map<String, String> map = new HashMap<String, String>();
        resolveAsyncInterface(interfaceClass, map);

        // 添加 side、协议版本信息、时间戳和进程号等信息到 map 中
        map.put(Constants.SIDE_KEY, Constants.CONSUMER_SIDE);
        map.put(Constants.DUBBO_VERSION_KEY, Version.getProtocolVersion());
        map.put(Constants.TIMESTAMP_KEY, String.valueOf(System.currentTimeMillis()));
        if (ConfigUtils.getPid() > 0) {
            map.put(Constants.PID_KEY, String.valueOf(ConfigUtils.getPid()));
        }

        // 非泛化服务
        if (!isGeneric()) {

            // 获取版本
            String revision = Version.getVersion(interfaceClass, version);
            if (revision != null && revision.length() > 0) {
                map.put("revision", revision);
            }

            // 获取接口方法列表，并添加到 map 中
            String[] methods = Wrapper.getWrapper(interfaceClass).getMethodNames();
            if (methods.length == 0) {
                logger.warn("NO method found in service interface " + interfaceClass.getName());
                map.put("methods", Constants.ANY_VALUE);
            } else {
                map.put("methods", StringUtils.join(new HashSet<String>(Arrays.asList(methods)), ","));
            }
        }
        map.put(Constants.INTERFACE_KEY, interfaceName);

        // 将 ApplicationConfig、ConsumerConfig、ReferenceConfig 等对象的字段信息添加到 map 中
        appendParameters(map, application);
        appendParameters(map, module);
        appendParameters(map, consumer, Constants.DEFAULT_KEY);
        appendParameters(map, this);


        // -------------------------------✨ 分割线4 ✨------------------------------
        Map<String, Object> attributes = null;
        if (methods != null && !methods.isEmpty()) {
            attributes = new HashMap<String, Object>();
            // 遍历 MethodConfig 列表
            for (MethodConfig methodConfig : methods) {
                appendParameters(map, methodConfig, methodConfig.getName());
                String retryKey = methodConfig.getName() + ".retry";
                // 检测 map 是否包含 methodName.retry
                if (map.containsKey(retryKey)) {
                    String retryValue = map.remove(retryKey);
                    if ("false".equals(retryValue)) {
                        // 添加重试次数配置 methodName.retries
                        map.put(methodConfig.getName() + ".retries", "0");
                    }
                }
                // 添加 MethodConfig 中的“属性”字段到 attributes
                // 比如 onreturn、onthrow、oninvoke 等
                attributes.put(methodConfig.getName(), convertMethodConfig2AyncInfo(methodConfig));
            }
        }

        // -------------------------------✨ 分割线5 ✨------------------------------

        // 获取服务消费者 ip 地址
        String hostToRegistry = ConfigUtils.getSystemProperty(Constants.DUBBO_IP_TO_REGISTRY);
        if (hostToRegistry == null || hostToRegistry.length() == 0) {
            hostToRegistry = NetUtils.getLocalHost();
        } else if (isInvalidLocalHost(hostToRegistry)) {
            throw new IllegalArgumentException("Specified invalid registry ip from property:" + Constants.DUBBO_IP_TO_REGISTRY + ", value:" + hostToRegistry);
        }
        map.put(Constants.REGISTER_IP_KEY, hostToRegistry);

        // 创建代理类
        ref = createProxy(map);

        // 根据服务名，ReferenceConfig，代理类构建 ConsumerModel，
        // 并将 ConsumerModel 存入到 ApplicationModel 中
        ConsumerModel consumerModel = new ConsumerModel(getUniqueServiceName(), ref, interfaceClass.getMethods(), attributes);
        ApplicationModel.initConsumerModel(getUniqueServiceName(), consumerModel);
    }

    /**
     * createProxy 似乎只是用于创建代理对象的。但实际上并非如此，该方法还会调用其他方法构建以及合并 Invoker 实例。
     * <p>
     * 首先根据配置检查是否为本地调用，若是，则调用 InjvmProtocol 的 refer 方法生成 InjvmInvoker 实例。
     * 若不是，则读取直联配置项，或注册中心 url，并将读取到的 url 存储到 urls 中。然后根据 urls 元素数量进行后续操作。
     * 若 urls 元素数量为1，则直接通过 Protocol 自适应拓展类构建 Invoker 实例接口。若 urls 元素数量大于1，即存在多个注册中心或服务直联 url，此时先根据 url 构建 Invoker。
     * 然后再通过 Cluster 合并多个 Invoker，最后调用 ProxyFactory 生成代理类。Invoker 的构建过程以及代理类的过程比较重要，
     *
     * @param map
     * @return
     */
    @SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
    private T createProxy(Map<String, String> map) {
        URL tmpUrl = new URL("temp", "localhost", 0, map);
        final boolean isJvmRefer;
        if (isInjvm() == null) {
            // url 配置被指定，则不做本地引用
            if (url != null && url.length() > 0) { // if a url is specified, don't do local reference
                isJvmRefer = false;
            } else {
                // 根据 url 的协议、scope 以及 injvm 等参数检测是否需要本地引用
                // 比如如果用户显式配置了 scope=local，此时 isInjvmRefer 返回 true
                // by default, reference local service if there is
                isJvmRefer = InjvmProtocol.getInjvmProtocol().isInjvmRefer(tmpUrl);
            }
        } else {
            // 获取 injvm 配置值
            isJvmRefer = isInjvm();
        }

        // 本地引用
        if (isJvmRefer) {
            // 生成本地引用 URL，协议为 injvm
            URL url = new URL(Constants.LOCAL_PROTOCOL, NetUtils.LOCALHOST, 0, interfaceClass.getName()).addParameters(map);
            // 调用 refer 方法构建 InjvmInvoker 实例
            invoker = refprotocol.refer(interfaceClass, url);
            if (logger.isInfoEnabled()) {
                logger.info("Using injvm service " + interfaceClass.getName());
            }

            // 远程引用
        } else {
            // url 不为空，表明用户可能想进行点对点调用
            if (url != null && url.length() > 0) { // user specified URL, could be peer-to-peer address, or register center's address.
                // 当需要配置多个 url 时，可用分号进行分割，这里会进行切分
                String[] us = Constants.SEMICOLON_SPLIT_PATTERN.split(url);
                if (us != null && us.length > 0) {
                    for (String u : us) {
                        URL url = URL.valueOf(u);
                        if (url.getPath() == null || url.getPath().length() == 0) {
                            // 设置接口全限定名为 url 路径
                            url = url.setPath(interfaceName);
                        }
                        // 检测 url 协议是否为 registry，若是，表明用户想使用指定的注册中心
                        if (Constants.REGISTRY_PROTOCOL.equals(url.getProtocol())) {
                            // 将 map 转换为查询字符串，并作为 refer 参数的值添加到 url 中
                            urls.add(url.addParameterAndEncoded(Constants.REFER_KEY, StringUtils.toQueryString(map)));
                        } else {
                            // 合并 url，移除服务提供者的一些配置（这些配置来源于用户配置的 url 属性），
                            // 比如线程池相关配置。并保留服务提供者的部分配置，比如版本，group，时间戳等
                            // 最后将合并后的配置设置为 url 查询字符串中。
                            urls.add(ClusterUtils.mergeUrl(url, map));
                        }
                    }
                }

                // 加载注册中心 url
            } else { // assemble URL from register center's configuration
                List<URL> us = loadRegistries(false);
                if (us != null && !us.isEmpty()) {
                    for (URL u : us) {
                        URL monitorUrl = loadMonitor(u);
                        if (monitorUrl != null) {
                            map.put(Constants.MONITOR_KEY, URL.encode(monitorUrl.toFullString()));
                        }
                        // 添加 refer 参数到 url 中，并将 url 添加到 urls 中
                        urls.add(u.addParameterAndEncoded(Constants.REFER_KEY, StringUtils.toQueryString(map)));
                    }
                }
                // 未配置注册中心，抛出异常
                if (urls.isEmpty()) {
                    throw new IllegalStateException("No such any registry to reference " + interfaceName + " on the consumer " + NetUtils.getLocalHost() + " use dubbo version " + Version.getVersion() + ", please config <dubbo:registry address=\"...\" /> to your spring config.");
                }
            }

            // 单个注册中心或服务提供者(服务直联，下同)
            if (urls.size() == 1) {
                // 调用 RegistryProtocol 的 refer 构建 Invoker 实例
                invoker = refprotocol.refer(interfaceClass, urls.get(0));

                // 多个注册中心或多个服务提供者，或者两者混合
            } else {
                List<Invoker<?>> invokers = new ArrayList<Invoker<?>>();
                URL registryURL = null;

                // 获取所有的 Invoker
                for (URL url : urls) {

                    // 通过 refprotocol 调用 refer 构建 Invoker，refprotocol 会在运行时
                    // 根据 url 协议头加载指定的 Protocol 实例，并调用实例的 refer 方法
                    invokers.add(refprotocol.refer(interfaceClass, url));
                    if (Constants.REGISTRY_PROTOCOL.equals(url.getProtocol())) {
                        registryURL = url; // use last registry url
                    }
                }
                if (registryURL != null) { // registry url is available
                    // use AvailableCluster only when register's cluster is available
                    // 如果注册中心链接不为空，则将使用 AvailableCluster
                    URL u = registryURL.addParameter(Constants.CLUSTER_KEY, AvailableCluster.NAME);
                    // 创建 StaticDirectory 实例，并由 Cluster 对多个 Invoker 进行合并
                    invoker = cluster.join(new StaticDirectory(u, invokers));
                } else { // not a registry url
                    invoker = cluster.join(new StaticDirectory(invokers));
                }
            }
        }

        Boolean c = check;
        if (c == null && consumer != null) {
            c = consumer.isCheck();
        }
        if (c == null) {
            c = true; // default true
        }

        // invoker 可用性检查
        if (c && !invoker.isAvailable()) {
            // make it possible for consumer to retry later if provider is temporarily unavailable
            initialized = false;
            throw new IllegalStateException("Failed to check the status of the service " + interfaceName + ". No provider available for the service " + (group == null ? "" : group + "/") + interfaceName + (version == null ? "" : ":" + version) + " from the url " + invoker.getUrl() + " to the consumer " + NetUtils.getLocalHost() + " use dubbo version " + Version.getVersion());
        }
        if (logger.isInfoEnabled()) {
            logger.info("Refer dubbo service " + interfaceClass.getName() + " from url " + invoker.getUrl());
        }

        // 生成代理类
        // create service proxy
        return (T) proxyFactory.getProxy(invoker);
    }

    private void checkDefault() {
        if (consumer == null) {
            consumer = new ConsumerConfig();
        }
        appendProperties(consumer);
    }

    private void resolveAsyncInterface(Class<?> interfaceClass, Map<String, String> map) {
        AsyncFor annotation = interfaceClass.getAnnotation(AsyncFor.class);
        if (annotation == null) {
            return;
        }
        Class<?> target = annotation.value();
        if (!target.isAssignableFrom(interfaceClass)) {
            return;
        }
        this.asyncInterfaceClass = interfaceClass;
        this.interfaceClass = target;
        setInterface(this.interfaceClass.getName());
        map.put(Constants.INTERFACES, interfaceClass.getName());
    }


    public Class<?> getInterfaceClass() {
        if (interfaceClass != null) {
            return interfaceClass;
        }
        if (isGeneric()
                || (getConsumer() != null && getConsumer().isGeneric())) {
            return GenericService.class;
        }
        try {
            if (interfaceName != null && interfaceName.length() > 0) {
                this.interfaceClass = Class.forName(interfaceName, true, Thread.currentThread()
                        .getContextClassLoader());
            }
        } catch (ClassNotFoundException t) {
            throw new IllegalStateException(t.getMessage(), t);
        }
        return interfaceClass;
    }

    /**
     * @param interfaceClass
     * @see #setInterface(Class)
     * @deprecated
     */
    @Deprecated
    public void setInterfaceClass(Class<?> interfaceClass) {
        setInterface(interfaceClass);
    }

    public String getInterface() {
        return interfaceName;
    }

    public void setInterface(Class<?> interfaceClass) {
        if (interfaceClass != null && !interfaceClass.isInterface()) {
            throw new IllegalStateException("The interface class " + interfaceClass + " is not a interface!");
        }
        this.interfaceClass = interfaceClass;
        setInterface(interfaceClass == null ? null : interfaceClass.getName());
    }

    public void setInterface(String interfaceName) {
        this.interfaceName = interfaceName;
        if (id == null || id.length() == 0) {
            id = interfaceName;
        }
    }

    public String getClient() {
        return client;
    }

    public void setClient(String client) {
        checkName("client", client);
        this.client = client;
    }

    @Parameter(excluded = true)
    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public List<MethodConfig> getMethods() {
        return methods;
    }

    @SuppressWarnings("unchecked")
    public void setMethods(List<? extends MethodConfig> methods) {
        this.methods = (List<MethodConfig>) methods;
    }

    public ConsumerConfig getConsumer() {
        return consumer;
    }

    public void setConsumer(ConsumerConfig consumer) {
        this.consumer = consumer;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    // just for test
    Invoker<?> getInvoker() {
        return invoker;
    }

    @Parameter(excluded = true)
    public String getUniqueServiceName() {
        StringBuilder buf = new StringBuilder();
        if (group != null && group.length() > 0) {
            buf.append(group).append("/");
        }
        buf.append(interfaceName);
        if (version != null && version.length() > 0) {
            buf.append(":").append(version);
        }
        return buf.toString();
    }

}
