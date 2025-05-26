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

import ai.kompile.app.MainApplication; // Import to access constants
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.MultipartConfigElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.RestTemplate;

@Configuration(proxyBeanMethods = false)
public class AppConfig {

    private static final Logger logger = LoggerFactory.getLogger(AppConfig.class);

    @Autowired
    private Environment environment;

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
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