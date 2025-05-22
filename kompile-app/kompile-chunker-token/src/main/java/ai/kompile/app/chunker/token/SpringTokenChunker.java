package ai.kompile.app.chunker.token;

import ai.kompile.app.core.chunking.TextChunker;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Component("springTokenTextChunker")
public class SpringTokenChunker implements TextChunker {

    private static final String CHUNKER_NAME = "spring_token";
    public static final String OPTION_MAX_TOKEN_LIMIT = "maxTokenLimit";
    public static final String OPTION_MIN_CHUNK_SIZE_TOKENS = "minChunkSizeTokens"; // Corrected name
    public static final String OPTION_CHUNK_OVERLAP_TOKENS = "chunkOverlapTokens"; // Corrected name
    public static final String OPTION_TOKENIZER_NAME = "tokenizerName"; // e.g., "cl100k_base" for gpt-3.5-turbo/gpt-4

    public static final int DEFAULT_MAX_TOKEN_LIMIT = 1000; // Default in Spring AI
    public static final int DEFAULT_MIN_CHUNK_SIZE_TOKENS = 20; // Sensible default
    public static final int DEFAULT_CHUNK_OVERLAP_TOKENS = 5; // Sensible default for tokens
    public static final String DEFAULT_TOKENIZER_NAME = EncodingType.CL100K_BASE.getName();

    private final EncodingRegistry encodingRegistry;

    public SpringTokenChunker() {
        this.encodingRegistry = Encodings.newDefaultEncodingRegistry();
    }

    @Override
    public List<Document> chunk(Document document, Map<String, Object> options) {
        Assert.notNull(document, "Document cannot be null");
        Assert.notNull(options, "Options map cannot be null");

        int maxTokenLimit = (int) options.getOrDefault(OPTION_MAX_TOKEN_LIMIT, DEFAULT_MAX_TOKEN_LIMIT);
        int minTokens = (int) options.getOrDefault(OPTION_MIN_CHUNK_SIZE_TOKENS, DEFAULT_MIN_CHUNK_SIZE_TOKENS);
        int chunkOverlap = (int) options.getOrDefault(OPTION_CHUNK_OVERLAP_TOKENS, DEFAULT_CHUNK_OVERLAP_TOKENS);
        String tokenizerName = (String) options.getOrDefault(OPTION_TOKENIZER_NAME, DEFAULT_TOKENIZER_NAME);

        EncodingType encodingType;
        try {
            encodingType = EncodingType.valueOf(tokenizerName.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown tokenizer name '{}', defaulting to {}. Available: {}",
                    tokenizerName, DEFAULT_TOKENIZER_NAME,
                    List.of(EncodingType.values()));
            encodingType = EncodingType.valueOf(DEFAULT_TOKENIZER_NAME.toUpperCase());
        }

        Encoding encoding = encodingRegistry.getEncoding(encodingType);

        // In Spring AI 1.0.0, TokenTextSplitter has a builder
        TokenTextSplitter textSplitter = TokenTextSplitter.builder()
                .withChunkSize(maxTokenLimit)
                .withMaxNumChunks(minTokens)
                .withMinChunkSizeChars(chunkOverlap)
                .withKeepSeparator(false)
                .build();

        List<Document> splitDocuments = textSplitter.apply(List.of(document));

        int chunkNumber = 0;
        for(Document chunk : splitDocuments) {
            Map<String, Object> metadata = new HashMap<>(chunk.getMetadata());
            metadata.putIfAbsent("original_document_id", document.getId());
            metadata.put("chunk_number", chunkNumber++);
            metadata.put("chunker", getName());

            chunk.getMetadata().clear();
            chunk.getMetadata().putAll(metadata);
        }
        log.debug("Split document {} into {} chunks using {}. Options: maxTokens={}, minTokens={}, overlapTokens={}, tokenizer={}",
                document.getId(), splitDocuments.size(), getName(), maxTokenLimit, minTokens, chunkOverlap, tokenizerName);
        return splitDocuments;
    }

    @Override
    public String getName() {
        return CHUNKER_NAME;
    }
}