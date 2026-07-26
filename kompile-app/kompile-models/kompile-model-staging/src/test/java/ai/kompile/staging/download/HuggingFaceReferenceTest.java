package ai.kompile.staging.download;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HuggingFaceReferenceTest {

    @Test
    void acceptsRepositoryAndCanonicalTreeBlobResolveUrls() {
        HuggingFaceReference repository =
                HuggingFaceReference.parse(" owner/model ", null);
        assertEquals("owner/model", repository.repository());
        assertEquals("main", repository.requestedRevision());
        assertNull(repository.requestedPath());

        HuggingFaceReference tree = HuggingFaceReference.parse(
                "https://huggingface.co/owner/model/tree/release/quantized", null);
        assertEquals(HuggingFaceReference.Kind.TREE, tree.kind());
        assertEquals("release", tree.requestedRevision());
        assertEquals("quantized", tree.requestedPath());

        HuggingFaceReference blob = HuggingFaceReference.parse(
                "https://huggingface.co/owner/model/blob/v1/model-Q4_K_M.gguf", null);
        assertEquals(HuggingFaceReference.Kind.BLOB, blob.kind());
        assertEquals("model-Q4_K_M.gguf", blob.requestedPath());
        assertTrue(blob.requestedPathIsModel());

        HuggingFaceReference resolve = HuggingFaceReference.parse(
                "https://huggingface.co/owner/model/resolve/v1/model.ggml", "v1");
        assertEquals(HuggingFaceReference.Kind.RESOLVE, resolve.kind());
        assertTrue(resolve.requestedPathIsModel());
    }

    @Test
    void rejectsCredentialsQueriesFragmentsEncodedPathsAndConflictingRevisions() {
        assertThrows(IllegalArgumentException.class, () -> HuggingFaceReference.parse(
                "https://user:secret@huggingface.co/owner/model", null));
        assertThrows(IllegalArgumentException.class, () -> HuggingFaceReference.parse(
                "https://huggingface.co/owner/model?token=secret", null));
        assertThrows(IllegalArgumentException.class, () -> HuggingFaceReference.parse(
                "https://huggingface.co/owner/model#token=secret", null));
        assertThrows(IllegalArgumentException.class, () -> HuggingFaceReference.parse(
                "https://huggingface.co/owner/model/blob/main/a%2Fb.gguf", null));
        assertThrows(IllegalArgumentException.class, () -> HuggingFaceReference.parse(
                "https://huggingface.co/owner/model/blob/main/model.gguf", "other"));
    }

    @Test
    void rejectsAmbiguousOrNonCanonicalPaths() {
        assertThrows(IllegalArgumentException.class,
                () -> HuggingFaceReference.parse("owner/model/extra", null));
        assertThrows(IllegalArgumentException.class, () -> HuggingFaceReference.parse(
                "http://huggingface.co/owner/model", null));
        assertThrows(IllegalArgumentException.class, () -> HuggingFaceReference.parse(
                "https://example.com/owner/model", null));
        assertThrows(IllegalArgumentException.class, () -> HuggingFaceReference.parse(
                "https://huggingface.co/owner/model/blob/main", null));
    }
}
