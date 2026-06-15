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

package ai.kompile.loader.email.inbox;

import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.core.loaders.DocumentSourceDescriptor.SourceType;
import org.apache.james.mime4j.dom.*;
import org.apache.james.mime4j.dom.field.ContentDispositionField;
import org.apache.james.mime4j.dom.field.ContentTypeField;
import org.apache.james.mime4j.stream.Field;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Extracts binary and text attachments from MIME messages to temp files,
 * producing {@link DocumentSourceDescriptor}s that can be routed to the
 * appropriate loader pipeline (PDF, Office, etc.) by the
 * {@link ai.kompile.crawler.CrawlPipelineRouter}.
 *
 * <p>Each extracted attachment becomes a standalone file on disk with the
 * correct extension, allowing downstream loaders to process it natively.
 * Temp files are marked {@code deleteOnExit} and cleaned up on JVM shutdown.</p>
 */
public class EmailAttachmentExtractor {

    private static final Logger logger = LoggerFactory.getLogger(EmailAttachmentExtractor.class);

    private final Path tempDir;

    public EmailAttachmentExtractor() {
        this(null);
    }

    public EmailAttachmentExtractor(Path tempDir) {
        if (tempDir != null) {
            this.tempDir = tempDir;
        } else {
            try {
                this.tempDir = Files.createTempDirectory("kompile-email-attachments-");
                this.tempDir.toFile().deleteOnExit();
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to create temp directory for attachments", e);
            }
        }
    }

    /**
     * Extracts all attachments from a MIME message to temp files.
     *
     * @param message      the parsed mime4j Message
     * @param parentSource source identifier of the parent email (e.g., file path or mbox#index)
     * @param parentMeta   metadata from the parent email (messageId, subject, from, date)
     * @return list of extracted attachments with temp file paths and descriptors
     */
    public List<ExtractedAttachment> extractAttachments(Message message, String parentSource,
                                                         Map<String, Object> parentMeta) {
        List<ExtractedAttachment> attachments = new ArrayList<>();
        Body body = message.getBody();
        if (!(body instanceof Multipart)) {
            return attachments;
        }
        collectAttachments((Multipart) body, parentSource, parentMeta, attachments);
        return attachments;
    }

    /**
     * Returns the temp directory used for storing extracted attachments.
     */
    public Path getTempDir() {
        return tempDir;
    }

    // ── Recursive attachment collection ──────────────────────────────────

    private void collectAttachments(Multipart multipart, String parentSource,
                                     Map<String, Object> parentMeta,
                                     List<ExtractedAttachment> attachments) {
        for (Entity part : multipart.getBodyParts()) {
            if (part.getBody() instanceof Multipart) {
                collectAttachments((Multipart) part.getBody(), parentSource, parentMeta, attachments);
                continue;
            }

            if (!isAttachment(part)) {
                continue;
            }

            String filename = getFilename(part);
            String mimeType = part.getMimeType();
            String extension = deriveExtension(filename, mimeType);

            try {
                Path tempFile;
                long size;

                if (part.getBody() instanceof BinaryBody binaryBody) {
                    tempFile = Files.createTempFile(tempDir, "attach-",
                            extension != null ? "." + extension : "");
                    try (InputStream is = binaryBody.getInputStream()) {
                        size = Files.copy(is, tempFile, StandardCopyOption.REPLACE_EXISTING);
                    }
                } else if (part.getBody() instanceof TextBody textBody) {
                    tempFile = Files.createTempFile(tempDir, "attach-",
                            extension != null ? "." + extension : ".txt");
                    try (Reader reader = textBody.getReader();
                         Writer writer = Files.newBufferedWriter(tempFile)) {
                        char[] buffer = new char[4096];
                        int n;
                        while ((n = reader.read(buffer)) != -1) {
                            writer.write(buffer, 0, n);
                        }
                    }
                    size = Files.size(tempFile);
                } else {
                    continue;
                }

                tempFile.toFile().deleteOnExit();

                // Build metadata linking attachment back to its parent email
                String attachSourceUrl = parentSource + "#attachment:" + (filename != null ? filename : "unnamed");
                Map<String, Object> attachMeta = new LinkedHashMap<>();
                attachMeta.put("email.isAttachment", true);
                attachMeta.put(GraphConstants.META_SOURCE, attachSourceUrl);
                attachMeta.put(GraphConstants.META_SOURCE_PATH, attachSourceUrl);
                attachMeta.put(GraphConstants.META_FILE_NAME, filename != null ? filename : "unnamed");
                attachMeta.put(GraphConstants.META_LOADER, "Email Inbox Crawler");
                attachMeta.put(GraphConstants.META_SOURCE_TYPE, "EMAIL_ATTACHMENT");
                if (parentMeta != null) {
                    putIfNotNull(attachMeta, "email.parentMessageId", parentMeta.get("email.messageId"));
                    putIfNotNull(attachMeta, "email.parentSubject", parentMeta.get("email.subject"));
                    putIfNotNull(attachMeta, "email.parentFrom", parentMeta.get("email.from"));
                    putIfNotNull(attachMeta, "email.parentDate", parentMeta.get("email.date"));
                }
                if (filename != null) {
                    attachMeta.put("email.attachmentName", filename);
                }
                if (mimeType != null) {
                    attachMeta.put("email.attachmentMimeType", mimeType);
                }
                attachMeta.put(GraphConstants.META_EMAIL_ATTACHMENT_SIZE, size);

                DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                        .type(SourceType.FILE)
                        .pathOrUrl(tempFile.toAbsolutePath().toString())
                        .originalFileName(filename != null ? filename : "unnamed")
                        .sourceId(parentSource + "#attachment:" + (filename != null ? filename : "unnamed"))
                        .sizeBytes(size)
                        .metadata(attachMeta)
                        .build();

                attachments.add(new ExtractedAttachment(
                        tempFile, filename, mimeType, size, descriptor
                ));

                logger.debug("Extracted attachment: {} ({}, {} bytes) to {}",
                        filename, mimeType, size, tempFile);

            } catch (IOException e) {
                logger.warn("Failed to extract attachment '{}' from {}: {}",
                        filename, parentSource, e.getMessage());
            }
        }
    }

    // ── MIME helpers ─────────────────────────────────────────────────────

    private boolean isAttachment(Entity entity) {
        Field dispositionField = entity.getHeader().getField("Content-Disposition");
        if (dispositionField instanceof ContentDispositionField cdf) {
            String disposition = cdf.getDispositionType();
            if ("attachment".equalsIgnoreCase(disposition)) {
                return true;
            }
            // Inline binary document parts (PDF, Office, etc.) should also be extracted
            // for sub-loader processing, but skip inline images (part of email body rendering)
            if ("inline".equalsIgnoreCase(disposition)) {
                String mimeType = entity.getMimeType();
                return mimeType != null && isExtractableDocumentType(mimeType);
            }
        }
        if (dispositionField != null) {
            return dispositionField.getBody().toLowerCase().contains("attachment");
        }
        return false;
    }

    /**
     * Returns true for MIME types representing binary documents that should be
     * extracted and routed to sub-loaders (PDF, Office, archives, etc.).
     * Excludes images and plain text which are typically inline email content.
     */
    private static boolean isExtractableDocumentType(String mimeType) {
        String lower = mimeType.toLowerCase();
        return lower.equals("application/pdf")
                || lower.startsWith("application/vnd.ms-")
                || lower.startsWith("application/vnd.openxmlformats-")
                || lower.equals("application/msword")
                || lower.equals("application/zip")
                || lower.equals("application/gzip")
                || lower.equals("application/x-tar")
                || lower.equals("application/json")
                || lower.equals("text/csv")
                || lower.equals("application/xml")
                || lower.equals("text/xml");
    }

    private String getFilename(Entity entity) {
        Field dispositionField = entity.getHeader().getField("Content-Disposition");
        if (dispositionField instanceof ContentDispositionField cdf) {
            String filename = cdf.getFilename();
            if (filename != null) return filename;
        }
        Field ctField = entity.getHeader().getField("Content-Type");
        if (ctField instanceof ContentTypeField ctf) {
            return ctf.getParameter("name");
        }
        return null;
    }

    static String deriveExtension(String filename, String mimeType) {
        if (filename != null) {
            int dot = filename.lastIndexOf('.');
            if (dot >= 0 && dot < filename.length() - 1) {
                return filename.substring(dot + 1).toLowerCase();
            }
        }
        if (mimeType == null) return null;
        return switch (mimeType.toLowerCase()) {
            case "application/pdf" -> "pdf";
            case "application/msword" -> "doc";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx";
            case "application/vnd.ms-excel" -> "xls";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx";
            case "application/vnd.ms-powerpoint" -> "ppt";
            case "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx";
            case "text/plain" -> "txt";
            case "text/csv" -> "csv";
            case "text/html" -> "html";
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "image/gif" -> "gif";
            case "application/json" -> "json";
            case "application/xml", "text/xml" -> "xml";
            case "application/zip" -> "zip";
            case "application/gzip" -> "gz";
            default -> null;
        };
    }

    private static void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    /**
     * An attachment extracted to a temp file with its metadata and source descriptor.
     *
     * @param tempFile         path to the temp file containing the attachment bytes
     * @param originalFilename original filename from the MIME headers (may be null)
     * @param mimeType         MIME content type of the attachment
     * @param sizeBytes        size of the extracted file in bytes
     * @param sourceDescriptor a FILE-type descriptor pointing to the temp file,
     *                         suitable for routing to other loaders
     */
    public record ExtractedAttachment(
            Path tempFile,
            String originalFilename,
            String mimeType,
            long sizeBytes,
            DocumentSourceDescriptor sourceDescriptor
    ) {}
}
