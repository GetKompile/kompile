package ai.kompile.vectorstore.anserini;

import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.embedding.anserini.AnseriniEmbeddingModelImpl;
import ai.kompile.embedding.anserini.config.AnseriniEmbeddingConfiguration;
import ai.kompile.vectorstore.anserini.reranking.RerankerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ContextConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = {
        AnseriniVectorStoreWiringTest.TestConfig.class,
        AnseriniEmbeddingConfiguration.class,
        AnseriniEmbeddingModelImpl.class,
        AnseriniVectorStoreAutoConfiguration.class,
        RerankerConfiguration.class
}, properties = {
        "kompile.vectorstore.anserini.enabled=true",
        "kompile.vectorstore.anserini.persistence-enabled=true",
        "kompile.vectorstore.anserini.index-path=./target/test-index"
})
public class AnseriniVectorStoreWiringTest {

    @Autowired
    private ApplicationContext context;

    @Autowired(required = false)
    private AnseriniVectorStoreImpl vectorStore;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Test
    void testVectorStoreBeanCreation() {
        assertThat(embeddingModel).isNotNull();
        assertThat(embeddingModel).isInstanceOf(AnseriniEmbeddingModelImpl.class);

        assertThat(vectorStore).isNotNull();
        assertThat(vectorStore).isInstanceOf(AnseriniVectorStoreImpl.class);

        System.out.println(
                "SUCCESS: AnseriniVectorStoreImpl created with EmbeddingModel: " + embeddingModel.getClass().getName());
    }

    @Configuration
    @EnableAutoConfiguration
    static class TestConfig {
        // Properties are provided via @SpringBootTest(properties = ...)
    }
}
