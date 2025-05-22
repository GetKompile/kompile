package ai.kompile.app.core.chunking;

import org.springframework.ai.document.Document;
import java.util.List;
import java.util.Map;

/**
 * Interface for text chunking strategies.
 * Implementations of this interface will provide different ways to split a Document into smaller chunks.
 */
public interface TextChunker {

    /**
     * Chunks the given document into a list of smaller documents.
     *
     * @param document The document to be chunked.
     * @param options  A map of options to configure the chunking process (e.g., chunkSize, overlap, language).
     * @return A list of chunked documents.
     */
    List<Document> chunk(Document document, Map<String, Object> options);

    /**
     * Returns the name of the chunking strategy.
     * This can be used to identify and select a specific chunker.
     * @return The name of the chunker.
     */
    String getName();

    /**
     * Returns a list of language codes (e.g., "en", "es", "ja") supported by this chunker.
     * An asterisk (*) can be used to indicate that the chunker is language-agnostic.
     * @return A list of supported language codes.
     */
    List<String> getSupportedLanguages();
}