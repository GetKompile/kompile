package ai.kompile.embedding.anserini;

import org.junit.jupiter.api.Test;

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
}
