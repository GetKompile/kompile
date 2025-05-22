package ai.kompile.app.chunker.markdown;

import ai.kompile.app.core.chunking.TextChunker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component("springMarkdownTextChunker")
public class SpringMarkdownChunker implements TextChunker {

    private static final String CHUNKER_NAME = "spring_markdown";
    // MarkdownTextSplitter specific options can be added if needed,
    // but its constructor in Spring AI 1.0 is parameterless or takes Tika properties.
    // For a simple wrapper, we might not expose many options beyond what the splitter does by default.

    @Override
    public List<Document> chunk(Document document, Map<String, Object> options) {
        Assert.notNull(document, "Document cannot be null");
        // Optionally, check if the document is indeed Markdown based on mime_type or file extension
        // String mimeType = (String) document.getMetadata().getOrDefault("mime_type", "");
        // String fileName = (String) document.getMetadata().getOrDefault("file_name", "");
        // if (!"text/markdown".equalsIgnoreCase(mimeType) && !fileName.toLowerCase().endsWith(".md")) {
        //    log.warn("SpringMarkdownChunker is intended for Markdown documents, but received mime_type: {}, file_name: {}", mimeType, fileName);
        // }

        // Spring AI 1.0.0 MarkdownTextSplitter, typically used within TikaDocumentReader context.
        // It's a DocumentTransformer.
        CustomMarkdownTextChunker textSplitter = new CustomMarkdownTextChunker();

        List<Document> splitDocuments = textSplitter.chunk(document,options);

        int chunkNumber = 0;
        for(Document chunk : splitDocuments) {
            Map<String, Object> metadata = new HashMap<>(chunk.getMetadata());
            metadata.putIfAbsent("original_document_id", document.getId());
            metadata.put("chunk_number", chunkNumber++);
            metadata.put("chunker", getName());

            chunk.getMetadata().clear();
            chunk.getMetadata().putAll(metadata);
        }
        log.debug("Split Markdown document {} into {} chunks using {}.", document.getId(), splitDocuments.size(), getName());
        return splitDocuments;
    }

    @Override
    public String getName() {
        return CHUNKER_NAME;
    }
}