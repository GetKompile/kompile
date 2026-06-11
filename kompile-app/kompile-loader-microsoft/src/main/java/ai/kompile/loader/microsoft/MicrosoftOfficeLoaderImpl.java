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
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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

        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            Sheet sheet = workbook.getSheetAt(i);
            StringBuilder content = new StringBuilder();
            content.append("Sheet: ").append(sheet.getSheetName()).append("\n\n");

            for (Row row : sheet) {
                for (Cell cell : row) {
                    String cellValue = getCellValueAsString(cell);
                    if (!cellValue.trim().isEmpty()) {
                        content.append(cellValue).append("\t");
                    }
                }
                content.append("\n");
            }

            if (content.length() > 0) {
                Document springDoc = new Document(content.toString());
                addMetadata(springDoc, file, docType);
                springDoc.getMetadata().put("sheetName", sheet.getSheetName());
                springDoc.getMetadata().put("sheetIndex", i);
                documents.add(springDoc);
            }
        }

        return documents;
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
        try (FileInputStream fis = new FileInputStream(file);
             XMLSlideShow slideShow = new XMLSlideShow(fis);
             SlideShowExtractor extractor = new SlideShowExtractor(slideShow)) {

            String content = extractor.getText();
            Document springDoc = new Document(content);
            addMetadata(springDoc, file, "Microsoft PowerPoint Presentation (.pptx)");
            return List.of(springDoc);
        }
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
        PSTFolder rootFolder = pstFile.getRootFolder();
        extractPstFolder(rootFolder, documents, file);


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
    }
}
