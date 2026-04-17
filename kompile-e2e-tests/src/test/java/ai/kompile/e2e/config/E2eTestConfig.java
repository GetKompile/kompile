package ai.kompile.e2e.config;

import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.llm.LanguageModel;
import ai.kompile.e2e.fixtures.InMemoryEmbeddingModel;
import ai.kompile.e2e.fixtures.StubLanguageModel;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Test configuration providing lightweight stub beans
 * that replace heavy production implementations.
 */
@TestConfiguration
public class E2eTestConfig {

    @Bean
    @Primary
    public EmbeddingModel testEmbeddingModel() {
        return new InMemoryEmbeddingModel(384);
    }

    @Bean
    @Primary
    public LanguageModel testLanguageModel() {
        return new StubLanguageModel();
    }
}
