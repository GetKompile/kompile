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
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.Properties;

/**
 * Primary data source configuration for the application.
 * All domain entities use this single database.
 */
@Slf4j
@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(
    basePackages = {
        "ai.kompile.app.facts.repository",
        "ai.kompile.app.ingest.repository",
        "ai.kompile.app.staging.repository",
        "ai.kompile.app.eval.repository",
        "ai.kompile.app.prompts.repository",
        "ai.kompile.chat.history.repository",
        "ai.kompile.knowledgegraph.repository",
        "ai.kompile.knowledgegraph.builder.repository",
        "ai.kompile.knowledgegraph.embedding.repository",
        "ai.kompile.oauth.repository",
        "ai.kompile.orchestrator.repository"
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
    "ai.kompile.chat.history.domain",
    "ai.kompile.knowledgegraph.domain",
    "ai.kompile.knowledgegraph.builder.domain",
    "ai.kompile.knowledgegraph.embedding.domain",
    "ai.kompile.oauth.domain",
    "ai.kompile.orchestrator.model"
})
public class PrimaryDataSourceConfig {

    @Value("${spring.datasource.url:jdbc:h2:file:./data/kompile-db;DB_CLOSE_ON_EXIT=FALSE;AUTO_RECONNECT=TRUE}")
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
        log.info("Creating primary data source: {}", jdbcUrl);
        DataSourceBuilder<?> builder = DataSourceBuilder.create();
        builder.url(jdbcUrl);
        builder.driverClassName(driverClassName);
        builder.username(username);
        builder.password(password);
        return builder.build();
    }

    @Bean
    public SchemaMigrationBridgeService schemaMigrationBridgeService(DataSource dataSource) {
        return new SchemaMigrationBridgeService(dataSource);
    }

    @Bean
    @Primary
    public LocalContainerEntityManagerFactoryBean entityManagerFactory(
            DataSource dataSource,
            SchemaMigrationBridgeService schemaMigrationBridgeService) {
        // Run bridge migrations BEFORE Hibernate schema update
        log.info("Running schema migrations before EntityManagerFactory creation...");
        schemaMigrationBridgeService.runMigrations();

        log.info("Creating primary entity manager factory");

        LocalContainerEntityManagerFactoryBean em = new LocalContainerEntityManagerFactoryBean();
        em.setDataSource(dataSource);
        em.setPackagesToScan(
            "ai.kompile.app.facts.domain",
            "ai.kompile.app.ingest.domain",
            "ai.kompile.app.staging.domain",
            "ai.kompile.app.eval.domain",
            "ai.kompile.app.prompts.domain",
            "ai.kompile.chat.history.domain",
            "ai.kompile.knowledgegraph.domain",
            "ai.kompile.knowledgegraph.builder.domain",
            "ai.kompile.knowledgegraph.embedding.domain",
            "ai.kompile.oauth.domain",
            "ai.kompile.orchestrator.model"
        );
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
        properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
        properties.put("hibernate.hbm2ddl.auto", "update");
        properties.put("hibernate.show_sql", false);
        properties.put("hibernate.format_sql", true);
        properties.put("hibernate.jdbc.batch_size", 50);
        properties.put("hibernate.order_inserts", true);
        properties.put("hibernate.order_updates", true);
        return properties;
    }
}
