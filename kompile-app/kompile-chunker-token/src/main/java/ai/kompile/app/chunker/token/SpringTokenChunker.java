package ai.kompile.app.chunker.token;

import ai.kompile.app.core.chunking.TextChunker;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component("springTokenTextChunker")
public class SpringTokenChunker implements TextChunker {

    private static final String CHUNKER_NAME = "spring_token";

    // Options aligned with Spring AI's TokenTextSplitter.Builder
    public static final String OPTION_CHUNK_SIZE_TOKENS = "chunkSize"; // Target size of each chunk in tokens
    public static final String OPTION_MIN_CHUNK_SIZE_CHARS = "minChunkSizeChars"; // Minimum size of each text chunk in characters
    public static final String OPTION_MIN_CHUNK_LENGTH_TO_EMBED_CHARS = "minChunkLengthToEmbed"; // Discard chunks shorter than this (chars)
    public static final String OPTION_MAX_NUM_CHUNKS = "maxNumChunks"; // Maximum number of chunks to generate
    public static final String OPTION_KEEP_SEPARATOR = "keepSeparator"; // Whether to keep separators
    public static final String OPTION_TOKENIZER_NAME = "tokenizerName"; // Kept for conceptual compatibility, though TokenTextSplitter uses fixed CL100K_BASE

    // Defaults from Spring AI's TokenTextSplitter
    public static final int DEFAULT_CHUNK_SIZE_TOKENS = 800;
    public static final int DEFAULT_MIN_CHUNK_SIZE_CHARS = 350;
    public static final int DEFAULT_MIN_CHUNK_LENGTH_TO_EMBED_CHARS = 5;
    public static final int DEFAULT_MAX_NUM_CHUNKS = 10000;
    public static final boolean DEFAULT_KEEP_SEPARATOR = true;
    public static final String DEFAULT_TOKENIZER_NAME = EncodingType.CL100K_BASE.getName();


    public SpringTokenChunker() {
        // EncodingRegistry is handled internally by TokenTextSplitter if not using a custom one.
    }

    @Override
    public List<Document> chunk(Document document, Map<String, Object> options) {
        Assert.notNull(document, "Document cannot be null");
        Assert.notNull(options, "Options map cannot be null");

        int chunkSize = (int) options.getOrDefault(OPTION_CHUNK_SIZE_TOKENS, DEFAULT_CHUNK_SIZE_TOKENS);
        int minChunkSizeChars = (int) options.getOrDefault(OPTION_MIN_CHUNK_SIZE_CHARS, DEFAULT_MIN_CHUNK_SIZE_CHARS);
        int minChunkLengthToEmbed = (int) options.getOrDefault(OPTION_MIN_CHUNK_LENGTH_TO_EMBED_CHARS, DEFAULT_MIN_CHUNK_LENGTH_TO_EMBED_CHARS);
        int maxNumChunks = (int) options.getOrDefault(OPTION_MAX_NUM_CHUNKS, DEFAULT_MAX_NUM_CHUNKS);
        boolean keepSeparator = (boolean) options.getOrDefault(OPTION_KEEP_SEPARATOR, DEFAULT_KEEP_SEPARATOR);
        String tokenizerNameOption = (String) options.getOrDefault(OPTION_TOKENIZER_NAME, DEFAULT_TOKENIZER_NAME);

        if (!DEFAULT_TOKENIZER_NAME.equalsIgnoreCase(tokenizerNameOption)) {
            log.warn("Provided tokenizerName option ('{}') is ignored. Spring AI's TokenTextSplitter internally uses '{}'.",
                    tokenizerNameOption, DEFAULT_TOKENIZER_NAME);
        }

        TokenTextSplitter textSplitter = TokenTextSplitter.builder()
                .withChunkSize(chunkSize)
                .withMinChunkSizeChars(minChunkSizeChars)
                .withMinChunkLengthToEmbed(minChunkLengthToEmbed)
                .withMaxNumChunks(maxNumChunks)
                .withKeepSeparator(keepSeparator)
                .build();

        List<Document> sourceDocuments = Collections.singletonList(document);
        // The apply method in TextSplitter handles the conversion from List<Document> to List<String> for splitting,
        // then wraps the resulting strings back into Document objects.
        List<Document> splitDocumentsRaw = textSplitter.apply(sourceDocuments);

        List<Document> finalSplitDocuments = new ArrayList<>();
        int chunkNumber = 0;
        for(Document chunk : splitDocumentsRaw) {
            Map<String, Object> metadata = new HashMap<>(chunk.getMetadata()); // Start with existing metadata from splitter
            metadata.putIfAbsent("original_document_id", document.getId());
            metadata.put("chunk_number", chunkNumber++);
            metadata.put("chunker", getName());
            // The actual tokenizer used by TokenTextSplitter is CL100K_BASE
            metadata.put("tokenizer", DEFAULT_TOKENIZER_NAME);


            finalSplitDocuments.add(new Document(chunk.getId(), chunk.getText(), metadata));
        }

        log.debug("Split document {} into {} chunks using {}. Options: chunkSize={}, minChunkSizeChars={}, minChunkLengthToEmbed={}, maxNumChunks={}, keepSeparator={}. Tokenizer used: {}",
                document.getId(), finalSplitDocuments.size(), getName(),
                chunkSize, minChunkSizeChars, minChunkLengthToEmbed, maxNumChunks, keepSeparator, DEFAULT_TOKENIZER_NAME);
        return finalSplitDocuments;
    }

    @Override
    public String getName() {
        return CHUNKER_NAME;
    }

    @Override
    public List<String> getSupportedLanguages() {
        // jtokkit tokenizer CL100K_BASE is trained on diverse data but primarily optimized for English.
        return Arrays.asList("en", "multi");
    }
}