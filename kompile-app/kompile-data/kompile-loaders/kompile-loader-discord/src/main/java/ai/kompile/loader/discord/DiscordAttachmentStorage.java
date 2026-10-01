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

package ai.kompile.loader.discord;

import ai.kompile.loader.discord.DiscordModels.Attachment;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Implements the shared "attachmentDirectory" contract used by every Kompile message loader:
 * when a descriptor/config carries an {@code attachmentDirectory}, attachments are saved to
 * {@code <attachmentDirectory>/<messageKey>/<fileName>} as original bytes, and the parent message
 * gets an {@code "attachments"} metadata list describing what was saved (or skipped) — no separate
 * attachment Document/CrawlItem is produced, and no text is extracted here. The CLI is expected to
 * crawl the saved files itself with its own native-safe file loaders.
 */
@Slf4j
final class DiscordAttachmentStorage {

    /** Default cap on a single attachment's size before it's skipped instead of saved. */
    static final long DEFAULT_MAX_ATTACHMENT_BYTES = 26_214_400L;
    private static final int MAX_FILENAME_LENGTH = 120;

    private DiscordAttachmentStorage() {}

    /**
     * Derives the stable per-message directory name: the first 16 hex characters of
     * SHA-256(sourcePath).
     */
    static String messageKey(String sourcePath) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(sourcePath.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) { // 8 bytes -> 16 hex chars
                hex.append(Character.forDigit((hash[i] >> 4) & 0xF, 16));
                hex.append(Character.forDigit(hash[i] & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Downloads and saves one attachment under {@code attachmentDirectory}, or records why it was
     * skipped. Never throws for per-attachment problems (oversize, download failure, a resolved
     * path escaping the target directory) — those become a {@code "skipped"} entry so the caller
     * can keep going with the rest of the message's attachments.
     *
     * @param usedFileNames file names already claimed for this message; updated in place so a
     *                       second attachment with the same name gets a de-duplicated one
     * @return a metadata entry for the message's {@code "attachments"} list: either
     *         {@code {fileName, contentType, size, path}} on success, or
     *         {@code {fileName, contentType, size, skipped}} when saving was skipped
     */
    static Map<String, Object> save(DiscordApiService api, Attachment attachment, Path attachmentDirectory,
                                     String messageKey, long maxAttachmentBytes, Set<String> usedFileNames) {
        String fileName = sanitizeFileName(attachment.filename(), attachment.contentType(), usedFileNames);

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("fileName", fileName);
        if (attachment.contentType() != null) entry.put("contentType", attachment.contentType());
        entry.put("size", attachment.size());

        if (attachment.size() > maxAttachmentBytes) {
            entry.put("skipped", "exceeds maxAttachmentBytes (" + maxAttachmentBytes + ")");
            return entry;
        }

        Path normalizedRoot = attachmentDirectory.normalize();
        Path messageDir = normalizedRoot.resolve(messageKey).normalize();
        Path target = messageDir.resolve(fileName).normalize();
        if (!target.startsWith(normalizedRoot)) {
            log.warn("Refusing to write Discord attachment '{}' outside attachmentDirectory: {}",
                    attachment.filename(), target);
            entry.put("skipped", "resolved path escaped attachmentDirectory");
            return entry;
        }

        Path tempFile;
        try {
            tempFile = api.downloadAttachment(attachment);
        } catch (Exception e) {
            log.warn("Failed to download Discord attachment '{}': {}", attachment.filename(), e.getMessage());
            entry.put("skipped", "download failed: " + e.getMessage());
            return entry;
        }

        try {
            Files.createDirectories(messageDir);
            Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
            entry.put("path", messageKey + "/" + fileName);
            return entry;
        } catch (IOException e) {
            log.warn("Failed to save Discord attachment '{}' to {}: {}", attachment.filename(), target, e.getMessage());
            entry.put("skipped", "save failed: " + e.getMessage());
            return entry;
        } finally {
            // Whether the move succeeded (nothing left at the source) or failed (bail out cleanly
            // instead of leaving a stray temp file behind), the temp file must never linger.
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    /**
     * Sanitizes an original attachment file name: strips path separators and control characters,
     * strips leading dots, derives an extension from the content type when the name is missing
     * one, truncates to {@value #MAX_FILENAME_LENGTH} characters while preserving the extension,
     * and de-duplicates within the message as {@code name-2.ext}, {@code name-3.ext}, etc.
     */
    static String sanitizeFileName(String original, String contentType, Set<String> usedFileNames) {
        String base = original == null ? "" : original;
        StringBuilder cleaned = new StringBuilder(base.length());
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
            if (c == '/' || c == '\\' || Character.isISOControl(c)) continue;
            cleaned.append(c);
        }
        String name = cleaned.toString();

        int firstNonDot = 0;
        while (firstNonDot < name.length() && name.charAt(firstNonDot) == '.') firstNonDot++;
        name = name.substring(firstNonDot);
        if (name.isEmpty()) name = "attachment";

        String ext;
        String stem;
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            ext = name.substring(dot); // includes leading '.'
            stem = name.substring(0, dot);
        } else {
            stem = name;
            String derivedExt = extensionForContentType(contentType);
            ext = derivedExt != null ? derivedExt : "";
        }

        if (stem.length() + ext.length() > MAX_FILENAME_LENGTH) {
            int stemBudget = Math.max(1, MAX_FILENAME_LENGTH - ext.length());
            stem = stem.substring(0, Math.min(stem.length(), stemBudget));
        }

        String candidate = stem + ext;
        if (usedFileNames.add(candidate)) return candidate;

        for (int suffix = 2; ; suffix++) {
            String suffixed = stem + "-" + suffix + ext;
            if (usedFileNames.add(suffixed)) return suffixed;
        }
    }

    private static String extensionForContentType(String contentType) {
        if (contentType == null) return null;
        String type = contentType.split(";")[0].trim().toLowerCase();
        return switch (type) {
            case "image/png" -> ".png";
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            case "image/svg+xml" -> ".svg";
            case "application/pdf" -> ".pdf";
            case "text/plain" -> ".txt";
            case "text/csv" -> ".csv";
            case "application/json" -> ".json";
            case "application/zip" -> ".zip";
            case "video/mp4" -> ".mp4";
            case "audio/mpeg" -> ".mp3";
            case "application/msword" -> ".doc";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> ".docx";
            case "application/vnd.ms-excel" -> ".xls";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> ".xlsx";
            default -> null;
        };
    }
}
