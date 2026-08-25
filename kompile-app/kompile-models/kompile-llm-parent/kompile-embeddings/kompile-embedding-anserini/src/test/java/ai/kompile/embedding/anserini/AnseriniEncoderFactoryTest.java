package ai.kompile.embedding.anserini;

import io.anserini.encoder.samediff.GenericDenseSameDiffEncoder;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AnseriniEncoderFactoryTest {

    @Test
    void nullishRegistryEncoderTypeDoesNotOverrideModelIdDetection() {
        assertNull(AnseriniEncoderFactory.normalizeEncoderType(null));
        assertNull(AnseriniEncoderFactory.normalizeEncoderType(""));
        assertNull(AnseriniEncoderFactory.normalizeEncoderType(" null "));

        assertEquals(AnseriniEncoderFactory.EncoderType.BGE,
                AnseriniEncoderFactory.getEncoderTypeFromModelId("bge-base-en-v1.5"));
    }

    @Test
    void registryMetadataSelectsPoolingAndNormalizationWithoutModelNameRules() {
        Map<String, Object> metadata = Map.of(
                "pooling_strategy", "mean",
                "normalize_output", "false",
                "input_prefix", "query: ",
                "embedding_dim", "384");

        assertEquals(GenericDenseSameDiffEncoder.PoolingStrategy.MEAN,
                AnseriniEncoderFactory.poolingStrategy(metadata));
        assertEquals(false,
                AnseriniEncoderFactory.booleanMetadata(metadata, "normalize_output", true));
        assertEquals("query: ",
                AnseriniEncoderFactory.stringMetadata(metadata, "input_prefix", ""));
        assertEquals(384,
                AnseriniEncoderFactory.integerMetadata(metadata, "embedding_dim", null));
        assertEquals(GenericDenseSameDiffEncoder.PoolingStrategy.AUTO,
                AnseriniEncoderFactory.poolingStrategy(Map.of()));
    }
}
