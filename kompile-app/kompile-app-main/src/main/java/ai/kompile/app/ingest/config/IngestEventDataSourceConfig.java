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

package ai.kompile.app.ingest.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;
import java.util.UUID;

/**
 * Configuration for ingest event log data source.
 * Uses the same H2 database as chat history by default.
 */
@Slf4j
@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(
    basePackages = "ai.kompile.app.ingest.repository",
    entityManagerFactoryRef = "ingestEventEntityManagerFactory",
    transactionManagerRef = "ingestEventTransactionManager"
)
@ConditionalOnProperty(name = "kompile.ingest.eventlog.enabled", havingValue = "true", matchIfMissing = true)
public class IngestEventDataSourceConfig {

    @Value("${kompile.ingest.eventlog.jdbc-url:}")
    private String jdbcUrl;

    @Value("${kompile.ingest.eventlog.jdbc-username:sa}")
    private String jdbcUsername;

    @Value("${kompile.ingest.eventlog.jdbc-password:}")
    private String jdbcPassword;

    @Value("${kompile.ingest.eventlog.database-path:${user.home}/.kompile/data/ingest-events}")
    private String databasePath;

    /**
     * Creates a DataSource for ingest events.
     * Uses custom JDBC URL if specified, otherwise embedded H2.
     */
    @Bean(name = "ingestEventDataSource")
    public DataSource ingestEventDataSource() {
        if (jdbcUrl != null && !jdbcUrl.isEmpty()) {
            return createCustomJdbcDataSource();
        } else {
            return createH2DataSource();
        }
    }

    private DataSource createCustomJdbcDataSource() {
        log.info("Configuring ingest events with custom JDBC: {}", jdbcUrl);
        DataSourceBuilder<?> builder = DataSourceBuilder.create();
        builder.url(jdbcUrl);
        builder.username(jdbcUsername);
        builder.password(jdbcPassword);
        return builder.build();
    }

    private DataSource createH2DataSource() {
        String h2FileUrl = String.format("jdbc:h2:file:%s;DB_CLOSE_DELAY=-1;AUTO_SERVER=TRUE", databasePath);
        log.info("Configuring ingest events with embedded H2: {}", h2FileUrl);

        DataSource fileDataSource = buildH2DataSource(h2FileUrl);

        try (Connection conn = fileDataSource.getConnection()) {
            log.info("Successfully connected to ingest events H2 database");
            return fileDataSource;
        } catch (SQLException e) {
            if (isDatabaseLockError(e)) {
                log.warn("Ingest events H2 database is locked. Falling back to in-memory database.");
                return createInMemoryH2DataSource();
            }
            log.warn("Warning accessing ingest events H2 database: {}. Proceeding anyway.", e.getMessage());
            return fileDataSource;
        }
    }

    private DataSource createInMemoryH2DataSource() {
        String uniqueId = UUID.randomUUID().toString().substring(0, 8);
        String h2MemUrl = String.format("jdbc:h2:mem:ingest-events-%s;DB_CLOSE_DELAY=-1", uniqueId);
        log.info("Using in-memory H2 database for ingest events: {}", h2MemUrl);
        return buildH2DataSource(h2MemUrl);
    }

    private DataSource buildH2DataSource(String url) {
        DataSourceBuilder<?> builder = DataSourceBuilder.create();
        builder.url(url);
        builder.driverClassName("org.h2.Driver");
        builder.username("sa");
        builder.password("");
        return builder.build();
    }

    private boolean isDatabaseLockError(SQLException e) {
        if (e.getErrorCode() == 90020) {
            return true;
        }
        String message = e.getMessage();
        if (message != null) {
            String lowerMessage = message.toLowerCase();
            return lowerMessage.contains("already in use") ||
                   lowerMessage.contains("locked") ||
                   lowerMessage.contains("database may be already open");
        }
        return false;
    }

    /**
     * Entity manager factory for ingest event entities.
     */
    @Bean(name = "ingestEventEntityManagerFactory")
    public LocalContainerEntityManagerFactoryBean ingestEventEntityManagerFactory() {
        LocalContainerEntityManagerFactoryBean em = new LocalContainerEntityManagerFactoryBean();
        em.setDataSource(ingestEventDataSource());
        em.setPackagesToScan("ai.kompile.app.ingest.domain");

        HibernateJpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
        em.setJpaVendorAdapter(vendorAdapter);
        em.setJpaProperties(hibernateProperties());

        return em;
    }

    /**
     * Transaction manager for ingest events.
     */
    @Bean(name = "ingestEventTransactionManager")
    public PlatformTransactionManager ingestEventTransactionManager() {
        JpaTransactionManager transactionManager = new JpaTransactionManager();
        transactionManager.setEntityManagerFactory(ingestEventEntityManagerFactory().getObject());
        return transactionManager;
    }

    private Properties hibernateProperties() {
        Properties properties = new Properties();
        properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
        properties.put("hibernate.hbm2ddl.auto", "update");
        properties.put("hibernate.show_sql", false);
        properties.put("hibernate.format_sql", true);
        properties.put("hibernate.use_sql_comments", false);
        properties.put("hibernate.jdbc.batch_size", 50);
        properties.put("hibernate.order_inserts", true);
        properties.put("hibernate.order_updates", true);
        return properties;
    }
}
