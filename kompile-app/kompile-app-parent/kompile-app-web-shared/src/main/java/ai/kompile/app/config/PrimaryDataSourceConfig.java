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

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.domain.EntityScanPackages;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypesScanner;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.Properties;

/**
 * Primary data source configuration for the application.
 * All domain entities use this single database.
 *
 * <p>Lives in kompile-app-web-shared so that all three persona processes — the admin console,
 * kompile-app-chat and kompile-app-crawl-manager — get the same repositories, entities and
 * transaction manager. It cannot be left to Boot's JPA auto-configuration: {@code @EnableAutoConfiguration}
 * derives its scan root from the package of the {@code @SpringBootApplication} class (NOT from
 * {@code scanBasePackages}), so each app would see only its own package and every repository below
 * would go missing. It used to live in app-main, where the chat app's first boot died on
 * {@code NoSuchBeanDefinitionException: ai.kompile.codeindexer.domain.CodeProjectRepository}.
 *
 * <p>The package lists are deliberately a superset of any one persona: a package with no classes on
 * the current classpath simply contributes nothing, so this stays a single shared list rather than
 * three drifting copies.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement
@EnableJpaRepositories(
    basePackages = {
        "ai.kompile.app.facts.repository",
        "ai.kompile.app.ingest.repository",
        "ai.kompile.app.staging.repository",
        "ai.kompile.app.eval.repository",
        "ai.kompile.app.prompts.repository",
        "ai.kompile.app.monitor.repository",
        "ai.kompile.chat.history.repository",
        "ai.kompile.knowledgegraph.repository",
        "ai.kompile.knowledgegraph.builder.repository",
        "ai.kompile.knowledgegraph.embedding.repository",
        "ai.kompile.knowledgegraph.persistence.dual",
        "ai.kompile.oauth.repository",
        "ai.kompile.orchestrator.repository",
        "ai.kompile.enrichment.repository",
        "ai.kompile.app.diagram.repository",
        "ai.kompile.app.sync.repository",
        "ai.kompile.staging.repository",
        "ai.kompile.testmilestone.repository",
        "ai.kompile.codeindexer.domain",
        "ai.kompile.event.observation.repository",
        "ai.kompile.graphchangetracking.repository"
    },
    entityManagerFactoryRef = "entityManagerFactory",
    transactionManagerRef = "transactionManager"
)
@EntityScan(basePackages = {
    "ai.kompile.app.facts.domain",
    "ai.kompile.app.ingest.domain",
    "ai.kompile.app.staging.domain",
    "ai.kompile.app.eval.domain",
    "ai.kompile.app.prompts.domain",
    "ai.kompile.app.monitor.domain",
    "ai.kompile.chat.history.domain",
    "ai.kompile.knowledgegraph.domain",
    "ai.kompile.knowledgegraph.builder.domain",
    "ai.kompile.knowledgegraph.embedding.domain",
    "ai.kompile.knowledgegraph.persistence.dual",
    "ai.kompile.oauth.domain",
    "ai.kompile.orchestrator.model",
    "ai.kompile.enrichment.domain",
    "ai.kompile.app.diagram.domain",
    "ai.kompile.app.sync.domain",
    "ai.kompile.staging.domain",
    "ai.kompile.testmilestone.domain",
    "ai.kompile.codeindexer.domain",
    "ai.kompile.event.observation.domain",
    "ai.kompile.graphchangetracking.domain"
})
public class PrimaryDataSourceConfig {

    /** Constant for the transaction manager bean name shared by all ingest-related services. */
    public static final String INGEST_EVENT_TRANSACTION_MANAGER =
            ai.kompile.app.ingest.service.IngestTransactionManagers.INGEST_EVENT_TRANSACTION_MANAGER;

    // DB_CLOSE_DELAY=-1: keep the file-backed H2 database open for the JVM lifetime.
    // Without it, H2 closes the ENTIRE database when HikariCP releases its last physical
    // connection (e.g. a transient pool drop-to-zero while the JVM is under load), after
    // which every query fails permanently with "The database has been closed [90098]".
    // AUTO_RECONNECT only re-dials a connection and cannot revive an already-closed file store.
    // AUTO_SERVER=TRUE lets the three persona processes (admin console, chat, crawl manager) share
    // one project database; see KompileBootstrapEnvironmentPostProcessor, which supplies the real
    // URL. This literal is only the fallback for a context where that post-processor never ran, and
    // it has to agree with it or that path reintroduces the single-JVM file lock. Note that H2
    // refuses AUTO_SERVER=TRUE together with DB_CLOSE_ON_EXIT=FALSE ("Feature not supported"
    // [50100]), so the latter is gone from both URLs.
    @Value("${spring.datasource.url:jdbc:h2:file:./data/kompile-db;DB_CLOSE_DELAY=-1;AUTO_RECONNECT=TRUE;AUTO_SERVER=TRUE}")
    private String jdbcUrl;

    @Value("${spring.datasource.driverClassName:org.h2.Driver}")
    private String driverClassName;

    @Value("${spring.datasource.username:sa}")
    private String username;

    @Value("${spring.datasource.password:}")
    private String password;

    @Bean
    @Primary
    public DataSource dataSource() {
        // Redact credentials from JDBC URL before logging
        String safeUrl = jdbcUrl != null ? jdbcUrl.replaceAll("://[^@]+@", "://***@") : "null";
        log.info("Creating primary data source: {}", safeUrl);
        DataSourceBuilder<?> builder = DataSourceBuilder.create();
        builder.url(jdbcUrl);
        builder.driverClassName(driverClassName);
        builder.username(username);
        builder.password(password);
        return builder.build();
    }

    private static final String[] ENTITY_PACKAGES = {
        "ai.kompile.app.facts.domain",
        "ai.kompile.app.ingest.domain",
        "ai.kompile.app.staging.domain",
        "ai.kompile.app.eval.domain",
        "ai.kompile.app.prompts.domain",
        "ai.kompile.app.monitor.domain",
        "ai.kompile.chat.history.domain",
        "ai.kompile.knowledgegraph.domain",
        "ai.kompile.knowledgegraph.builder.domain",
        "ai.kompile.knowledgegraph.embedding.domain",
        "ai.kompile.knowledgegraph.persistence.dual",
        "ai.kompile.oauth.domain",
        "ai.kompile.orchestrator.model",
        "ai.kompile.enrichment.domain",
        "ai.kompile.app.diagram.domain",
        "ai.kompile.app.sync.domain",
        "ai.kompile.staging.domain",
        "ai.kompile.testmilestone.domain",
        "ai.kompile.codeindexer.domain",
        "ai.kompile.event.observation.domain",
        "ai.kompile.graphchangetracking.domain"
    };

    @Bean
    @Primary
    public PersistenceManagedTypes persistenceManagedTypes(ApplicationContext applicationContext) {
        return new PersistenceManagedTypesScanner(applicationContext).scan(ENTITY_PACKAGES);
    }

    @Bean
    public SchemaMigrationBridgeService schemaMigrationBridgeService(DataSource dataSource) {
        return new SchemaMigrationBridgeService(dataSource);
    }

    @Bean
    @Primary
    public LocalContainerEntityManagerFactoryBean entityManagerFactory(
            DataSource dataSource,
            SchemaMigrationBridgeService schemaMigrationBridgeService,
            PersistenceManagedTypes persistenceManagedTypes) {
        // Run bridge migrations BEFORE Hibernate schema update
        log.info("Running schema migrations before EntityManagerFactory creation...");
        schemaMigrationBridgeService.runMigrations();

        log.info("Creating primary entity manager factory");

        LocalContainerEntityManagerFactoryBean em = new LocalContainerEntityManagerFactoryBean();
        em.setDataSource(dataSource);
        // Use PersistenceManagedTypes for proper AOT/native image support
        // (setPackagesToScan doesn't work reliably in native images)
        em.setManagedTypes(persistenceManagedTypes);
        em.setPersistenceUnitName("primary");

        HibernateJpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
        em.setJpaVendorAdapter(vendorAdapter);
        em.setJpaProperties(hibernateProperties());

        return em;
    }

    @Bean(name = {"transactionManager", "ingestEventTransactionManager"})
    @Primary
    public PlatformTransactionManager transactionManager(LocalContainerEntityManagerFactoryBean entityManagerFactory) {
        log.info("Creating primary transaction manager (also aliased as ingestEventTransactionManager)");
        JpaTransactionManager transactionManager = new JpaTransactionManager();
        transactionManager.setEntityManagerFactory(entityManagerFactory.getObject());
        return transactionManager;
    }

    private Properties hibernateProperties() {
        Properties properties = new Properties();
        // H2Dialect is auto-detected from the JDBC URL - no need to set explicitly
        // (Hibernate 6.4+ warns: HHH90000025 if set manually)
        properties.put("hibernate.hbm2ddl.auto", "update");
        properties.put("hibernate.show_sql", false);
        properties.put("hibernate.format_sql", true);
        properties.put("hibernate.jdbc.batch_size", 50);
        properties.put("hibernate.order_inserts", true);
        properties.put("hibernate.order_updates", true);
        // Use 'none' bytecode provider for GraalVM native image compatibility
        // (ByteBuddy generates classes at runtime which is unsupported in native images)
        properties.put("hibernate.bytecode.provider", "none");
        return properties;
    }
}
