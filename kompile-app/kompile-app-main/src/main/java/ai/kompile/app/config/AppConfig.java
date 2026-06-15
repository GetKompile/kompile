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

package ai.kompile.app.config;

import ai.kompile.app.MainApplication;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.MultipartConfigElement;
import org.apache.tomcat.util.compat.JreCompat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.core.env.Environment;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.RestTemplate;

import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;

@Configuration(proxyBeanMethods = false)
@EnableAsync(proxyTargetClass = true)
@ImportRuntimeHints({AppConfig.VarConfigRegister.class})
public class AppConfig {

    private static final Logger logger = LoggerFactory.getLogger(AppConfig.class);

    @Autowired
    private Environment environment;

    public static class VarConfigRegister implements RuntimeHintsRegistrar {

        @Override
        public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
            // Only register what's absolutely necessary to avoid hosted API conflicts

            // Register Field class for basic reflection
            hints.reflection().registerType(Field.class,
                    MemberCategory.DECLARED_FIELDS,
                    MemberCategory.INVOKE_DECLARED_METHODS);

            // Register FileSystem for the useCanonCaches field
            try {
                Class<?> fileSystemClass = Class.forName("java.io.FileSystem");
                hints.reflection().registerType(fileSystemClass,
                        MemberCategory.DECLARED_FIELDS);
            } catch (ClassNotFoundException e) {
                // Ignore if not available
            }

            hints.reflection().registerType(VarHandle.class);
            // Register Tomcat compatibility classes with minimal reflection
            hints.reflection().registerType(JreCompat.class,
                    MemberCategory.INTROSPECT_PUBLIC_METHODS,
                    MemberCategory.INTROSPECT_DECLARED_CONSTRUCTORS,
                    MemberCategory.PUBLIC_FIELDS,
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                    MemberCategory.INVOKE_DECLARED_METHODS);

            // Tomcat compat subclasses — use registerIfPresent since available classes
            // vary across Tomcat versions (e.g. Jre12Compat removed in 10.1.20+)
            for (String compatClass : new String[]{
                    "org.apache.tomcat.util.compat.Jre16Compat",
                    "org.apache.tomcat.util.compat.Jre19Compat",
                    "org.apache.tomcat.util.compat.Jre21Compat",
                    "org.apache.tomcat.util.compat.Jre22Compat"
            }) {
                registerIfPresent(hints, classLoader, compatClass,
                        MemberCategory.INTROSPECT_PUBLIC_METHODS,
                        MemberCategory.INTROSPECT_DECLARED_CONSTRUCTORS,
                        MemberCategory.PUBLIC_FIELDS,
                        MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                        MemberCategory.INVOKE_DECLARED_METHODS);
            }

            // GraalVM native image: register ND4J/JavaCPP JNI types
            registerIfPresent(hints, classLoader, "org.bytedeco.javacpp.Loader",
                    MemberCategory.DECLARED_FIELDS, MemberCategory.INVOKE_DECLARED_METHODS,
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
            registerIfPresent(hints, classLoader, "org.bytedeco.javacpp.Pointer",
                    MemberCategory.DECLARED_FIELDS, MemberCategory.INVOKE_DECLARED_METHODS,
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
            registerIfPresent(hints, classLoader, "org.nd4j.nativeblas.Nd4jCpu",
                    MemberCategory.DECLARED_FIELDS, MemberCategory.INVOKE_DECLARED_METHODS,
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
            registerIfPresent(hints, classLoader, "org.nd4j.nativeblas.Nd4jCpu$NativeOps",
                    MemberCategory.DECLARED_FIELDS, MemberCategory.INVOKE_DECLARED_METHODS,
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);

            // GraalVM native image: register Lucene codec types
            registerIfPresent(hints, classLoader, "org.apache.lucene.codecs.lucene99.Lucene99Codec",
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
            registerIfPresent(hints, classLoader, "org.apache.lucene.codecs.lucene99.Lucene99HnswVectorsFormat",
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
            registerIfPresent(hints, classLoader, "org.apache.lucene.analysis.standard.StandardAnalyzer",
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);

            // GraalVM native image: register Spring AI types
            registerIfPresent(hints, classLoader, "org.springframework.ai.document.Document",
                    MemberCategory.DECLARED_FIELDS, MemberCategory.INVOKE_DECLARED_METHODS,
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);

            // GraalVM native image: register all record classes used as @Tool parameters
            // These need reflection for getRecordComponents() in McpToolRegistry.buildInputSchema()
            registerToolRecordClasses(hints, classLoader);

            // Include native library resources
            hints.resources().registerPattern("org/bytedeco/**");
            hints.resources().registerPattern("org/nd4j/**");
            hints.resources().registerPattern("static/**");
            hints.resources().registerPattern("application*.properties");
        }

        private static void registerToolRecordClasses(RuntimeHints hints, ClassLoader classLoader) {
            // Packages containing tool classes with inner record parameter types
            String[] toolPackages = {
                    "ai.kompile.app.tools",
                    "ai.kompile.tool.rag",
                    "ai.kompile.tool.filesystem",
                    "ai.kompile.knowledgegraph.tool",
                    "ai.kompile.staging.tool"
            };
            int count = 0;
            for (String pkg : toolPackages) {
                try {
                    // Use ClassGraph for package scanning (already on classpath)
                    io.github.classgraph.ScanResult scanResult = new io.github.classgraph.ClassGraph()
                            .acceptPackages(pkg)
                            .enableClassInfo()
                            .scan();
                    for (io.github.classgraph.ClassInfo classInfo : scanResult.getAllClasses()) {
                        try {
                            Class<?> clazz = Class.forName(classInfo.getName(), false, classLoader);
                            if (clazz.isRecord()) {
                                hints.reflection().registerType(clazz,
                                        MemberCategory.DECLARED_FIELDS,
                                        MemberCategory.INVOKE_DECLARED_METHODS,
                                        MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
                                count++;
                            }
                        } catch (ClassNotFoundException e) {
                            // Skip
                        }
                    }
                    scanResult.close();
                } catch (Exception e) {
                    // ClassGraph scan failed — fall through
                }
            }
        }

        private static void registerIfPresent(RuntimeHints hints, ClassLoader classLoader,
                                               String className, MemberCategory... categories) {
            try {
                Class<?> clazz = Class.forName(className, false, classLoader);
                hints.reflection().registerType(clazz, categories);
            } catch (ClassNotFoundException e) {
                // Class not on classpath — skip registration
            }
        }
    }

    @Bean(name = "taskExecutor")
    public TaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // Increased pool size to support concurrent batch indexing
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("async-indexing-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(120);
        executor.initialize();
        logger.info("Configured async TaskExecutor for background indexing tasks (core={}, max={})",
                executor.getCorePoolSize(), executor.getMaxPoolSize());
        return executor;
    }

    @Bean
    public RestTemplate restTemplate() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(30_000);
        return new RestTemplate(factory);
    }

    @Bean
    public ai.kompile.project.KompileProjectStore kompileProjectStore() {
        return new ai.kompile.project.KompileProjectStore();
    }

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        // Serialize Instant/LocalDateTime as ISO-8601 strings instead of epoch timestamps
        // This ensures frontend JavaScript correctly parses dates
        objectMapper.configure(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        return objectMapper;
    }

    @Bean
    public MultipartConfigElement multipartConfigElement() {
        logger.info("Attempting to configure MultipartConfigElement bean.");
        MultipartConfigFactory factory = new MultipartConfigFactory();

        // Retrieve values from Environment (which includes system properties set in MainApplication)
        String maxFileSizeValue = environment.getProperty(MainApplication.MAX_FILE_SIZE_PROPERTY, MainApplication.DEFAULT_MAX_FILE_SIZE);
        String maxRequestSizeValue = environment.getProperty(MainApplication.MAX_REQUEST_SIZE_PROPERTY, MainApplication.DEFAULT_MAX_REQUEST_SIZE);

        logger.info("MultipartConfig: Using max-file-size from environment: {}", maxFileSizeValue);
        logger.info("MultipartConfig: Using max-request-size from environment: {}", maxRequestSizeValue);

        try {
            DataSize maxFileSize = DataSize.parse(maxFileSizeValue);
            DataSize maxRequestSize = DataSize.parse(maxRequestSizeValue);

            factory.setMaxFileSize(maxFileSize);
            factory.setMaxRequestSize(maxRequestSize);

            logger.info("Successfully parsed and set MaxFileSize to: {} bytes", maxFileSize.toBytes());
            logger.info("Successfully parsed and set MaxRequestSize to: {} bytes", maxRequestSize.toBytes());

            // Optional: Define a temporary directory for large file uploads.
            // Ensure this path is writable by the application.
            // String tempLocation = environment.getProperty("kompile.multipart.temp-location", "/tmp/kompile-uploads");
            // factory.setLocation(tempLocation);
            // logger.info("Multipart temporary upload location set to: {}", tempLocation);


        } catch (IllegalArgumentException e) {
            logger.error("MultipartConfig: Failed to parse DataSize values. Falling back to Spring Boot defaults. Error: {}", e.getMessage(), e);
            // Let Spring Boot handle defaults if parsing fails, or set hardcoded safe defaults.
            // For example, to explicitly set to Spring's typical defaults if parsing our custom ones fails:
            // factory.setMaxFileSize(DataSize.ofMegabytes(1));
            // factory.setMaxRequestSize(DataSize.ofMegabytes(10));
        }
        return factory.createMultipartConfig();
    }
}
