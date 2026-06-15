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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.loader.gdocs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GoogleDocsParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private GoogleDocsParser parser;

    @BeforeEach
    void setUp() {
        parser = new GoogleDocsParser();
    }

    // ── parse() — basic ────────────────────────────────────────────────

    @Test
    void parsesDocumentIdAndTitle() {
        ObjectNode doc = docsDocument("doc123", "My Document");
        addParagraph(doc, "NORMAL_TEXT", "Hello world\n");

        Document result = parser.parse(doc, driveMeta("doc123", "My Document"));

        assertEquals("doc123", result.getMetadata().get("gdocs.documentId"));
        assertEquals("My Document", result.getMetadata().get("gdocs.title"));
        assertEquals("gdocs://documents/doc123", result.getMetadata().get("source"));
        assertEquals("gdocs", result.getMetadata().get("source_type"));
    }

    @Test
    void parsesPlainTextParagraph() {
        ObjectNode doc = docsDocument("doc1", "Test");
        addParagraph(doc, "NORMAL_TEXT", "This is a paragraph.\n");

        Document result = parser.parse(doc, driveMeta("doc1", "Test"));

        assertTrue(result.getText().contains("This is a paragraph."));
    }

    @Test
    void parsesHeading1() {
        ObjectNode doc = docsDocument("doc1", "Test");
        addParagraph(doc, "HEADING_1", "Introduction\n");
        addParagraph(doc, "NORMAL_TEXT", "Body text\n");

        Document result = parser.parse(doc, driveMeta("doc1", "Test"));

        assertTrue(result.getText().contains("# Introduction"));
        assertTrue(result.getText().contains("Body text"));
    }

    @Test
    void parsesHeading2() {
        ObjectNode doc = docsDocument("doc1", "Test");
        addParagraph(doc, "HEADING_2", "Section\n");

        Document result = parser.parse(doc, driveMeta("doc1", "Test"));

        assertTrue(result.getText().contains("## Section"));
    }

    @Test
    void parsesHeading3() {
        ObjectNode doc = docsDocument("doc1", "Test");
        addParagraph(doc, "HEADING_3", "Subsection\n");

        Document result = parser.parse(doc, driveMeta("doc1", "Test"));

        assertTrue(result.getText().contains("### Subsection"));
    }

    @Test
    void parsesTitleStyle() {
        ObjectNode doc = docsDocument("doc1", "Test");
        addParagraph(doc, "TITLE", "Document Title\n");

        Document result = parser.parse(doc, driveMeta("doc1", "Test"));

        assertTrue(result.getText().contains("# Document Title"));
    }

    @Test
    void parsesSubtitleStyle() {
        ObjectNode doc = docsDocument("doc1", "Test");
        addParagraph(doc, "SUBTITLE", "A Subtitle\n");

        Document result = parser.parse(doc, driveMeta("doc1", "Test"));

        assertTrue(result.getText().contains("## A Subtitle"));
    }

    // ── Text styling ───────────────────────────────────────────────────

    @Test
    void parsesBoldText() {
        ObjectNode doc = docsDocument("doc1", "Test");
        addStyledParagraph(doc, "NORMAL_TEXT", "bold text\n", true, false);

        Document result = parser.parse(doc, driveMeta("doc1", "Test"));

        assertTrue(result.getText().contains("**bold text**"));
    }

    @Test
    void parsesItalicText() {
        ObjectNode doc = docsDocument("doc1", "Test");
        addStyledParagraph(doc, "NORMAL_TEXT", "italic text\n", false, true);

        Document result = parser.parse(doc, driveMeta("doc1", "Test"));

        assertTrue(result.getText().contains("*italic text*"));
    }

    @Test
    void parsesBoldItalicText() {
        ObjectNode doc = docsDocument("doc1", "Test");
        addStyledParagraph(doc, "NORMAL_TEXT", "bold italic\n", true, true);

        Document result = parser.parse(doc, driveMeta("doc1", "Test"));

        assertTrue(result.getText().contains("***bold italic***"));
    }

    // ── Tables ─────────────────────────────────────────────────────────

    @Test
    void parsesTable() {
        ObjectNode doc = docsDocument("doc1", "Test");
        addTable(doc, new String[][]{
                {"Name", "Age"},
                {"Alice", "30"},
                {"Bob", "25"}
        });

        Document result = parser.parse(doc, driveMeta("doc1", "Test"));

        assertTrue(result.getText().contains("| Name"));
        assertTrue(result.getText().contains("| Age"));
        assertTrue(result.getText().contains("| ---"));
        assertTrue(result.getText().contains("| Alice"));
        assertTrue(result.getText().contains("| Bob"));
    }

    @Test
    void countsTablesInMetadata() {
        ObjectNode doc = docsDocument("doc1", "Test");
        addTable(doc, new String[][]{{"A", "B"}, {"1", "2"}});
        addTable(doc, new String[][]{{"X", "Y"}, {"3", "4"}});

        Document result = parser.parse(doc, driveMeta("doc1", "Test"));

        assertEquals(2, result.getMetadata().get("gdocs.tableCount"));
    }

    // ── Lists ──────────────────────────────────────────────────────────

    @Test
    void parsesListItems() {
        ObjectNode doc = docsDocument("doc1", "Test");
        addListItem(doc, "First item\n", 0);
        addListItem(doc, "Second item\n", 0);
        addListItem(doc, "Nested item\n", 1);

        Document result = parser.parse(doc, driveMeta("doc1", "Test"));

        assertTrue(result.getText().contains("- First item"));
        assertTrue(result.getText().contains("- Second item"));
        assertTrue(result.getText().contains("  - Nested item"));
    }

    @Test
    void countsListItemsInMetadata() {
        ObjectNode doc = docsDocument("doc1", "Test");
        addListItem(doc, "Item 1\n", 0);
        addListItem(doc, "Item 2\n", 0);
        addListItem(doc, "Item 3\n", 1);

        Document result = parser.parse(doc, driveMeta("doc1", "Test"));

        assertEquals(3, result.getMetadata().get("gdocs.listItemCount"));
    }

    // ── Inline images ──────────────────────────────────────────────────

    @Test
    void parsesInlineImage() {
        ObjectNode doc = docsDocument("doc1", "Test");
        addInlineImage(doc, "kix.img001");

        Document result = parser.parse(doc, driveMeta("doc1", "Test"));

        assertTrue(result.getText().contains("[Image: kix.img001]"));
    }

    @Test
    void countsInlineImagesInMetadata() {
        ObjectNode doc = docsDocument("doc1", "Test");
        addInlineImage(doc, "img001");
        addInlineImage(doc, "img002");

        Document result = parser.parse(doc, driveMeta("doc1", "Test"));

        assertEquals(2, result.getMetadata().get("gdocs.imageCount"));
    }

    // ── Footnotes ──────────────────────────────────────────────────────

    @Test
    void parsesFootnotes() {
        ObjectNode doc = docsDocument("doc1", "Test");
        addParagraph(doc, "NORMAL_TEXT", "Text with footnote\n");
        addFootnote(doc, "fn1", "This is a footnote.");

        Document result = parser.parse(doc, driveMeta("doc1", "Test"));

        assertTrue(result.getText().contains("Footnotes"));
        assertTrue(result.getText().contains("[^fn1]: This is a footnote."));
    }

    // ── Drive metadata ─────────────────────────────────────────────────

    @Test
    void populatesDriveMetadata() {
        ObjectNode doc = docsDocument("doc1", "Test");
        addParagraph(doc, "NORMAL_TEXT", "Body\n");

        ObjectNode drive = driveMeta("doc1", "Test");
        drive.put("modifiedTime", "2024-01-15T10:30:00Z");
        drive.put("createdTime", "2024-01-01T00:00:00Z");
        drive.put("webViewLink", "https://docs.google.com/document/d/doc1/edit");
        drive.put("version", "42");

        ArrayNode owners = MAPPER.createArrayNode();
        ObjectNode owner = MAPPER.createObjectNode();
        owner.put("displayName", "Alice Smith");
        owner.put("emailAddress", "alice@example.com");
        owners.add(owner);
        drive.set("owners", owners);

        ObjectNode lastModifier = MAPPER.createObjectNode();
        lastModifier.put("displayName", "Bob Jones");
        lastModifier.put("emailAddress", "bob@example.com");
        drive.set("lastModifyingUser", lastModifier);

        Document result = parser.parse(doc, drive);

        assertEquals("Test", result.getMetadata().get("gdocs.fileName"));
        assertEquals("2024-01-15T10:30:00Z", result.getMetadata().get("gdocs.modifiedTime"));
        assertEquals("2024-01-01T00:00:00Z", result.getMetadata().get("gdocs.createdTime"));
        assertEquals("https://docs.google.com/document/d/doc1/edit", result.getMetadata().get("gdocs.webViewLink"));
        assertEquals("42", result.getMetadata().get("gdocs.version"));
        assertEquals("Alice Smith", result.getMetadata().get("gdocs.owner"));
        assertEquals("alice@example.com", result.getMetadata().get("gdocs.ownerEmail"));
        assertEquals("Bob Jones", result.getMetadata().get("gdocs.lastModifiedBy"));
        assertEquals("bob@example.com", result.getMetadata().get("gdocs.lastModifiedByEmail"));
    }

    @Test
    void populatesParentFolderId() {
        ObjectNode doc = docsDocument("doc1", "Test");
        addParagraph(doc, "NORMAL_TEXT", "Body\n");

        ObjectNode drive = driveMeta("doc1", "Test");
        ArrayNode parents = MAPPER.createArrayNode();
        parents.add("folder_abc123");
        drive.set("parents", parents);

        Document result = parser.parse(doc, drive);

        assertEquals("folder_abc123", result.getMetadata().get("gdocs.folderId"));
    }

    @Test
    void noFolderIdWhenParentsAbsent() {
        ObjectNode doc = docsDocument("doc1", "Test");
        addParagraph(doc, "NORMAL_TEXT", "Body\n");

        ObjectNode drive = driveMeta("doc1", "Test");
        // No parents array set

        Document result = parser.parse(doc, drive);

        assertNull(result.getMetadata().get("gdocs.folderId"));
    }

    @Test
    void handlesNullDriveMetadata() {
        ObjectNode doc = docsDocument("doc1", "Test");
        addParagraph(doc, "NORMAL_TEXT", "Body\n");

        Document result = parser.parse(doc, null);

        assertEquals("doc1", result.getMetadata().get("gdocs.documentId"));
        assertNull(result.getMetadata().get("gdocs.owner"));
    }

    // ── parseFromPlainText() ───────────────────────────────────────────

    @Test
    void parsesFromPlainText() {
        ObjectNode drive = driveMeta("doc1", "Plain Doc");
        Document result = parser.parseFromPlainText("Hello plain text", drive);

        assertEquals("Hello plain text", result.getText());
        assertEquals("doc1", result.getMetadata().get("gdocs.documentId"));
        assertEquals("Plain Doc", result.getMetadata().get("gdocs.title"));
        assertEquals("plaintext_fallback", result.getMetadata().get("gdocs.parseMode"));
    }

    @Test
    void parsesFromNullPlainText() {
        ObjectNode drive = driveMeta("doc1", "Empty");
        Document result = parser.parseFromPlainText(null, drive);

        assertEquals("", result.getText());
    }

    // ── extractBody edge cases ─────────────────────────────────────────

    @Test
    void extractBodyHandlesNullBody() {
        assertEquals("", parser.extractBody(null));
    }

    @Test
    void extractBodyHandlesMissingContent() {
        ObjectNode body = MAPPER.createObjectNode();
        assertEquals("", parser.extractBody(body));
    }

    // ── extractFootnotes edge cases ────────────────────────────────────

    @Test
    void extractFootnotesHandlesNull() {
        assertEquals("", parser.extractFootnotes(null));
    }

    @Test
    void extractFootnotesHandlesEmptyObject() {
        assertEquals("", parser.extractFootnotes(MAPPER.createObjectNode()));
    }

    // ── countTables / countInlineImages / countListItems edge cases ────

    @Test
    void countTablesHandlesNull() {
        assertEquals(0, parser.countTables(null));
    }

    @Test
    void countInlineImagesHandlesNull() {
        assertEquals(0, parser.countInlineImages(null));
    }

    @Test
    void countListItemsHandlesNull() {
        assertEquals(0, parser.countListItems(null));
    }

    // ── Section breaks ─────────────────────────────────────────────────

    @Test
    void parsesSectionBreak() {
        ObjectNode doc = docsDocument("doc1", "Test");
        addParagraph(doc, "NORMAL_TEXT", "Before\n");
        addSectionBreak(doc);
        addParagraph(doc, "NORMAL_TEXT", "After\n");

        Document result = parser.parse(doc, driveMeta("doc1", "Test"));

        assertTrue(result.getText().contains("Before"));
        assertTrue(result.getText().contains("---"));
        assertTrue(result.getText().contains("After"));
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private ObjectNode docsDocument(String documentId, String title) {
        ObjectNode doc = MAPPER.createObjectNode();
        doc.put("documentId", documentId);
        doc.put("title", title);
        ObjectNode body = MAPPER.createObjectNode();
        body.set("content", MAPPER.createArrayNode());
        doc.set("body", body);
        return doc;
    }

    private ObjectNode driveMeta(String fileId, String name) {
        ObjectNode meta = MAPPER.createObjectNode();
        meta.put("id", fileId);
        meta.put("name", name);
        return meta;
    }

    private void addParagraph(ObjectNode doc, String namedStyle, String text) {
        ArrayNode content = (ArrayNode) doc.path("body").get("content");
        ObjectNode element = MAPPER.createObjectNode();
        ObjectNode paragraph = MAPPER.createObjectNode();

        ObjectNode style = MAPPER.createObjectNode();
        style.put("namedStyleType", namedStyle);
        paragraph.set("paragraphStyle", style);

        ArrayNode elements = MAPPER.createArrayNode();
        ObjectNode textElement = MAPPER.createObjectNode();
        ObjectNode textRun = MAPPER.createObjectNode();
        textRun.put("content", text);
        textRun.set("textStyle", MAPPER.createObjectNode());
        textElement.set("textRun", textRun);
        elements.add(textElement);
        paragraph.set("elements", elements);

        element.set("paragraph", paragraph);
        content.add(element);
    }

    private void addStyledParagraph(ObjectNode doc, String namedStyle, String text,
                                     boolean bold, boolean italic) {
        ArrayNode content = (ArrayNode) doc.path("body").get("content");
        ObjectNode element = MAPPER.createObjectNode();
        ObjectNode paragraph = MAPPER.createObjectNode();

        ObjectNode style = MAPPER.createObjectNode();
        style.put("namedStyleType", namedStyle);
        paragraph.set("paragraphStyle", style);

        ArrayNode elements = MAPPER.createArrayNode();
        ObjectNode textElement = MAPPER.createObjectNode();
        ObjectNode textRun = MAPPER.createObjectNode();
        textRun.put("content", text);
        ObjectNode textStyle = MAPPER.createObjectNode();
        textStyle.put("bold", bold);
        textStyle.put("italic", italic);
        textRun.set("textStyle", textStyle);
        textElement.set("textRun", textRun);
        elements.add(textElement);
        paragraph.set("elements", elements);

        element.set("paragraph", paragraph);
        content.add(element);
    }

    private void addTable(ObjectNode doc, String[][] data) {
        ArrayNode content = (ArrayNode) doc.path("body").get("content");
        ObjectNode element = MAPPER.createObjectNode();
        ObjectNode table = MAPPER.createObjectNode();
        ArrayNode tableRows = MAPPER.createArrayNode();

        for (String[] row : data) {
            ObjectNode tableRow = MAPPER.createObjectNode();
            ArrayNode tableCells = MAPPER.createArrayNode();
            for (String cell : row) {
                ObjectNode tableCell = MAPPER.createObjectNode();
                ArrayNode cellContent = MAPPER.createArrayNode();
                ObjectNode paragraph = MAPPER.createObjectNode();
                ArrayNode elements = MAPPER.createArrayNode();
                ObjectNode textElement = MAPPER.createObjectNode();
                ObjectNode textRun = MAPPER.createObjectNode();
                textRun.put("content", cell);
                textElement.set("textRun", textRun);
                elements.add(textElement);
                paragraph.set("elements", elements);
                ObjectNode paraWrapper = MAPPER.createObjectNode();
                paraWrapper.set("paragraph", paragraph);
                cellContent.add(paraWrapper);
                tableCell.set("content", cellContent);
                tableCells.add(tableCell);
            }
            tableRow.set("tableCells", tableCells);
            tableRows.add(tableRow);
        }

        table.set("tableRows", tableRows);
        element.set("table", table);
        content.add(element);
    }

    private void addListItem(ObjectNode doc, String text, int nestingLevel) {
        ArrayNode content = (ArrayNode) doc.path("body").get("content");
        ObjectNode element = MAPPER.createObjectNode();
        ObjectNode paragraph = MAPPER.createObjectNode();

        ObjectNode style = MAPPER.createObjectNode();
        style.put("namedStyleType", "NORMAL_TEXT");
        paragraph.set("paragraphStyle", style);

        ObjectNode bullet = MAPPER.createObjectNode();
        bullet.put("nestingLevel", nestingLevel);
        paragraph.set("bullet", bullet);

        ArrayNode elements = MAPPER.createArrayNode();
        ObjectNode textElement = MAPPER.createObjectNode();
        ObjectNode textRun = MAPPER.createObjectNode();
        textRun.put("content", text);
        textRun.set("textStyle", MAPPER.createObjectNode());
        textElement.set("textRun", textRun);
        elements.add(textElement);
        paragraph.set("elements", elements);

        element.set("paragraph", paragraph);
        content.add(element);
    }

    private void addInlineImage(ObjectNode doc, String objectId) {
        ArrayNode content = (ArrayNode) doc.path("body").get("content");
        ObjectNode element = MAPPER.createObjectNode();
        ObjectNode paragraph = MAPPER.createObjectNode();

        ObjectNode style = MAPPER.createObjectNode();
        style.put("namedStyleType", "NORMAL_TEXT");
        paragraph.set("paragraphStyle", style);

        ArrayNode elements = MAPPER.createArrayNode();
        ObjectNode imgElement = MAPPER.createObjectNode();
        ObjectNode inlineObj = MAPPER.createObjectNode();
        inlineObj.put("inlineObjectId", objectId);
        imgElement.set("inlineObjectElement", inlineObj);
        elements.add(imgElement);
        paragraph.set("elements", elements);

        element.set("paragraph", paragraph);
        content.add(element);
    }

    private void addFootnote(ObjectNode doc, String footnoteId, String text) {
        ObjectNode footnotes = (ObjectNode) doc.get("footnotes");
        if (footnotes == null) {
            footnotes = MAPPER.createObjectNode();
            doc.set("footnotes", footnotes);
        }

        ObjectNode footnote = MAPPER.createObjectNode();
        ArrayNode contentArray = MAPPER.createArrayNode();
        ObjectNode element = MAPPER.createObjectNode();
        ObjectNode paragraph = MAPPER.createObjectNode();
        ArrayNode elements = MAPPER.createArrayNode();
        ObjectNode textElement = MAPPER.createObjectNode();
        ObjectNode textRun = MAPPER.createObjectNode();
        textRun.put("content", text);
        textElement.set("textRun", textRun);
        elements.add(textElement);
        paragraph.set("elements", elements);
        element.set("paragraph", paragraph);
        contentArray.add(element);
        footnote.set("content", contentArray);

        footnotes.set(footnoteId, footnote);
    }

    private void addSectionBreak(ObjectNode doc) {
        ArrayNode content = (ArrayNode) doc.path("body").get("content");
        ObjectNode element = MAPPER.createObjectNode();
        element.set("sectionBreak", MAPPER.createObjectNode());
        content.add(element);
    }

    private void addParagraphWithLink(ObjectNode doc, String namedStyle, String text, String url) {
        ArrayNode content = (ArrayNode) doc.path("body").get("content");
        ObjectNode element = MAPPER.createObjectNode();
        ObjectNode paragraph = MAPPER.createObjectNode();

        ObjectNode style = MAPPER.createObjectNode();
        style.put("namedStyleType", namedStyle);
        paragraph.set("paragraphStyle", style);

        ArrayNode elements = MAPPER.createArrayNode();
        ObjectNode textElement = MAPPER.createObjectNode();
        ObjectNode textRun = MAPPER.createObjectNode();
        textRun.put("content", text);
        ObjectNode textStyle = MAPPER.createObjectNode();
        ObjectNode link = MAPPER.createObjectNode();
        link.put("url", url);
        textStyle.set("link", link);
        textRun.set("textStyle", textStyle);
        textElement.set("textRun", textRun);
        elements.add(textElement);
        paragraph.set("elements", elements);

        element.set("paragraph", paragraph);
        content.add(element);
    }

    // ── Heading metadata tests ──────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void parsePopulatesHeadingsMetadata() {
        ObjectNode doc = docsDocument("doc1", "Test");
        addParagraph(doc, "HEADING_1", "Chapter One\n");
        addParagraph(doc, "NORMAL_TEXT", "Some body text\n");
        addParagraph(doc, "HEADING_2", "Section 1.1\n");

        Document result = parser.parse(doc, driveMeta("doc1", "Test"));

        List<Map<String, String>> headings = (List<Map<String, String>>) result.getMetadata().get("gdocs.headings");
        assertNotNull(headings, "gdocs.headings should be populated");
        assertEquals(2, headings.size());
        assertEquals("Chapter One", headings.get(0).get("text"));
        assertEquals("1", headings.get(0).get("level"));
        assertEquals("0", headings.get(0).get("index"));
        assertEquals("Section 1.1", headings.get(1).get("text"));
        assertEquals("2", headings.get(1).get("level"));
        assertEquals("1", headings.get(1).get("index"));
    }

    @Test
    void noHeadingsMetadataWhenNoHeadingsPresent() {
        ObjectNode doc = docsDocument("doc1", "Test");
        addParagraph(doc, "NORMAL_TEXT", "Just body text\n");

        Document result = parser.parse(doc, driveMeta("doc1", "Test"));

        assertNull(result.getMetadata().get("gdocs.headings"),
                "gdocs.headings should not be present when there are no headings");
    }

    // ── Link metadata tests ──────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void parsePopulatesLinksMetadata() {
        ObjectNode doc = docsDocument("doc1", "Test");
        addParagraphWithLink(doc, "NORMAL_TEXT", "Click here\n", "https://example.com");
        addParagraphWithLink(doc, "NORMAL_TEXT", "Kompile\n", "https://kompile.ai");

        Document result = parser.parse(doc, driveMeta("doc1", "Test"));

        List<Map<String, String>> links = (List<Map<String, String>>) result.getMetadata().get("gdocs.links");
        assertNotNull(links, "gdocs.links should be populated");
        assertEquals(2, links.size());
        assertEquals("https://example.com", links.get(0).get("url"));
        assertEquals("Click here", links.get(0).get("text"));
        assertEquals("https://kompile.ai", links.get(1).get("url"));
    }

    @Test
    void noLinksMetadataWhenNoLinksPresent() {
        ObjectNode doc = docsDocument("doc1", "Test");
        addParagraph(doc, "NORMAL_TEXT", "Plain text with no links\n");

        Document result = parser.parse(doc, driveMeta("doc1", "Test"));

        assertNull(result.getMetadata().get("gdocs.links"),
                "gdocs.links should not be present when there are no links");
    }
}
