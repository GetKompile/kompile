package ai.kompile.app.chunker.markdown;

import ai.kompile.app.core.chunking.TextChunker;
import lombok.extern.slf4j.Slf4j;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.text.TextContentRenderer;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component("customMarkdownTextChunker")
public class CustomMarkdownTextChunker implements TextChunker {

    private static final String CHUNKER_NAME = "custom_markdown";
    public static final String OPTION_SPLIT_ON_HEADINGS = "splitOnHeadings"; // true by default
    public static final String OPTION_MAX_CHARS_PER_CHUNK = "maxCharsPerChunk";
    // Could add options for min chunk size, overlap for text between structural elements

    public static final int DEFAULT_MAX_CHARS_PER_CHUNK = 2000; // Arbitrary default

    private final Parser parser;
    private final TextContentRenderer textRenderer;


    public CustomMarkdownTextChunker() {
        this.parser = Parser.builder().build();
        this.textRenderer = TextContentRenderer.builder().build();
    }


    @Override
    public List<Document> chunk(Document document, Map<String, Object> options) {
        Assert.notNull(document, "Document cannot be null");
        String markdownContent = document.getText();
        if (markdownContent == null || markdownContent.isBlank()) {
            return List.of();
        }

        boolean splitOnHeadings = (boolean) options.getOrDefault(OPTION_SPLIT_ON_HEADINGS, true);
        int maxCharsPerChunk = (int) options.getOrDefault(OPTION_MAX_CHARS_PER_CHUNK, DEFAULT_MAX_CHARS_PER_CHUNK);


        List<Document> chunks = new ArrayList<>();
        Node markdownNode = parser.parse(markdownContent);

        StringBuilder currentChunkBuffer = new StringBuilder();
        int chunkNumber = 0;

        // Visitor to traverse the Markdown AST
        MyAbstractVisitor visitor = new MyAbstractVisitor(splitOnHeadings, currentChunkBuffer, document, chunks, chunkNumber, maxCharsPerChunk);

        markdownNode.accept(visitor);

        // Add any remaining content in the buffer as the last chunk
        if (currentChunkBuffer.length() > 0) {
            addChunk(currentChunkBuffer.toString().trim(), document, chunks, document.getMetadata(), chunkNumber, visitor.currentHeadingText, visitor.currentHeadingLevel);
        }

        log.debug("Split Markdown document {} into {} chunks using {}.", document.getId(), chunks.size(), getName());
        return chunks;
    }

    private void addChunk(String content, Document originalDocument, List<Document> chunkListToAddTo, Map<String,Object> originalMetadata, int chunkNum, String currentHeading, int headingLevel) {
        if (content == null || content.isBlank()) return;

        Map<String, Object> metadata = new HashMap<>(originalMetadata);
        metadata.put("original_document_id", originalDocument.getId());
        metadata.put("chunk_number", chunkNum); // Note: this chunkNum is not incremented here, but in the calling loop
        metadata.put("chunker", getName());
        if (currentHeading != null && !currentHeading.isBlank()) {
            metadata.put("heading_context", currentHeading);
            metadata.put("heading_level", headingLevel);
        }
        chunkListToAddTo.add(new Document(UUID.randomUUID().toString(), content, metadata));
    }

    private String renderNodeToText(Node node) {
        return textRenderer.render(node).trim();
    }


    @Override
    public String getName() {
        return CHUNKER_NAME;
    }

    private class MyAbstractVisitor extends AbstractVisitor {
        private final boolean splitOnHeadings;
        private final StringBuilder currentChunkBuffer;
        private final Document document;
        private final List<Document> chunks;
        private final int chunkNumber;
        private final int maxCharsPerChunk;
        public String currentHeadingText;
        public int currentHeadingLevel;

        public MyAbstractVisitor(boolean splitOnHeadings, StringBuilder currentChunkBuffer, Document document, List<Document> chunks, int chunkNumber, int maxCharsPerChunk) {
            this.splitOnHeadings = splitOnHeadings;
            this.currentChunkBuffer = currentChunkBuffer;
            this.document = document;
            this.chunks = chunks;
            this.chunkNumber = chunkNumber;
            this.maxCharsPerChunk = maxCharsPerChunk;
            currentHeadingText = null;
            currentHeadingLevel = 0;
        }

        @Override
        public void visit(Heading heading) {
            if (splitOnHeadings && currentChunkBuffer.length() > 0) {
                // If we encounter a new heading and buffer is not empty,
                // finalize the current chunk.
                addChunk(currentChunkBuffer.toString().trim(), document, chunks, document.getMetadata(), chunkNumber, currentHeadingText, currentHeadingLevel);
                currentChunkBuffer.setLength(0); // Reset buffer
            }
            // Render heading into the buffer as it's part of the next chunk
            String headingTextContent = renderNodeToText(heading);
            currentChunkBuffer.append(headingTextContent).append("\n\n");

            // Capture heading text for metadata
            currentHeadingText = headingTextContent.replace("#", "").trim();
            currentHeadingLevel = heading.getLevel();

            super.visit(heading); // Continue visiting children of heading if any
        }

        @Override
        public void visit(Paragraph paragraph) {
            appendNodeText(paragraph);
            super.visit(paragraph);
        }

        @Override
        public void visit(BlockQuote blockQuote) {
            appendNodeText(blockQuote); // Consider rendering block quotes with >
            super.visit(blockQuote);
        }

        @Override
        public void visit(ListItem listItem) {
            // Append list item marker if not already handled by renderer
            String renderedItem = renderNodeToText(listItem);
            // A simple way to ensure list markers, could be more robust
            if (listItem.getParent() instanceof OrderedList) {
                // OrderedList doesn't easily give its counter via commonmark node API for renderer
                currentChunkBuffer.append("* "); // simplified
            } else if (listItem.getParent() instanceof BulletList) {
                currentChunkBuffer.append("* ");
            }
            currentChunkBuffer.append(renderedItem).append("\n");
            super.visit(listItem);
        }


        @Override
        public void visit(FencedCodeBlock fencedCodeBlock) {
            // If a code block alone is large, it might become its own chunk
            // or split if it exceeds maxChars. For simplicity here, add its content.
            String codeContent = fencedCodeBlock.getLiteral();
            String lang = fencedCodeBlock.getInfo() != null ? fencedCodeBlock.getInfo() : "";
            currentChunkBuffer.append("```").append(lang).append("\n");
            currentChunkBuffer.append(codeContent);
            currentChunkBuffer.append("\n```\n\n");
            super.visit(fencedCodeBlock);
        }

        @Override
        public void visit(ThematicBreak thematicBreak) {
            // Could be a split point
            if (currentChunkBuffer.length() > 0) {
                addChunk(currentChunkBuffer.toString().trim(), document, chunks, document.getMetadata(), chunkNumber, currentHeadingText, currentHeadingLevel);
                currentChunkBuffer.setLength(0);
                currentHeadingText = null; // Reset heading context after a thematic break
                currentHeadingLevel = 0;
            }
            currentChunkBuffer.append("---\n\n"); // Add the break itself
            super.visit(thematicBreak);
        }

        // Helper to append rendered text of a node
        private void appendNodeText(Node node) {
            String text = renderNodeToText(node);
            if (!text.isBlank()) {
                // Simple oversized chunk handling: if adding this node makes current buffer too big,
                // finalize current buffer and start new one with this node.
                if (currentChunkBuffer.length() + text.length() > maxCharsPerChunk && currentChunkBuffer.length() > 0) {
                    addChunk(currentChunkBuffer.toString().trim(), document, chunks, document.getMetadata(), chunkNumber, currentHeadingText, currentHeadingLevel);
                    currentChunkBuffer.setLength(0);
                    // currentHeadingText/Level would persist until next heading or thematic break
                }
                currentChunkBuffer.append(text).append("\n\n"); // Add double newline for paragraph-like separation
            }
        }
    }
}