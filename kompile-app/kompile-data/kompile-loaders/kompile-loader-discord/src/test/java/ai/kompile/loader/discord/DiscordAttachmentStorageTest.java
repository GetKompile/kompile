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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the C4/B5 "attachmentDirectory" contract implemented by {@link DiscordAttachmentStorage}:
 * original bytes saved under {@code <attachmentDirectory>/<messageKey>/<fileName>}, path containment,
 * file-name sanitization/truncation/de-duplication, oversize skipping without downloading, and that
 * the temp file {@link DiscordApiService#downloadAttachment} produces is always deleted, whether the
 * save succeeds or fails.
 */
class DiscordAttachmentStorageTest {

    @Test
    void savesAttachmentUnderMessageKeyDirectoryWithOriginalBytesAndDeletesTempFile(@TempDir Path attachmentDirectory) {
        StubApiService api = new StubApiService();
        Attachment att = attachment("report.pdf", "application/pdf", 1024L);
        String messageKey = DiscordAttachmentStorage.messageKey("discord://g/c/m1");

        Map<String, Object> entry = DiscordAttachmentStorage.save(
                api, att, attachmentDirectory, messageKey, DiscordAttachmentStorage.DEFAULT_MAX_ATTACHMENT_BYTES,
                new HashSet<>());

        assertEquals("report.pdf", entry.get("fileName"));
        assertEquals("application/pdf", entry.get("contentType"));
        assertEquals(1024L, entry.get("size"));
        assertEquals(messageKey + "/report.pdf", entry.get("path"));
        assertNull(entry.get("skipped"));

        Path saved = attachmentDirectory.resolve(messageKey).resolve("report.pdf");
        assertTrue(Files.exists(saved), "attachment must be written to <attachmentDirectory>/<messageKey>/<fileName>");
        assertArrayEquals(api.content, readAll(saved));
        assertFalse(Files.exists(api.lastTempFile), "temp file must be deleted after a successful save");
    }

    @Test
    void oversizedAttachmentIsSkippedWithoutDownloading(@TempDir Path attachmentDirectory) {
        StubApiService api = new StubApiService();
        Attachment att = attachment("huge.zip", "application/zip", 10_000L);

        Map<String, Object> entry = DiscordAttachmentStorage.save(
                api, att, attachmentDirectory, "abc123", 100L, new HashSet<>());

        assertEquals("exceeds maxAttachmentBytes (100)", entry.get("skipped"));
        assertNull(entry.get("path"));
        assertFalse(api.downloadCalled, "an oversized attachment must never be downloaded");
        assertFalse(Files.exists(attachmentDirectory.resolve("abc123")),
                "no per-message directory should be created for a skipped attachment");
    }

    @Test
    void duplicateFileNamesAreDeduplicatedPerMessage(@TempDir Path attachmentDirectory) {
        StubApiService api = new StubApiService();
        Set<String> used = new HashSet<>();
        Attachment att1 = attachment("image.png", "image/png", 10L);
        Attachment att2 = attachment("image.png", "image/png", 10L);

        Map<String, Object> entry1 = DiscordAttachmentStorage.save(
                api, att1, attachmentDirectory, "key1", DiscordAttachmentStorage.DEFAULT_MAX_ATTACHMENT_BYTES, used);
        Map<String, Object> entry2 = DiscordAttachmentStorage.save(
                api, att2, attachmentDirectory, "key1", DiscordAttachmentStorage.DEFAULT_MAX_ATTACHMENT_BYTES, used);

        assertEquals("image.png", entry1.get("fileName"));
        assertEquals("image-2.png", entry2.get("fileName"));
        assertTrue(Files.exists(attachmentDirectory.resolve("key1").resolve("image.png")));
        assertTrue(Files.exists(attachmentDirectory.resolve("key1").resolve("image-2.png")));
    }

    @Test
    void tempFileIsDeletedEvenWhenSaveFails(@TempDir Path tempDir) throws IOException {
        StubApiService api = new StubApiService();
        // Point attachmentDirectory at a path that is actually a regular file, so
        // Files.createDirectories(messageDir) fails and the save is skipped rather than succeeding.
        Path blockedRoot = tempDir.resolve("blocked");
        Files.writeString(blockedRoot, "not a directory");
        Attachment att = attachment("note.txt", "text/plain", 5L);

        Map<String, Object> entry = DiscordAttachmentStorage.save(
                api, att, blockedRoot, "key1", DiscordAttachmentStorage.DEFAULT_MAX_ATTACHMENT_BYTES, new HashSet<>());

        assertTrue(entry.get("skipped") != null && ((String) entry.get("skipped")).startsWith("save failed"),
                String.valueOf(entry.get("skipped")));
        assertFalse(Files.exists(api.lastTempFile), "temp file must be deleted even when the save fails");
    }

    @Test
    void sanitizeFileNameStripsSeparatorsControlCharsAndLeadingDots() {
        String result = DiscordAttachmentStorage.sanitizeFileName("../../\u0000evil.txt", null, new HashSet<>());

        assertFalse(result.contains("/"));
        assertFalse(result.contains("\\"));
        assertFalse(result.chars().anyMatch(Character::isISOControl));
        assertFalse(result.startsWith("."));
        assertEquals("evil.txt", result);
    }

    @Test
    void sanitizeFileNameDerivesExtensionFromContentTypeWhenMissing() {
        String result = DiscordAttachmentStorage.sanitizeFileName("noext", "image/png", new HashSet<>());

        assertEquals("noext.png", result);
    }

    @Test
    void sanitizeFileNameLeavesNameWithoutExtensionAloneWhenContentTypeIsUnknown() {
        String result = DiscordAttachmentStorage.sanitizeFileName("noext", "application/x-made-up", new HashSet<>());

        assertEquals("noext", result);
    }

    @Test
    void sanitizeFileNameTruncatesLongNamesPreservingExtension() {
        String longStem = "a".repeat(200);

        String result = DiscordAttachmentStorage.sanitizeFileName(longStem + ".png", null, new HashSet<>());

        assertTrue(result.length() <= 120, "must truncate to the max filename length, was " + result.length());
        assertTrue(result.endsWith(".png"));
    }

    @Test
    void sanitizeFileNameFallsBackToAttachmentWhenNameIsEntirelyDots() {
        String result = DiscordAttachmentStorage.sanitizeFileName("...", null, new HashSet<>());

        assertEquals("attachment", result);
    }

    @Test
    void messageKeyIsDeterministicSixteenHexChars() {
        String key1 = DiscordAttachmentStorage.messageKey("discord://g/c/m1");
        String key2 = DiscordAttachmentStorage.messageKey("discord://g/c/m1");
        String key3 = DiscordAttachmentStorage.messageKey("discord://g/c/m2");

        assertEquals(key1, key2, "the same source_path must always yield the same key");
        assertNotEquals(key1, key3, "different source_path values should yield different keys");
        assertEquals(16, key1.length());
        assertTrue(key1.matches("[0-9a-f]{16}"), key1);
    }

    private static byte[] readAll(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Attachment attachment(String filename, String contentType, long size) {
        return new Attachment("att-id", filename, contentType, size,
                "http://example.invalid/" + filename, "http://example.invalid/" + filename, null, null);
    }

    /** Stubs out network access: hands back a real temp file with fixed bytes, like a real download would. */
    private static final class StubApiService extends DiscordApiService {
        final byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);
        boolean downloadCalled = false;
        Path lastTempFile;

        StubApiService() {
            super("fake-token", Duration.ZERO, "http://unused.invalid");
        }

        @Override
        public Path downloadAttachment(Attachment attachment) throws IOException {
            downloadCalled = true;
            Path temp = Files.createTempFile("discord-attachment-storage-test-", ".bin");
            Files.write(temp, content);
            lastTempFile = temp;
            return temp;
        }
    }
}
