/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.app.chunker.markdown;

import ai.kompile.app.core.chunking.SentenceFilter;
import ai.kompile.app.core.chunking.TextChunker;
import ai.kompile.core.retrievers.RetrievedDoc;
import lombok.extern.slf4j.Slf4j;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.text.TextContentRenderer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component("customMarkdownTextChunker")
public class CustomMarkdownTextChunker implements TextChunker {

    private static final String CHUNKER_NAME = "custom_markdown";
    public static final String OPTION_SPLIT_ON_HEADINGS = "splitOnHeadings"; // true by default
    public static final String OPTION_MAX_CHARS_PER_CHUNK = "maxCharsPerChunk";

    public static final int DEFAULT_MAX_CHARS_PER_CHUNK = 2000; // Arbitrary default
    private static final Pattern SENTENCE_END_PATTERN = Pattern.compile("[.!?]\\s+");

    private final Parser parser;
    private final TextContentRenderer textRenderer;

    public CustomMarkdownTextChunker() {
        this.parser = Parser.builder().build();
        this.textRenderer = TextContentRenderer.builder().build();
    }

    @Override
    public List<String> getSupportedLanguages() {
        // Markdown structure is language-agnostic.
        // Text content processing within markdown for chunking (e.g. sentence splitting) might have
        // language dependencies if advanced NLP techniques were used, but this implementation is generic.
        return Collections.singletonList("*");
    }

    @Override
    public List<RetrievedDoc> chunk(RetrievedDoc document, Map<String, Object> options) {
        // Validate document using the interface method
        validateDocument(document);

        // Prepare options with defaults
        Map<String, Object> mergedOptions = prepareOptions(options);
        boolean collectGarbage = (Boolean) mergedOptions.getOrDefault(OPTION_COLLECT_GARBAGE, true);
        boolean includeGarbageChunk = (Boolean) mergedOptions.getOrDefault(OPTION_INCLUDE_GARBAGE_CHUNK, true);

        String markdownContent = document.getText();

        boolean splitOnHeadings = (boolean) mergedOptions.getOrDefault(OPTION_SPLIT_ON_HEADINGS, true);
        int maxCharsPerChunk = (int) mergedOptions.getOrDefault(OPTION_MAX_CHARS_PER_CHUNK, DEFAULT_MAX_CHARS_PER_CHUNK);

        List<RetrievedDoc> chunks = new ArrayList<>();
        Node markdownNode = parser.parse(markdownContent);

        MyAbstractVisitor visitor = new MyAbstractVisitor(
                splitOnHeadings,
                document,
                chunks,
                maxCharsPerChunk,
                textRenderer
        );

        markdownNode.accept(visitor);

        // Add any remaining content in the buffer as the last chunk
        visitor.finalizeChunk();

        log.debug("Split Markdown document {} into {} chunks using {}.", document.getId(), chunks.size(), getName());

        // Apply sentence filtering and garbage collection if enabled
        if (collectGarbage) {
            return SentenceFilter.filterAndCollectGarbage(chunks, document, getName(), includeGarbageChunk);
        }

        return chunks;
    }

    @Override
    public String getName() {
        return CHUNKER_NAME;
    }

    @Override
    public Map<String, Object> getDefaultOptions() {
        Map<String, Object> defaults = new HashMap<>();
        defaults.put("chunkSize", DEFAULT_MAX_CHARS_PER_CHUNK);
        defaults.put("overlap", 200);
        defaults.put("preserveParagraphs", true);
        defaults.put(OPTION_SPLIT_ON_HEADINGS, true);
        defaults.put(OPTION_MAX_CHARS_PER_CHUNK, DEFAULT_MAX_CHARS_PER_CHUNK);
        // Garbage collection options - disabled by default (see TextChunker interface)
        defaults.put(OPTION_COLLECT_GARBAGE, false);
        defaults.put(OPTION_INCLUDE_GARBAGE_CHUNK, true);
        return defaults;
    }

    private static class MyAbstractVisitor extends AbstractVisitor {
        private final boolean splitOnHeadings;
        private final StringBuilder currentChunkBuffer;
        private final RetrievedDoc originalDocument;
        private final List<RetrievedDoc> chunks;
        private int chunkNumber; // Now an instance variable to be incremented
        private final int maxCharsPerChunk;
        private final TextContentRenderer textRenderer;
        public String currentHeadingText;
        public int currentHeadingLevel;
        private int orderedListItemCounter;

        public MyAbstractVisitor(boolean splitOnHeadings, RetrievedDoc document, List<RetrievedDoc> chunks, int maxCharsPerChunk, TextContentRenderer textRenderer) {
            this.splitOnHeadings = splitOnHeadings;
            this.currentChunkBuffer = new StringBuilder();
            this.originalDocument = document;
            this.chunks = chunks;
            this.chunkNumber = 0;
            this.maxCharsPerChunk = maxCharsPerChunk;
            this.textRenderer = textRenderer;
            this.currentHeadingText = null;
            this.currentHeadingLevel = 0;
            this.orderedListItemCounter = 0;
        }

        private void addChunkToList(String content, String headingContext, int headingLvl) {
            if (content == null || content.isBlank()) return;

            Map<String, Object> metadata = new HashMap<>(originalDocument.getMetadata());
            metadata.put("original_document_id", originalDocument.getId());
            metadata.put("chunk_number", chunkNumber++);
            metadata.put("chunker", CustomMarkdownTextChunker.CHUNKER_NAME);
            if (headingContext != null && !headingContext.isBlank()) {
                metadata.put("heading_context", headingContext);
                metadata.put("heading_level", headingLvl);
            }

            // Create RetrievedDoc using proper constructor
            RetrievedDoc chunk;
            if (originalDocument.getScore() != null) {
                chunk = new RetrievedDoc(UUID.randomUUID().toString(), content.trim(), metadata, originalDocument.getScore());
            } else {
                chunk = new RetrievedDoc(UUID.randomUUID().toString(), content.trim(), metadata);
            }
            chunks.add(chunk);
        }

        public void finalizeChunk() {
            if (currentChunkBuffer.length() > 0) {
                addChunkToList(currentChunkBuffer.toString(), this.currentHeadingText, this.currentHeadingLevel);
                currentChunkBuffer.setLength(0);
            }
        }

        private String renderNodeToText(Node node) {
            return textRenderer.render(node); // Trimmed later to preserve leading/trailing spaces for concatenation
        }

        @Override
        public void visit(Heading heading) {
            if (splitOnHeadings) {
                finalizeChunk(); // Finalize previous chunk before starting a new one with a heading
            }
            String headingTextContent = renderNodeToText(heading).trim();
            // Update heading context for subsequent chunks
            this.currentHeadingText = headingTextContent.replaceAll("#", "").trim();
            this.currentHeadingLevel = heading.getLevel();

            // Append heading to current buffer, try to split if heading itself is too long
            appendNodeTextWithPotentialSplitting(headingTextContent + "\n\n");
            super.visit(heading);
        }

        @Override
        public void visit(Paragraph paragraph) {
            appendNodeTextWithPotentialSplitting(renderNodeToText(paragraph) + "\n\n");
            super.visit(paragraph);
        }

        @Override
        public void visit(BlockQuote blockQuote) {
            // Could prepend "> " to each line if desired, textRenderer typically doesn't do this.
            // For simplicity, treating as normal text content.
            appendNodeTextWithPotentialSplitting(renderNodeToText(blockQuote) + "\n\n");
            super.visit(blockQuote);
        }

        @Override
        public void visit(BulletList bulletList) {
            super.visit(bulletList);
        }

        @Override
        public void visit(OrderedList orderedList) {
            orderedListItemCounter = orderedList.getStartNumber(); // Initialize or reset for nested lists
            super.visit(orderedList);
            orderedListItemCounter = 0; // Reset after list processing
        }

        @Override
        public void visit(ListItem listItem) {
            String itemMarker;
            Node parent = listItem.getParent();
            if (parent instanceof OrderedList) {
                itemMarker = orderedListItemCounter++ + ". ";
            } else { // BulletList
                itemMarker = "* ";
            }
            // Render the item content. If it's complex (e.g., nested paragraphs),
            // the sub-visits will handle their content. Here, we just add the marker.
            // The actual content of list item children will be appended by their respective visit methods.
            String renderedItem = renderNodeToText(listItem).trim();
            appendNodeTextWithPotentialSplitting(itemMarker + renderedItem + "\n");
            super.visit(listItem);
        }

        @Override
        public void visit(FencedCodeBlock fencedCodeBlock) {
            String lang = fencedCodeBlock.getInfo() != null ? fencedCodeBlock.getInfo() : "";
            String codeContent = "```" + lang + "\n" + fencedCodeBlock.getLiteral() + "```\n\n";
            appendNodeTextWithPotentialSplitting(codeContent);
            super.visit(fencedCodeBlock);
        }

        @Override
        public void visit(ThematicBreak thematicBreak) {
            finalizeChunk(); // A thematic break is a good point to split.
            // this.currentHeadingText = null; // Reset heading context after a thematic break
            // this.currentHeadingLevel = 0;
            // Add the break itself to the next chunk, or handle as separator.
            // For simplicity, we'll ensure it starts a new chunk if buffer isn't empty,
            // and then add it to the (potentially new) current buffer.
            appendNodeTextWithPotentialSplitting("---\n\n");
            super.visit(thematicBreak);
        }

        // This method appends text and handles splitting if the buffer gets too large
        // or if the incoming text itself is too large.
        private void appendNodeTextWithPotentialSplitting(String text) {
            if (text == null || text.isBlank()) {
                return;
            }

            // If current buffer + new text exceeds max size, try to finalize current buffer
            if (currentChunkBuffer.length() + text.length() > maxCharsPerChunk && currentChunkBuffer.length() > 0) {
                finalizeChunk();
            }

            // If the new text itself is larger than maxCharsPerChunk, split it
            String remainingText = text;
            while (remainingText.length() > maxCharsPerChunk) {
                int splitPoint = findSplitPoint(remainingText, maxCharsPerChunk);
                String currentTextSegment = remainingText.substring(0, splitPoint);

                // If buffer is not empty and adding this segment would exceed, finalize buffer first
                if (currentChunkBuffer.length() > 0 && currentChunkBuffer.length() + currentTextSegment.length() > maxCharsPerChunk) {
                    finalizeChunk();
                }

                // Append to buffer, and if buffer now full, finalize
                currentChunkBuffer.append(currentTextSegment);
                if(currentChunkBuffer.length() >= maxCharsPerChunk) {
                    finalizeChunk();
                }

                remainingText = remainingText.substring(splitPoint);
            }

            // Append any leftover part of the text (or the whole text if it wasn't too long)
            if (!remainingText.isBlank()) {
                if (currentChunkBuffer.length() + remainingText.length() > maxCharsPerChunk && currentChunkBuffer.length() > 0) {
                    finalizeChunk();
                }
                currentChunkBuffer.append(remainingText);
            }

            // If buffer exceeds max size after appending, finalize it.
            if (currentChunkBuffer.length() >= maxCharsPerChunk) {
                finalizeChunk();
            }
        }

        private int findSplitPoint(String text, int desiredLength) {
            if (text.length() <= desiredLength) {
                return text.length();
            }
            // Try to split at sentence endings first, backwards from desiredLength
            String sub = text.substring(0, desiredLength);
            Matcher matcher = SENTENCE_END_PATTERN.matcher(sub);
            int lastSentenceEnd = -1;
            while (matcher.find()) {
                lastSentenceEnd = matcher.end();
            }
            if (lastSentenceEnd > 0) return lastSentenceEnd;

            // Try to split at last space
            int lastSpace = sub.lastIndexOf(' ');
            if (lastSpace > 0) return lastSpace + 1; // Include the space for potential rejoining or trim later

            // If no good split point, just split at desiredLength
            return desiredLength;
        }

        // Default visit for other nodes: try to render their text content
        @Override
        protected void visitChildren(Node parent) {
            if (parent instanceof Paragraph || parent instanceof Heading ||
                    parent instanceof ListItem || parent instanceof BlockQuote ||
                    parent instanceof FencedCodeBlock || parent instanceof ThematicBreak) {
                // These are handled by their specific visit methods or appendNodeTextWithPotentialSplitting
                // super.visitChildren(parent) would visit text nodes within them, which might lead to double processing
                // if not careful. CommonMark's TextContentRenderer handles children, so we rely on that.
                // The current approach is to render the whole block element and then append/split.
            } else {
                // For other structural elements not explicitly handled,
                // we can try to render their content if they are containers.
                // However, most relevant block elements are covered.
                // If there's a need to extract text from custom or less common nodes,
                // specific visit methods for them would be required.
                super.visitChildren(parent);
            }
        }

        @Override
        public void visit(Text textNode) {
            // Text nodes are typically children of block elements like Paragraph.
            // The current strategy is to render the parent block element (e.g., Paragraph)
            // as a whole using TextContentRenderer in the parent's visit method.
            // This avoids processing text fragments individually here, which could
            // lead to less coherent chunks if not managed carefully with the buffer.
            // If a more granular, text-node-level appending is needed,
            // the appendNodeTextWithPotentialSplitting logic would be called here.
            // For now, this method is a no-op as parent nodes handle rendering.
            super.visit(textNode);
        }
    }
}
