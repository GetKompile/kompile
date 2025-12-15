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

package ai.kompile.loader.pdf;

import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.action.PDAction;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.ai.document.Document;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
@ImportRuntimeHints(PdfExtendedLoaderImpl.PdfReaderHints.class)
public class PdfExtendedLoaderImpl implements DocumentLoader {

    private static final Logger logger = LoggerFactory.getLogger(PdfExtendedLoaderImpl.class);
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("pdf");

    // Configuration options
    private boolean extractByPage = false;
    private boolean extractMetadata = true;
    private boolean extractAnnotations = true;
    private boolean extractFormFields = true;
    private boolean extractBookmarks = true;
    private boolean extractLinks = true;


    static class PdfReaderHints implements RuntimeHintsRegistrar {

        @Override
        public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
            try {

                var resolver = new PathMatchingResourcePatternResolver();

                var patterns = Set.of("/org/apache/pdfbox/resources/glyphlist/zapfdingbats.txt",
                        "/org/apache/pdfbox/resources/glyphlist/glyphlist.txt", "/org/apache/fontbox/cmap/**",
                        "/org/apache/pdfbox/resources/afm/**", "/org/apache/pdfbox/resources/glyphlist/**",
                        "/org/apache/pdfbox/resources/icc/**", "/org/apache/pdfbox/resources/text/**",
                        "/org/apache/pdfbox/resources/ttf/**", "/org/apache/pdfbox/resources/version.properties");

                for (var pattern : patterns)
                    for (var resourceMatch : resolver.getResources(pattern))
                        hints.resources().registerResource(resourceMatch);

            }
            catch (IOException e) {
                throw new RuntimeException(e);
            }

        }

    }

    @Override
    public String getName() {
        return "PDF Extended Loader";
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
            throw new IllegalArgumentException("PdfExtendedLoader currently only supports FILE sources.");
        }

        File file = new File(sourceDescriptor.getPathOrUrl());
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("File does not exist or is not a regular file: " + sourceDescriptor.getPathOrUrl());
        }

        List<Document> documents = new ArrayList<>();

        try (PDDocument document = Loader.loadPDF(file)) {
            if (extractByPage) {
                documents.addAll(extractByPages(document, file));
            } else {
                documents.add(extractFullDocument(document, file));
            }

            // Extract additional content types as separate documents
            if (extractAnnotations) {
                Document annotationsDoc = extractAnnotations(document, file);
                if (annotationsDoc != null) {
                    documents.add(annotationsDoc);
                }
            }

            if (extractFormFields) {
                Document formFieldsDoc = extractFormFields(document, file);
                if (formFieldsDoc != null) {
                    documents.add(formFieldsDoc);
                }
            }

            if (extractBookmarks) {
                Document bookmarksDoc = extractBookmarks(document, file);
                if (bookmarksDoc != null) {
                    documents.add(bookmarksDoc);
                }
            }
        } catch (IOException e) {
            // Handle corrupted or invalid PDF files gracefully
            String errorMessage = e.getMessage();
            if (errorMessage != null && (
                    errorMessage.contains("Missing root object") ||
                    errorMessage.contains("trailer") ||
                    errorMessage.contains("Expected a long type") ||
                    errorMessage.contains("Could not find") ||
                    errorMessage.contains("Invalid") ||
                    errorMessage.contains("Malformed") ||
                    errorMessage.contains("not a PDF") ||
                    errorMessage.contains("startxref"))) {
                // This is a corrupted or invalid PDF file
                logger.warn("Unable to parse PDF file '{}': {}. The file may be corrupted or not a valid PDF.",
                           file.getName(), errorMessage);

                // Return an error document so the caller knows what happened
                Document errorDoc = new Document("[Error: Unable to parse PDF file. The file may be corrupted, truncated, or not a valid PDF document.]");
                errorDoc.getMetadata().put("source", file.getAbsolutePath());
                errorDoc.getMetadata().put("fileName", file.getName());
                errorDoc.getMetadata().put("fileSize", file.length());
                errorDoc.getMetadata().put("lastModified", file.lastModified());
                errorDoc.getMetadata().put("documentType", "PDF Document (Error)");
                errorDoc.getMetadata().put("loader", getName());
                errorDoc.getMetadata().put("parseError", true);
                errorDoc.getMetadata().put("errorMessage", errorMessage);
                documents.add(errorDoc);
            } else {
                // Re-throw other IOExceptions that aren't related to PDF parsing
                throw e;
            }
        }

        return documents;
    }

    private Document extractFullDocument(PDDocument document, File file) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        String content = stripper.getText(document);

        Document springDoc = new Document(content);
        addBasicMetadata(springDoc, file, document);
        springDoc.getMetadata().put("extractionType", "fullDocument");

        return springDoc;
    }

    private List<Document> extractByPages(PDDocument document, File file) throws IOException {
        List<Document> documents = new ArrayList<>();
        PDFTextStripper stripper = new PDFTextStripper();

        for (int i = 1; i <= document.getNumberOfPages(); i++) {
            stripper.setStartPage(i);
            stripper.setEndPage(i);
            String pageContent = stripper.getText(document);

            if (pageContent != null && !pageContent.trim().isEmpty()) {
                Document springDoc = new Document(pageContent);
                addBasicMetadata(springDoc, file, document);
                springDoc.getMetadata().put("extractionType", "singlePage");
                springDoc.getMetadata().put("pageNumber", i);
                springDoc.getMetadata().put("totalPages", document.getNumberOfPages());
                documents.add(springDoc);
            }
        }

        return documents;
    }

    private Document extractAnnotations(PDDocument document, File file) throws IOException {
        StringBuilder annotationContent = new StringBuilder();
        List<String> links = new ArrayList<>();

        for (int pageNum = 0; pageNum < document.getNumberOfPages(); pageNum++) {
            PDPage page = document.getPage(pageNum);
            List<PDAnnotation> annotations = page.getAnnotations();

            for (PDAnnotation annotation : annotations) {
                if (annotation instanceof PDAnnotationLink) {
                    PDAnnotationLink linkAnnotation = (PDAnnotationLink) annotation;
                    PDAction action = linkAnnotation.getAction();
                    if (action instanceof PDActionURI) {
                        PDActionURI uriAction = (PDActionURI) action;
                        String uri = uriAction.getURI();
                        links.add(uri);
                        annotationContent.append("Link on page ").append(pageNum + 1)
                                .append(": ").append(uri).append("\n");
                    }
                }

                // Extract annotation contents
                if (annotation.getContents() != null && !annotation.getContents().trim().isEmpty()) {
                    annotationContent.append("Annotation on page ").append(pageNum + 1)
                            .append(": ").append(annotation.getContents()).append("\n");
                }
            }
        }

        if (annotationContent.length() > 0) {
            Document annotationsDoc = new Document(annotationContent.toString());
            addBasicMetadata(annotationsDoc, file, document);
            annotationsDoc.getMetadata().put("extractionType", "annotations");
            annotationsDoc.getMetadata().put("linkCount", links.size());
            return annotationsDoc;
        }

        return null;
    }

    private Document extractFormFields(PDDocument document, File file) throws IOException {
        PDAcroForm acroForm = document.getDocumentCatalog().getAcroForm();
        if (acroForm == null) {
            return null;
        }

        StringBuilder formContent = new StringBuilder();
        List<PDField> fields = acroForm.getFields();

        for (PDField field : fields) {
            String fieldName = field.getFullyQualifiedName();
            String fieldValue = field.getValueAsString();
            String fieldType = field.getFieldType();

            formContent.append("Field: ").append(fieldName)
                    .append(" (").append(fieldType).append(")")
                    .append(" = ").append(fieldValue != null ? fieldValue : "")
                    .append("\n");
        }

        if (formContent.length() > 0) {
            Document formDoc = new Document(formContent.toString());
            addBasicMetadata(formDoc, file, document);
            formDoc.getMetadata().put("extractionType", "formFields");
            formDoc.getMetadata().put("fieldCount", fields.size());
            return formDoc;
        }

        return null;
    }

    private Document extractBookmarks(PDDocument document, File file) throws IOException {
        PDDocumentOutline outline = document.getDocumentCatalog().getDocumentOutline();
        if (outline == null) {
            return null;
        }

        StringBuilder bookmarkContent = new StringBuilder();
        extractBookmarkItems(outline, bookmarkContent, 0);

        if (bookmarkContent.length() > 0) {
            Document bookmarkDoc = new Document(bookmarkContent.toString());
            addBasicMetadata(bookmarkDoc, file, document);
            bookmarkDoc.getMetadata().put("extractionType", "bookmarks");
            return bookmarkDoc;
        }

        return null;
    }

    private void extractBookmarkItems(PDDocumentOutline item, StringBuilder content, int level) throws IOException {
        extractBookmarkItems(item.getFirstChild(),content,level);
    }

    private void extractBookmarkItems(PDOutlineItem item, StringBuilder content, int level) throws IOException {
        String indent = "  ".repeat(level);

        if (item != null) {
            content.append(indent).append(item.getTitle()).append("\n");

            // Process children
            PDOutlineItem child = item.getFirstChild();
            while (child != null) {
                extractBookmarkItems(child, content, level + 1);
                child = child.getNextSibling();
            }
        }
    }

    private void addBasicMetadata(Document document, File file, PDDocument pdfDocument) throws IOException {
        document.getMetadata().put("source", file.getAbsolutePath());
        document.getMetadata().put("fileName", file.getName());
        document.getMetadata().put("fileSize", file.length());
        document.getMetadata().put("lastModified", file.lastModified());
        document.getMetadata().put("documentType", "PDF Document");
        document.getMetadata().put("loader", getName());
        document.getMetadata().put("pageCount", pdfDocument.getNumberOfPages());

        if (extractMetadata) {
            PDDocumentInformation info = pdfDocument.getDocumentInformation();
            if (info != null) {
                if (info.getTitle() != null) {
                    document.getMetadata().put("title", info.getTitle());
                }
                if (info.getAuthor() != null) {
                    document.getMetadata().put("author", info.getAuthor());
                }
                if (info.getSubject() != null) {
                    document.getMetadata().put("subject", info.getSubject());
                }
                if (info.getKeywords() != null) {
                    document.getMetadata().put("keywords", info.getKeywords());
                }
                if (info.getCreator() != null) {
                    document.getMetadata().put("creator", info.getCreator());
                }
                if (info.getProducer() != null) {
                    document.getMetadata().put("producer", info.getProducer());
                }
                if (info.getCreationDate() != null) {
                    document.getMetadata().put("creationDate", info.getCreationDate().getTime());
                }
                if (info.getModificationDate() != null) {
                    document.getMetadata().put("modificationDate", info.getModificationDate().getTime());
                }
            }
        }
    }

    // Configuration setters
    public void setExtractByPage(boolean extractByPage) {
        this.extractByPage = extractByPage;
    }

    public void setExtractMetadata(boolean extractMetadata) {
        this.extractMetadata = extractMetadata;
    }

    public void setExtractAnnotations(boolean extractAnnotations) {
        this.extractAnnotations = extractAnnotations;
    }

    public void setExtractFormFields(boolean extractFormFields) {
        this.extractFormFields = extractFormFields;
    }

    public void setExtractBookmarks(boolean extractBookmarks) {
        this.extractBookmarks = extractBookmarks;
    }

    public void setExtractLinks(boolean extractLinks) {
        this.extractLinks = extractLinks;
    }
}
