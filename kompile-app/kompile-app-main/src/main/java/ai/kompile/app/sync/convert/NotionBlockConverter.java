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

package ai.kompile.app.sync.convert;

import com.vladsch.flexmark.ast.*;
import com.vladsch.flexmark.ext.tables.TableBlock;
import com.vladsch.flexmark.ext.tables.TableBody;
import com.vladsch.flexmark.ext.tables.TableCell;
import com.vladsch.flexmark.ext.tables.TableHead;
import com.vladsch.flexmark.ext.tables.TableRow;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Converts between Markdown text and Notion block JSON structures.
 *
 * Uses flexmark-java for parsing Markdown into an AST, then walks the AST to
 * produce Notion block objects. The reverse path (blocks to Markdown) uses
 * string building from the block type and rich text content.
 */
@Component
public class NotionBlockConverter {

    private final Parser parser;

    public NotionBlockConverter() {
        MutableDataSet options = new MutableDataSet();
        options.set(Parser.EXTENSIONS, List.of(TablesExtension.create()));
        this.parser = Parser.builder(options).build();
    }

    // ── Markdown -> Notion Blocks ───────────────────────────────────────

    /**
     * Converts a Markdown string to a list of Notion block objects (as Maps).
     * Each map is ready to be serialized as JSON for the Notion API.
     */
    public List<Map<String, Object>> markdownToBlocks(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }

        Document doc = parser.parse(markdown);
        List<Map<String, Object>> blocks = new ArrayList<>();
        Node child = doc.getFirstChild();
        while (child != null) {
            Map<String, Object> block = nodeToBlock(child);
            if (block != null) {
                blocks.add(block);
            }
            child = child.getNext();
        }
        return blocks;
    }

    private Map<String, Object> nodeToBlock(Node node) {
        if (node instanceof Heading heading) {
            int level = Math.min(heading.getLevel(), 3);
            String type = "heading_" + level;
            return Map.of(
                    "object", "block",
                    "type", type,
                    type, Map.of("rich_text", richTextFromInlines(heading))
            );
        } else if (node instanceof Paragraph para) {
            return Map.of(
                    "object", "block",
                    "type", "paragraph",
                    "paragraph", Map.of("rich_text", richTextFromInlines(para))
            );
        } else if (node instanceof FencedCodeBlock code) {
            String language = code.getInfo().toString().trim();
            Map<String, Object> codeBody = new LinkedHashMap<>();
            codeBody.put("rich_text", List.of(textObject(code.getContentChars().toString().stripTrailing())));
            if (!language.isEmpty()) {
                codeBody.put("language", mapLanguage(language));
            }
            return Map.of("object", "block", "type", "code", "code", codeBody);
        } else if (node instanceof IndentedCodeBlock code) {
            return Map.of(
                    "object", "block",
                    "type", "code",
                    "code", Map.of("rich_text", List.of(textObject(code.getContentChars().toString().stripTrailing())))
            );
        } else if (node instanceof BlockQuote quote) {
            // Collect all paragraph text inside the blockquote
            StringBuilder sb = new StringBuilder();
            Node qChild = quote.getFirstChild();
            while (qChild != null) {
                sb.append(qChild.getChars().toString().replaceAll("^>\\s?", "").trim());
                qChild = qChild.getNext();
                if (qChild != null) sb.append("\n");
            }
            return Map.of(
                    "object", "block",
                    "type", "quote",
                    "quote", Map.of("rich_text", List.of(textObject(sb.toString())))
            );
        } else if (node instanceof BulletList list) {
            // Return the first item; Notion handles lists as flat block sequences
            List<Map<String, Object>> items = new ArrayList<>();
            Node item = list.getFirstChild();
            while (item instanceof ListItem li) {
                items.add(Map.of(
                        "object", "block",
                        "type", "bulleted_list_item",
                        "bulleted_list_item", Map.of("rich_text", richTextFromInlines(li))
                ));
                item = item.getNext();
            }
            // Return first, caller should handle but Notion API accepts flat list
            return items.isEmpty() ? null : items.get(0);
        } else if (node instanceof OrderedList list) {
            Node item = list.getFirstChild();
            if (item instanceof ListItem li) {
                return Map.of(
                        "object", "block",
                        "type", "numbered_list_item",
                        "numbered_list_item", Map.of("rich_text", richTextFromInlines(li))
                );
            }
        } else if (node instanceof ThematicBreak) {
            return Map.of("object", "block", "type", "divider", "divider", Map.of());
        }
        // Fallback: render as paragraph with raw text
        String text = node.getChars().toString().trim();
        if (!text.isEmpty()) {
            return Map.of(
                    "object", "block",
                    "type", "paragraph",
                    "paragraph", Map.of("rich_text", List.of(textObject(text)))
            );
        }
        return null;
    }

    private List<Map<String, Object>> richTextFromInlines(Node parent) {
        List<Map<String, Object>> richText = new ArrayList<>();
        // Simple approach: extract plain text content
        String text = extractPlainText(parent).trim();
        if (!text.isEmpty()) {
            richText.add(textObject(text));
        }
        return richText;
    }

    private String extractPlainText(Node node) {
        StringBuilder sb = new StringBuilder();
        Node child = node.getFirstChild();
        while (child != null) {
            if (child instanceof Text text) {
                sb.append(text.getChars());
            } else if (child instanceof SoftLineBreak) {
                sb.append(" ");
            } else if (child instanceof HardLineBreak) {
                sb.append("\n");
            } else if (child instanceof Code code) {
                sb.append(code.getText());
            } else if (child instanceof Emphasis em) {
                sb.append(extractPlainText(em));
            } else if (child instanceof StrongEmphasis strong) {
                sb.append(extractPlainText(strong));
            } else if (child instanceof Paragraph para) {
                sb.append(extractPlainText(para));
            } else {
                sb.append(child.getChars());
            }
            child = child.getNext();
        }
        if (sb.isEmpty()) {
            // Leaf node with no children: use chars directly
            sb.append(node.getChars().toString().replaceAll("^[#*>\\-+0-9.]+\\s*", ""));
        }
        return sb.toString();
    }

    private Map<String, Object> textObject(String content) {
        return Map.of(
                "type", "text",
                "text", Map.of("content", content)
        );
    }

    private String mapLanguage(String lang) {
        // Notion requires specific language identifiers
        return switch (lang.toLowerCase()) {
            case "js", "javascript" -> "javascript";
            case "ts", "typescript" -> "typescript";
            case "py", "python" -> "python";
            case "rb", "ruby" -> "ruby";
            case "sh", "bash", "shell" -> "bash";
            case "yml" -> "yaml";
            case "md" -> "markdown";
            case "dockerfile" -> "docker";
            default -> lang.toLowerCase();
        };
    }

    // ── Notion Blocks -> Markdown ───────────────────────────────────────

    /**
     * Converts a list of Notion block objects (as Maps) back to Markdown text.
     */
    @SuppressWarnings("unchecked")
    public String blocksToMarkdown(List<Map<String, Object>> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> block : blocks) {
            String type = (String) block.get("type");
            if (type == null) continue;

            Map<String, Object> body = (Map<String, Object>) block.get(type);
            if (body == null) continue;

            switch (type) {
                case "paragraph" -> {
                    sb.append(extractRichText(body)).append("\n\n");
                }
                case "heading_1" -> {
                    sb.append("# ").append(extractRichText(body)).append("\n\n");
                }
                case "heading_2" -> {
                    sb.append("## ").append(extractRichText(body)).append("\n\n");
                }
                case "heading_3" -> {
                    sb.append("### ").append(extractRichText(body)).append("\n\n");
                }
                case "bulleted_list_item" -> {
                    sb.append("- ").append(extractRichText(body)).append("\n");
                }
                case "numbered_list_item" -> {
                    sb.append("1. ").append(extractRichText(body)).append("\n");
                }
                case "to_do" -> {
                    boolean checked = Boolean.TRUE.equals(body.get("checked"));
                    sb.append(checked ? "- [x] " : "- [ ] ");
                    sb.append(extractRichText(body)).append("\n");
                }
                case "code" -> {
                    String lang = body.containsKey("language") ? (String) body.get("language") : "";
                    sb.append("```").append(lang).append("\n");
                    sb.append(extractRichText(body)).append("\n");
                    sb.append("```\n\n");
                }
                case "quote" -> {
                    String text = extractRichText(body);
                    for (String line : text.split("\n")) {
                        sb.append("> ").append(line).append("\n");
                    }
                    sb.append("\n");
                }
                case "callout" -> {
                    sb.append("> ").append(extractRichText(body)).append("\n\n");
                }
                case "divider" -> {
                    sb.append("---\n\n");
                }
                case "image" -> {
                    String url = extractImageUrl(body);
                    if (url != null) {
                        sb.append("![image](").append(url).append(")\n\n");
                    }
                }
                case "bookmark" -> {
                    String url = (String) body.get("url");
                    if (url != null) {
                        sb.append("[").append(url).append("](").append(url).append(")\n\n");
                    }
                }
                default -> {
                    // Unknown block type: try to extract rich text
                    String text = extractRichText(body);
                    if (!text.isEmpty()) {
                        sb.append(text).append("\n\n");
                    }
                }
            }
        }
        return sb.toString().stripTrailing();
    }

    @SuppressWarnings("unchecked")
    private String extractRichText(Map<String, Object> body) {
        Object rtObj = body.get("rich_text");
        if (rtObj == null) return "";

        List<Map<String, Object>> richTextList;
        if (rtObj instanceof List<?> list) {
            richTextList = (List<Map<String, Object>>) list;
        } else {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> rt : richTextList) {
            String plainText = (String) rt.get("plain_text");
            if (plainText == null) {
                // Fallback: try text.content
                Object textObj = rt.get("text");
                if (textObj instanceof Map<?, ?> textMap) {
                    plainText = (String) textMap.get("content");
                }
            }
            if (plainText != null) {
                sb.append(plainText);
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private String extractImageUrl(Map<String, Object> body) {
        String imageType = (String) body.get("type");
        if (imageType != null) {
            Map<String, Object> imageData = (Map<String, Object>) body.get(imageType);
            if (imageData != null) {
                return (String) imageData.get("url");
            }
        }
        return null;
    }
}
