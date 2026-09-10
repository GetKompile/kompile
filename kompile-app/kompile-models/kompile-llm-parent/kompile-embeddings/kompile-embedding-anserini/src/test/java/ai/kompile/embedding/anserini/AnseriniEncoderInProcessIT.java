package ai.kompile.embedding.anserini;

import io.anserini.encoder.samediff.SameDiffEncoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * In-process crash reproduction for the crawl-path embedding SIGABRT (exit 134):
 * builds the SAME encoder the subprocess main builds
 * ({@link AnseriniEncoderFactory#createEncoder(String)}, model bge-base-en-v1.5
 * from ~/.kompile/models) and drives LOAD + batch EMBED directly in this JVM.
 *
 * No subprocess, no launcher, no IPC — if the native layer aborts, this test
 * crashes with a real Java stack trace / hs_err file in place, and a debugger
 * attaches trivially. Serves as the fast regression gate before any crawl.
 */
class AnseriniEncoderInProcessIT {

    private static final String MODEL_ID = "bge-base-en-v1.5";

    @Test
    @Timeout(value = 300)
    void loadsAndEmbedsBatchesInProcess() throws Exception {
        SameDiffEncoder<float[]> encoder = AnseriniEncoderFactory.createEncoder(MODEL_ID);
        assertNotNull(encoder, "encoder factory returned null for " + MODEL_ID);

        // Single encode first — this is the path the crawl hits first
        float[] one = encoder.encode("Meridian Dynamics was founded by Elena Vasquez in Portland.");
        assertNotNull(one, "single encode returned null");
        assertEquals(768, one.length, "bge-base-en-v1.5 dim");

        // Then the crawl-shaped batch load: 19 batches x 32 texts
        List<String> texts = new ArrayList<>(32);
        for (int i = 0; i < 32; i++) {
            texts.add("Chunk " + i + ": She previously worked at Corvus Analytics "
                    + "before founding Meridian Dynamics.");
        }
        for (int b = 0; b < 19; b++) {
            List<float[]> out = encoder.encodeBatch(texts);
            assertEquals(32, out.size(), "batch " + b + " size");
        }

        encoder.close();
    }
}
