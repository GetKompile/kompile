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

package ai.kompile.loader.microsoft;

import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.table.TableCellGraphBuilder;
import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import com.healthmarketscience.jackcess.Database;
import com.healthmarketscience.jackcess.DatabaseBuilder;
import com.healthmarketscience.jackcess.Table;
import com.pff.PSTFile;
import com.pff.PSTFolder;
import com.pff.PSTMessage;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.sl.extractor.SlideShowExtractor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTDocument1;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;

@Component
public class MicrosoftOfficeLoaderImpl implements DocumentLoader {

    private static final Logger logger = LoggerFactory.getLogger(MicrosoftOfficeLoaderImpl.class);

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            "doc", "docx", "xls", "xlsx", "ppt", "pptx", "mdb", "accdb", "pst"
    );

    @Override
    public String getName() {
        return "Microsoft Office Loader";
    }

    @Override
    public boolean supports(DocumentSourceDescriptor sourceDescriptor) {
        if (sourceDescriptor.getType() != DocumentSourceDescriptor.SourceType.FILE) {
            return false;
        }

        String path = sourceDescriptor.getPathOrUrl() != null ? sourceDescriptor.getPathOrUrl().toLowerCase() : "";
        return SUPPORTED_EXTENSIONS.stream().anyMatch(path::endsWith);
    }

    @Override
    public List<Document> load(DocumentSourceDescriptor sourceDescriptor) throws Exception {
        if (sourceDescriptor.getType() != DocumentSourceDescriptor.SourceType.FILE) {
            throw new IllegalArgumentException("MicrosoftOfficeLoader currently only supports FILE sources.");
        }

        File file = new File(sourceDescriptor.getPathOrUrl());
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("File does not exist or is not a regular file: " + sourceDescriptor.getPathOrUrl());
        }

        String filename = file.getName().toLowerCase();

        try {
            if (filename.endsWith(".doc")) {
                return loadWordDoc(file);
            } else if (filename.endsWith(".docx")) {
                return loadWordDocx(file);
            } else if (filename.endsWith(".xls")) {
                return loadExcelXls(file);
            } else if (filename.endsWith(".xlsx")) {
                return loadExcelXlsx(file);
            } else if (filename.endsWith(".ppt")) {
                return loadPowerPointPpt(file);
            } else if (filename.endsWith(".pptx")) {
                return loadPowerPointPptx(file);
            } else if (filename.endsWith(".mdb") || filename.endsWith(".accdb")) {
                return loadAccessDatabase(file);
            } else if (filename.endsWith(".pst")) {
                return loadOutlookPst(file);
            }
        } catch (Exception e) {
            // Handle corrupted or invalid Office files gracefully
            String errorMessage = e.getMessage();
            logger.warn("Unable to parse Microsoft Office file '{}': {}. The file may be corrupted or in an unsupported format.",
                       file.getName(), errorMessage);

            // Return an error document so the caller knows what happened
            Document errorDoc = new Document("[Error: Unable to parse Microsoft Office file. The file may be corrupted, truncated, or password-protected.]");
            errorDoc.getMetadata().put("source", file.getAbsolutePath());
            errorDoc.getMetadata().put("fileName", file.getName());
            errorDoc.getMetadata().put("fileSize", file.length());
            errorDoc.getMetadata().put("lastModified", file.lastModified());
            errorDoc.getMetadata().put("loader", getName());
            errorDoc.getMetadata().put("parseError", true);
            errorDoc.getMetadata().put("errorMessage", errorMessage != null ? errorMessage : "Unknown error");
            return List.of(errorDoc);
        }

        throw new IllegalArgumentException("Unsupported file type: " + filename);
    }

    private List<Document> loadWordDoc(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             HWPFDocument document = new HWPFDocument(fis);
             WordExtractor extractor = new WordExtractor(document)) {

            String content = extractor.getText();
            Document springDoc = new Document(content);
            addMetadata(springDoc, file, "Microsoft Word Document (.doc)");
            return List.of(springDoc);
        }
    }

    private List<Document> loadWordDocx(File file) throws IOException {
        List<Document> documents = new ArrayList<>();

        try (FileInputStream fis = new FileInputStream(file);
             XWPFDocument document = new XWPFDocument(fis);
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {

            // Extract full text content
            String content = extractor.getText();
            Document textDoc = new Document(content);
            addMetadata(textDoc, file, "Microsoft Word Document (.docx)");
            textDoc.getMetadata().put("content_type", "text");

            // Extract OOXML CoreProperties (author, title, dates, etc.)
            extractDocxCoreProperties(document, textDoc);

            // Extract tracked changes (revisions)
            extractDocxTrackedChanges(document, textDoc);

            // Extract headings for graph sectioning
            extractDocxHeadings(document, textDoc);

            documents.add(textDoc);

            // Extract tables as separate documents
            List<org.apache.poi.xwpf.usermodel.XWPFTable> tables = document.getTables();
            for (int i = 0; i < tables.size(); i++) {
                Document tableDoc = extractWordTableAsDocument(tables.get(i), file, i);
                if (tableDoc != null) {
                    documents.add(tableDoc);
                }
            }
        }

        return documents;
    }

    /**
     * Extracts OOXML CoreProperties from a .docx document: author, title, subject,
     * keywords, description, creationDate, modificationDate, lastModifiedBy.
     */
    private void extractDocxCoreProperties(XWPFDocument document, Document doc) {
        try {
            var props = document.getProperties();
            if (props == null) return;

            var core = props.getCoreProperties();
            if (core == null) return;

            if (core.getCreator() != null && !core.getCreator().isBlank()) {
                doc.getMetadata().put("author", core.getCreator());
            }
            if (core.getTitle() != null && !core.getTitle().isBlank()) {
                doc.getMetadata().put("title", core.getTitle());
            }
            if (core.getSubject() != null && !core.getSubject().isBlank()) {
                doc.getMetadata().put("subject", core.getSubject());
            }
            if (core.getKeywords() != null && !core.getKeywords().isBlank()) {
                doc.getMetadata().put("keywords", core.getKeywords());
            }
            if (core.getDescription() != null && !core.getDescription().isBlank()) {
                doc.getMetadata().put("description", core.getDescription());
            }
            if (core.getLastModifiedByUser() != null && !core.getLastModifiedByUser().isBlank()) {
                doc.getMetadata().put("lastModifiedBy", core.getLastModifiedByUser());
            }

            // Timestamps
            if (core.getCreated() != null) {
                doc.getMetadata().put("creationDate", core.getCreated().toInstant().toString());
            }
            if (core.getModified() != null) {
                doc.getMetadata().put("modificationDate", core.getModified().toInstant().toString());
            }

            // Extended properties (application, company)
            var extProps = props.getExtendedProperties();
            if (extProps != null) {
                var ext = extProps.getUnderlyingProperties();
                if (ext.getApplication() != null && !ext.getApplication().isBlank()) {
                    doc.getMetadata().put("applicationName", ext.getApplication());
                }
                if (ext.getCompany() != null && !ext.getCompany().isBlank()) {
                    doc.getMetadata().put("company", ext.getCompany());
                }
            }

            // Custom properties
            var customProps = props.getCustomProperties();
            if (customProps != null && customProps.getUnderlyingProperties() != null) {
                var customList = customProps.getUnderlyingProperties().getPropertyList();
                if (customList != null && !customList.isEmpty()) {
                    Map<String, String> customs = new LinkedHashMap<>();
                    for (var prop : customList) {
                        String name = prop.getName();
                        String value = prop.getLpwstr() != null ? prop.getLpwstr() :
                                       prop.getFiletime() != null ? prop.getFiletime().toString() :
                                       String.valueOf(prop.getI4());
                        customs.put(name, value);
                    }
                    doc.getMetadata().put("office.customProperties", customs);
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to extract CoreProperties from .docx", e);
        }
    }

    /**
     * Extracts tracked changes (revisions) from a .docx document and stores them
     * as a JSON-serializable list in the "docx.trackedChanges" metadata key.
     * Each entry contains: author, date, changeType, text.
     */
    private void extractDocxTrackedChanges(XWPFDocument document, Document doc) {
        try {
            List<Map<String, String>> trackedChanges = new ArrayList<>();

            for (var para : document.getParagraphs()) {
                var ctp = para.getCTP();
                // Check for insertions
                for (var ins : ctp.getInsList()) {
                    Map<String, String> change = new LinkedHashMap<>();
                    change.put("changeType", "INSERTION");
                    if (ins.getAuthor() != null) change.put("author", ins.getAuthor());
                    if (ins.getDate() != null) change.put("date", ins.getDate().toString());
                    // Get the inserted text from run elements within the insertion
                    StringBuilder text = new StringBuilder();
                    for (var run : ins.getRList()) {
                        for (var t : run.getTList()) {
                            text.append(t.getStringValue());
                        }
                    }
                    change.put("text", text.toString());
                    trackedChanges.add(change);
                }

                // Check for deletions
                for (var del : ctp.getDelList()) {
                    Map<String, String> change = new LinkedHashMap<>();
                    change.put("changeType", "DELETION");
                    if (del.getAuthor() != null) change.put("author", del.getAuthor());
                    if (del.getDate() != null) change.put("date", del.getDate().toString());
                    StringBuilder text = new StringBuilder();
                    for (var run : del.getRList()) {
                        for (var delText : run.getDelTextList()) {
                            text.append(delText.getStringValue());
                        }
                    }
                    change.put("text", text.toString());
                    trackedChanges.add(change);
                }
            }

            if (!trackedChanges.isEmpty()) {
                doc.getMetadata().put("docx.trackedChanges", trackedChanges);
            }
        } catch (Exception e) {
            logger.warn("Failed to extract tracked changes from .docx", e);
        }
    }

    /**
     * Extracts heading paragraphs from a .docx document and stores them as a list
     * in the "docx.headings" metadata key. Each entry is a Map with keys: text, level,
     * paragraphIndex.  Style names "Heading1"..."Heading9" and their normalised
     * equivalents (e.g. "heading 1") are treated as headings.
     */
    private void extractDocxHeadings(XWPFDocument document, Document doc) {
        try {
            List<Map<String, String>> headings = new ArrayList<>();
            List<org.apache.poi.xwpf.usermodel.XWPFParagraph> paragraphs = document.getParagraphs();
            for (int idx = 0; idx < paragraphs.size(); idx++) {
                org.apache.poi.xwpf.usermodel.XWPFParagraph para = paragraphs.get(idx);
                String style = para.getStyle();
                if (style == null) continue;
                String styleLower = style.toLowerCase().replace(" ", "").replace("_", "");
                int level = 0;
                if (styleLower.startsWith("heading")) {
                    // Extract the numeric suffix, e.g. "heading1" -> 1
                    String suffix = styleLower.substring("heading".length());
                    if (!suffix.isEmpty()) {
                        try { level = Integer.parseInt(suffix); } catch (NumberFormatException ignored) {}
                    }
                    if (level == 0) level = 1; // treat bare "heading" as level 1
                }
                if (level > 0) {
                    String text = para.getText();
                    if (text == null || text.isBlank()) continue;
                    Map<String, String> entry = new LinkedHashMap<>();
                    entry.put("text", text.trim());
                    entry.put("level", String.valueOf(level));
                    entry.put("paragraphIndex", String.valueOf(idx));
                    headings.add(entry);
                }
            }
            if (!headings.isEmpty()) {
                doc.getMetadata().put("docx.headings", headings);
            }
        } catch (Exception e) {
            logger.warn("Failed to extract headings from .docx", e);
        }
    }

    /**
     * Extracts a Word table as a separate Document with markdown formatting.
     */
    private Document extractWordTableAsDocument(org.apache.poi.xwpf.usermodel.XWPFTable table, File file, int tableIndex) {
        List<org.apache.poi.xwpf.usermodel.XWPFTableRow> rows = table.getRows();
        if (rows.isEmpty()) {
            return null;
        }

        StringBuilder markdown = new StringBuilder();
        List<String> headers = new ArrayList<>();

        // Process header row
        org.apache.poi.xwpf.usermodel.XWPFTableRow headerRow = rows.get(0);
        markdown.append("|");
        for (org.apache.poi.xwpf.usermodel.XWPFTableCell cell : headerRow.getTableCells()) {
            String cellText = cell.getText().trim().replace("|", "\\|");
            headers.add(cellText);
            markdown.append(" ").append(cellText).append(" |");
        }
        markdown.append("\n|");

        // Separator row
        for (int i = 0; i < headers.size(); i++) {
            markdown.append("---|");
        }
        markdown.append("\n");

        // Process data rows
        for (int i = 1; i < rows.size(); i++) {
            markdown.append("|");
            for (org.apache.poi.xwpf.usermodel.XWPFTableCell cell : rows.get(i).getTableCells()) {
                String cellText = cell.getText().trim().replace("|", "\\|");
                // Replace newlines within cells with spaces
                cellText = cellText.replace("\n", " ").replace("\r", "");
                markdown.append(" ").append(cellText).append(" |");
            }
            markdown.append("\n");
        }

        // Generate summary for embedding
        String summary = String.format("Table %d with %d rows and %d columns. Columns: %s",
            tableIndex + 1, rows.size() - 1, headers.size(), String.join(", ", headers));

        // Create document with summary as main content (for embedding)
        Document doc = new Document(summary);
        addMetadata(doc, file, "Microsoft Word Table");
        doc.getMetadata().put("content_type", "table");
        doc.getMetadata().put("full_table_content", markdown.toString());
        doc.getMetadata().put("table_index", tableIndex);
        doc.getMetadata().put("table_row_count", rows.size() - 1); // Exclude header
        doc.getMetadata().put("table_column_count", headers.size());
        doc.getMetadata().put("table_headers", String.join(",", headers));
        doc.getMetadata().put("storage_type", "search");

        // Cell-level graph so Word tables get the same structured TABLE/CELL nodes as CSV/Excel.
        // With single-owner dedup in ContentTypeRouter, persistGraphJson owns the TABLE node when
        // this graph is present (no duplicate alongside the promote path).
        List<List<String>> tableRows = new ArrayList<>();
        for (org.apache.poi.xwpf.usermodel.XWPFTableRow r : rows) {
            List<String> cells = new ArrayList<>();
            for (org.apache.poi.xwpf.usermodel.XWPFTableCell c : r.getTableCells()) {
                String t = c.getText();
                cells.add(t != null ? t.trim().replace("\n", " ").replace("\r", "") : "");
            }
            tableRows.add(cells);
        }
        Graph tableGraph =
                new TableCellGraphBuilder()
                        .namespace("docx:" + file.getName() + "#" + tableIndex)
                        .tableName("Table " + (tableIndex + 1))
                        .rows(tableRows)
                        .firstRowIsHeader(true)
                        .build();
        if (!tableGraph.getEntities().isEmpty()) {
            doc.getMetadata().put(GraphConstants.META_TABLE_GRAPH,
                    TableCellGraphBuilder.toJson(tableGraph));
        }

        return doc;
    }

    private List<Document> loadExcelXls(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             HSSFWorkbook workbook = new HSSFWorkbook(fis)) {

            return extractExcelContent(workbook, file, "Microsoft Excel Spreadsheet (.xls)");
        }
    }

    private List<Document> loadExcelXlsx(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             XSSFWorkbook workbook = new XSSFWorkbook(fis)) {

            return extractExcelContent(workbook, file, "Microsoft Excel Spreadsheet (.xlsx)");
        }
    }

    private List<Document> extractExcelContent(Workbook workbook, File file, String docType) {
        List<Document> documents = new ArrayList<>();

        // Extract workbook-level core properties (author, title, keywords) once
        // so they can be stamped on every sheet document.
        Map<String, String> workbookProps = extractExcelCoreProperties(workbook);

        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            Sheet sheet = workbook.getSheetAt(i);
            StringBuilder content = new StringBuilder();
            content.append("Sheet: ").append(sheet.getSheetName()).append("\n\n");

            List<String> headerCells = new ArrayList<>();
            boolean firstRow = true;
            int rowCount = 0;
            for (Row row : sheet) {
                if (firstRow) {
                    // Capture the header row
                    for (Cell cell : row) {
                        String cellValue = getCellValueAsString(cell);
                        headerCells.add(cellValue);
                        if (!cellValue.trim().isEmpty()) {
                            content.append(cellValue).append("\t");
                        }
                    }
                    content.append("\n");
                    firstRow = false;
                } else {
                    for (Cell cell : row) {
                        String cellValue = getCellValueAsString(cell);
                        if (!cellValue.trim().isEmpty()) {
                            content.append(cellValue).append("\t");
                        }
                    }
                    content.append("\n");
                    rowCount++;
                }
            }

            if (content.length() > 0) {
                Document springDoc = new Document(content.toString());
                addMetadata(springDoc, file, docType);
                springDoc.getMetadata().put("sheetName", sheet.getSheetName());
                springDoc.getMetadata().put("sheetIndex", i);
                if (!headerCells.isEmpty()) {
                    springDoc.getMetadata().put("table_headers", String.join(",", headerCells));
                    springDoc.getMetadata().put("table_column_count", headerCells.size());
                }
                if (rowCount > 0) {
                    springDoc.getMetadata().put("table_row_count", rowCount);
                }
                // Propagate workbook-level properties to each sheet document
                workbookProps.forEach((k, v) -> springDoc.getMetadata().put(k, v));
                documents.add(springDoc);
            }
        }

        return documents;
    }

    /**
     * Extracts OOXML CoreProperties from an XSSFWorkbook (xlsx/xlsm).
     * Returns an empty map for legacy HSSFWorkbook (xls) which lacks OOXML properties.
     */
    private Map<String, String> extractExcelCoreProperties(Workbook workbook) {
        Map<String, String> props = new LinkedHashMap<>();
        if (!(workbook instanceof XSSFWorkbook xssfWorkbook)) return props;
        try {
            var ooProps = xssfWorkbook.getProperties();
            if (ooProps == null) return props;
            var core = ooProps.getCoreProperties();
            if (core != null) {
                if (core.getCreator() != null && !core.getCreator().isBlank())
                    props.put("author", core.getCreator());
                if (core.getTitle() != null && !core.getTitle().isBlank())
                    props.put("title", core.getTitle());
                if (core.getKeywords() != null && !core.getKeywords().isBlank())
                    props.put("keywords", core.getKeywords());
                if (core.getSubject() != null && !core.getSubject().isBlank())
                    props.put("subject", core.getSubject());
                if (core.getDescription() != null && !core.getDescription().isBlank())
                    props.put("description", core.getDescription());
                if (core.getLastModifiedByUser() != null && !core.getLastModifiedByUser().isBlank())
                    props.put("lastModifiedBy", core.getLastModifiedByUser());
                if (core.getCreated() != null)
                    props.put("creationDate", core.getCreated().toInstant().toString());
                if (core.getModified() != null)
                    props.put("modificationDate", core.getModified().toInstant().toString());
            }
            var extProps = ooProps.getExtendedProperties();
            if (extProps != null) {
                var ext = extProps.getUnderlyingProperties();
                if (ext.getApplication() != null && !ext.getApplication().isBlank())
                    props.put("applicationName", ext.getApplication());
                if (ext.getCompany() != null && !ext.getCompany().isBlank())
                    props.put("company", ext.getCompany());
            }
        } catch (Exception e) {
            logger.warn("Failed to extract CoreProperties from Excel workbook", e);
        }
        return props;
    }

    private String getCellValueAsString(Cell cell) {
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getDateCellValue().toString();
                } else {
                    return String.valueOf(cell.getNumericCellValue());
                }
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                return cell.getCellFormula();
            default:
                return "";
        }
    }

    private List<Document> loadPowerPointPpt(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             HSLFSlideShow slideShow = new HSLFSlideShow(fis);
             SlideShowExtractor extractor = new SlideShowExtractor(slideShow)) {

            String content = extractor.getText();
            Document springDoc = new Document(content);
            addMetadata(springDoc, file, "Microsoft PowerPoint Presentation (.ppt)");
            return List.of(springDoc);
        }
    }

    private List<Document> loadPowerPointPptx(File file) throws IOException {
        List<Document> documents = new ArrayList<>();

        try (FileInputStream fis = new FileInputStream(file);
             XMLSlideShow slideShow = new XMLSlideShow(fis)) {

            // --- Extract workbook-level core properties once ---
            Map<String, String> pptxProps = extractPptxCoreProperties(slideShow);

            // --- Full-text summary document (index 0) ---
            try (SlideShowExtractor fullTextExtractor = new SlideShowExtractor(slideShow)) {
                String fullText = fullTextExtractor.getText();
                Document fullDoc = new Document(fullText != null ? fullText : "");
                addMetadata(fullDoc, file, "Microsoft PowerPoint Presentation (.pptx)");
                fullDoc.getMetadata().put("content_type", "text");
                pptxProps.forEach((k, v) -> fullDoc.getMetadata().put(k, v));
                documents.add(fullDoc);
            }

            // --- Per-slide documents ---
            List<XSLFSlide> slides = slideShow.getSlides();
            for (int slideIdx = 0; slideIdx < slides.size(); slideIdx++) {
                XSLFSlide slide = slides.get(slideIdx);
                int slideNumber = slideIdx + 1;

                // Collect slide text
                StringBuilder slideText = new StringBuilder();
                String slideTitle = null;
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        String text = textShape.getText();
                        if (text != null && !text.isBlank()) {
                            slideText.append(text).append("\n");
                            // First non-blank text shape is the title candidate
                            if (slideTitle == null) {
                                slideTitle = text.trim();
                            }
                        }
                    }
                }

                Document slideDoc = new Document(slideText.toString().trim());
                addMetadata(slideDoc, file, "Microsoft PowerPoint Presentation (.pptx)");
                slideDoc.getMetadata().put("content_type", "slide");
                slideDoc.getMetadata().put("slideNumber", String.valueOf(slideNumber));
                if (slideTitle != null) {
                    slideDoc.getMetadata().put("slideTitle", slideTitle);
                }
                // Speaker notes
                try {
                    var notes = slideShow.getNotesSlide(slide);
                    if (notes != null) {
                        StringBuilder notesText = new StringBuilder();
                        for (XSLFTextShape ph : notes.getPlaceholders()) {
                            if (ph.getTextType() == org.apache.poi.sl.usermodel.Placeholder.BODY) {
                                String t = ph.getText();
                                if (t != null && !t.isBlank()) notesText.append(t).append("\n");
                            }
                        }
                        String notesStr = notesText.toString().trim();
                        if (!notesStr.isEmpty()) {
                            slideDoc.getMetadata().put("speakerNotes", notesStr);
                        }
                    }
                } catch (Exception e) {
                    logger.debug("Could not extract speaker notes for slide {}: {}", slideNumber, e.getMessage());
                }
                pptxProps.forEach((k, v) -> slideDoc.getMetadata().put(k, v));
                documents.add(slideDoc);
            }
        }

        return documents;
    }

    /**
     * Extracts OOXML CoreProperties from an XMLSlideShow (pptx).
     */
    private Map<String, String> extractPptxCoreProperties(XMLSlideShow slideShow) {
        Map<String, String> props = new LinkedHashMap<>();
        try {
            var ooProps = slideShow.getProperties();
            if (ooProps == null) return props;
            var core = ooProps.getCoreProperties();
            if (core != null) {
                if (core.getCreator() != null && !core.getCreator().isBlank())
                    props.put("author", core.getCreator());
                if (core.getTitle() != null && !core.getTitle().isBlank())
                    props.put("title", core.getTitle());
                if (core.getKeywords() != null && !core.getKeywords().isBlank())
                    props.put("keywords", core.getKeywords());
                if (core.getSubject() != null && !core.getSubject().isBlank())
                    props.put("subject", core.getSubject());
                if (core.getDescription() != null && !core.getDescription().isBlank())
                    props.put("description", core.getDescription());
                if (core.getLastModifiedByUser() != null && !core.getLastModifiedByUser().isBlank())
                    props.put("lastModifiedBy", core.getLastModifiedByUser());
                if (core.getCreated() != null)
                    props.put("creationDate", core.getCreated().toInstant().toString());
                if (core.getModified() != null)
                    props.put("modificationDate", core.getModified().toInstant().toString());
            }
            var extProps = ooProps.getExtendedProperties();
            if (extProps != null) {
                var ext = extProps.getUnderlyingProperties();
                if (ext.getApplication() != null && !ext.getApplication().isBlank())
                    props.put("applicationName", ext.getApplication());
                if (ext.getCompany() != null && !ext.getCompany().isBlank())
                    props.put("company", ext.getCompany());
            }
        } catch (Exception e) {
            logger.warn("Failed to extract CoreProperties from pptx", e);
        }
        return props;
    }

    private List<Document> loadAccessDatabase(File file) throws IOException {
        List<Document> documents = new ArrayList<>();

        try (Database database = DatabaseBuilder.open(file)) {
            for (String tableName : database.getTableNames()) {
                Table table = database.getTable(tableName);
                StringBuilder content = new StringBuilder();
                content.append("Table: ").append(tableName).append("\n\n");

                // Add column headers
                for (com.healthmarketscience.jackcess.Column column : table.getColumns()) {
                    content.append(column.getName()).append("\t");
                }
                content.append("\n");

                for (com.healthmarketscience.jackcess.Row row : table) {
                    for (com.healthmarketscience.jackcess.Column column : table.getColumns()) {
                        Object value = row.get(column.getName());
                        content.append(value != null ? value.toString() : "").append("\t");
                    }
                    content.append("\n");
                }

                if (content.length() > 0) {
                    Document springDoc = new Document(content.toString());
                    addMetadata(springDoc, file, "Microsoft Access Database");
                    springDoc.getMetadata().put("tableName", tableName);
                    documents.add(springDoc);
                }
            }
        }

        return documents;
    }

    private List<Document> loadOutlookPst(File file) throws Exception {
        List<Document> documents = new ArrayList<>();

        PSTFile pstFile = new PSTFile(file);
        try {
            PSTFolder rootFolder = pstFile.getRootFolder();
            extractPstFolder(rootFolder, documents, file);
        } finally {
            pstFile.close();
        }

        return documents;
    }

    private void extractPstFolder(PSTFolder folder, List<Document> documents, File originalFile) throws Exception {
        // Process messages in this folder
        if (folder.getContentCount() > 0) {
            PSTMessage message = (PSTMessage) folder.getNextChild();
            while (message != null) {
                StringBuilder content = new StringBuilder();
                content.append("Subject: ").append(message.getSubject()).append("\n");
                content.append("From: ").append(message.getSenderName()).append(" <").append(message.getSenderEmailAddress()).append(">\n");
                content.append("To: ").append(message.getDisplayTo()).append("\n");
                content.append("Date: ").append(message.getClientSubmitTime()).append("\n\n");
                content.append(message.getBody());

                Document springDoc = new Document(content.toString());
                addMetadata(springDoc, originalFile, "Outlook PST Message");
                springDoc.getMetadata().put("subject", message.getSubject());
                springDoc.getMetadata().put("sender", message.getSenderName());
                springDoc.getMetadata().put("messageDate", message.getClientSubmitTime());
                springDoc.getMetadata().put("folderName", folder.getDisplayName());
                documents.add(springDoc);

                message = (PSTMessage) folder.getNextChild();
            }
        }

        // Process subfolders
        if (folder.hasSubfolders()) {
            List<PSTFolder> childFolders = folder.getSubFolders();
            for (PSTFolder childFolder : childFolders) {
                extractPstFolder(childFolder, documents, originalFile);
            }
        }
    }

    private void addMetadata(Document document, File file, String docType) {
        document.getMetadata().put("source", file.getAbsolutePath());
        document.getMetadata().put("fileName", file.getName());
        document.getMetadata().put("fileSize", file.length());
        document.getMetadata().put("lastModified", file.lastModified());
        document.getMetadata().put("documentType", docType);
        document.getMetadata().put("loader", getName());
        document.getMetadata().put("source_type", "FILE");
    }
}
