/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.metrics.interceptor;

import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.core.embeddings.ScoredDocument;
import ai.kompile.core.reranking.RerankerConfig;
import ai.kompile.metrics.binder.VectorStoreMetrics;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

/**
 * BeanPostProcessor that wraps {@link VectorStore} beans with a metrics-recording proxy.
 * This provides automatic instrumentation without modifying the original VectorStore implementations.
 */
@Component
@ConditionalOnBean(VectorStoreMetrics.class)
public class MetricsInterceptorPostProcessor implements BeanPostProcessor, ApplicationContextAware {

    private static final Logger log = LoggerFactory.getLogger(MetricsInterceptorPostProcessor.class);

    private ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof VectorStore && !(bean instanceof java.lang.reflect.Proxy)) {
            try {
                VectorStoreMetrics metrics = applicationContext.getBean(VectorStoreMetrics.class);
                VectorStore original = (VectorStore) bean;

                Object proxy = Proxy.newProxyInstance(
                        bean.getClass().getClassLoader(),
                        getAllInterfaces(bean.getClass()),
                        new VectorStoreMetricsHandler(original, metrics));

                log.info("Wrapped VectorStore bean '{}' with metrics proxy", beanName);
                return proxy;
            } catch (Exception e) {
                log.debug("Could not wrap VectorStore '{}' with metrics: {}", beanName, e.getMessage());
            }
        }
        return bean;
    }

    private static Class<?>[] getAllInterfaces(Class<?> clazz) {
        java.util.Set<Class<?>> interfaces = new java.util.LinkedHashSet<>();
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            for (Class<?> iface : c.getInterfaces()) {
                interfaces.add(iface);
            }
        }
        return interfaces.toArray(new Class<?>[0]);
    }

    private static class VectorStoreMetricsHandler implements InvocationHandler {
        private final VectorStore delegate;
        private final VectorStoreMetrics metrics;

        VectorStoreMetricsHandler(VectorStore delegate, VectorStoreMetrics metrics) {
            this.delegate = delegate;
            this.metrics = metrics;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();

            if (name.equals("similaritySearch") || name.equals("similaritySearchWithScores")
                    || name.equals("similaritySearchWithReranking")) {
                long start = System.currentTimeMillis();
                Object result = method.invoke(delegate, args);
                long duration = System.currentTimeMillis() - start;

                int resultCount = 0;
                if (result instanceof List) {
                    resultCount = ((List<?>) result).size();
                }
                metrics.recordSearch(duration, resultCount);
                return result;
            }

            if (name.equals("add") || name.equals("addWithEmbeddings") || name.equals("addWithFloatArrayEmbeddings")) {
                long start = System.currentTimeMillis();
                Object result = method.invoke(delegate, args);
                long duration = System.currentTimeMillis() - start;

                int docCount = 0;
                if (result instanceof Integer) {
                    docCount = (Integer) result;
                } else if (args != null && args.length > 0 && args[0] instanceof List) {
                    docCount = ((List<?>) args[0]).size();
                }
                metrics.recordAdd(duration, docCount);
                return result;
            }

            if (name.equals("delete")) {
                Object result = method.invoke(delegate, args);
                if (args != null && args.length > 0 && args[0] instanceof List) {
                    metrics.recordDelete(((List<?>) args[0]).size());
                }
                return result;
            }

            return method.invoke(delegate, args);
        }
    }
}
