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

package ai.kompile.app.subprocess;

import ai.kompile.vectorstore.anserini.AnseriniVectorStoreAutoConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.PropertySource;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

/**
 * Minimal Spring configuration for the graph subprocess.
 *
 * <p>Creates a lightweight context with only the beans needed for
 * matrix graph storage, graph algorithms, and reasoning:</p>
 * <ul>
 *   <li>MatrixGraphStore</li>
 *   <li>KnowledgeGraphService / GraphRagService over the matrix store</li>
 *   <li>Reasoning services that operate beside the graph owner</li>
 * </ul>
 *
 * <p>This is the graph subprocess counterpart to SubprocessIngestConfiguration.
 * It uses a whitelist approach — only explicitly imported auto-configurations
 * are loaded, preventing JPA/web modules from being scanned.</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "kompile.subprocess.mode", havingValue = "true", matchIfMissing = false)
@ComponentScan(
    basePackages = {
        // Core interfaces and shared model types used by graph/reasoning services.
        "ai.kompile.core",
        // Knowledge graph module (MatrixGraphStore, KnowledgeGraphService, algorithms).
        "ai.kompile.knowledgegraph",
        // Graph reasoning/event attribution runs beside the matrix KG owner so the main app
        // does not materialize Bayesian/MEBN/PSL/FOL working sets in Tomcat threads.
        "ai.kompile.graph.reasoning",
        "ai.kompile.event.attribution",
        // NOTE: ai.kompile.embedding.anserini is deliberately NOT scanned. The graph subprocess is
        // VECTOR-ONLY (WS7) — it reads stored node embeddings + receives query vectors, never embeds —
        // so it needs no embedding-model beans, and scanning that package pulled in
        // SpringAiEmbeddingModelAdapter (implements Spring-AI's EmbeddingModel) whose REQUIRED
        // @Qualifier("anseriniEmbeddingModelImpl") constructor dep can't be satisfied once the
        // grandchild-spawning AnseriniEmbeddingModelImpl is gone → context boot failure. Nothing in the
        // graph subprocess references this package (the vector store lives in ai.kompile.vectorstore.anserini).
        // Model manager (for model download/caching)
        "ai.kompile.modelmanager",
        // Core agent abstractions used by graph-adjacent reasoning components when present.
        "ai.kompile.core.agent"
    },
    excludeFilters = {
        // Exclude web-related components
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*Controller"),
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*WebSocket.*"),
        // Exclude scheduling
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*Scheduler.*"),
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*Scheduled.*"),
        // Exclude JPA repositories and services that need a database
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*Repository"),
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*JpaRepository"),
        // Exclude services not needed in subprocess
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*BroadcasterService.*"),
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*DocumentRetriever.*"),
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*AnseriniSearchService.*"),
        // Exclude KG embedding controllers and services that need JPA
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*KGEmbeddingController.*"),
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*KnowledgeGraphController.*"),
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*FactSheetGraphController.*"),
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*GraphIOController.*"),
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*KnowledgeGraphBuilderController.*"),
        // Extraction runs in the main app where the configured CLI/LFM agent beans live. The graph-matrix
        // subprocess owns the store/reasoning side only; do not instantiate MatrixGraphConstructor here.
        @ComponentScan.Filter(type = FilterType.REGEX,
                pattern = "ai\\.kompile\\.knowledgegraph\\.matrix\\.service\\.MatrixGraphConstructor"),
        // Exclude the JPA/builder graph path + JPA-backed services — they need a DataSource the subprocess
        // intentionally lacks (the main app holds the H2 file lock). The @Primary live path is the Lucene
        // matrix store; the matrix store + services depend on NONE of these (verified), so excluding is safe.
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = "ai\\.kompile\\.knowledgegraph\\.builder\\..*"),
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = "ai\\.kompile\\.knowledgegraph\\.persistence\\..*"),
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = "ai\\.kompile\\.knowledgegraph\\.io\\..*"),
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = "ai\\.kompile\\.knowledgegraph\\.tool\\..*"),
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*SourceWeightingServiceImpl"),
        // KGE (knowledge-graph embedding: RotatE/TransE training, job tracking, config persistence) is a
        // JPA/JDBC-backed subsystem and a SEPARATE concern from the matrix graph store. The three matrix
        // services (constructor/rag/kg) import NONE of ai.kompile.knowledgegraph.embedding (verified), so the
        // whole package is excluded with ONE filter rather than chasing individual @Service beans that each
        // inject an EntityManagerFactory/JdbcTemplate (KGEmbeddingConfigService, KGEmbeddingJobService,
        // KGEmbeddingSchemaBridgeService, the JPA KG-embedding adapter, …). KGE training runs in the main app.
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = "ai\\.kompile\\.knowledgegraph\\.embedding\\..*"),
        // Graph maintenance (snapshots/restore/compaction/health/orphan-pruning) is an ADMIN subsystem that
        // runs in the MAIN app. The matrix store path consumes NONE of maintenance.* (verified: only
        // GraphMaintenanceController + maintenance-internal beans reference it). In the split architecture the
        // main app's SnapshotManager reaches the subprocess-resident graph through the @Primary service client,
        // so snapshot/restore still works end-to-end without standing maintenance up inside the subprocess.
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = "ai\\.kompile\\.knowledgegraph\\.maintenance\\..*")
    }
)
@EnableConfigurationProperties
@PropertySource(value = "classpath:application.properties", ignoreResourceNotFound = true)
@Import({
    JacksonAutoConfiguration.class,
    AnseriniVectorStoreAutoConfiguration.class
})
public class SubprocessGraphConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(SubprocessGraphConfiguration.class);

    /**
     * Throwaway in-memory H2 {@link DataSource} for the few scanned beans that inject one
     * (e.g. KGEmbeddingSchemaBridgeService). The matrix subsystem uses Lucene, not JPA, so this db
     * stays empty — and being IN-MEMORY it never opens the main app's H2 file (which the main app
     * holds locked). Keeps the subprocess self-contained without standing up full JPA/Hibernate.
     */
    @Bean
    public DataSource subprocessGraphDataSource() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:graph-subprocess;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        ds.setUsername("sa");
        ds.setPassword("");
        return ds;
    }

    /** JdbcTemplate over the throwaway in-memory DataSource for scanned beans that inject one. */
    @Bean
    public JdbcTemplate subprocessGraphJdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /** NamedParameterJdbcTemplate over the same throwaway DataSource. */
    @Bean
    public NamedParameterJdbcTemplate subprocessGraphNamedJdbcTemplate(DataSource dataSource) {
        return new NamedParameterJdbcTemplate(dataSource);
    }
}
