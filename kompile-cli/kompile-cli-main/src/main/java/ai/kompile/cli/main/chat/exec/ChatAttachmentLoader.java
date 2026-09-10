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
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.cli.main.chat.exec;

import ai.kompile.cli.main.chat.config.DirectLlmClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/** Loads bounded headless-chat attachment files into the harness's provider-neutral form. */
public final class ChatAttachmentLoader {

    public static final long MAX_ATTACHMENT_BYTES = 5L * 1024L * 1024L;
    public static final long MAX_TOTAL_BYTES = 20L * 1024L * 1024L;

    private ChatAttachmentLoader() {
    }

    public static List<DirectLlmClient.AttachmentInput> load(List<Path> attachmentPaths)
            throws IOException {
        if (attachmentPaths == null || attachmentPaths.isEmpty()) {
            return List.of();
        }
        List<DirectLlmClient.AttachmentInput> result = new ArrayList<>();
        long total = 0;
        for (Path raw : attachmentPaths) {
            if (raw == null) {
                throw new IOException("Attachment path is required");
            }
            Path path = raw.toAbsolutePath().normalize();
            if (!Files.isRegularFile(path)) {
                throw new IOException("Attachment is not a regular file: " + path);
            }
            long size = Files.size(path);
            if (size > MAX_ATTACHMENT_BYTES) {
                throw new IOException("Attachment exceeds 5 MiB: " + path.getFileName());
            }
            total += size;
            if (total > MAX_TOTAL_BYTES) {
                throw new IOException("Attachments exceed the 20 MiB total limit");
            }

            String mimeType = detectMimeType(path);
            boolean image = mimeType.startsWith("image/");
            if (image) {
                String base64 = Base64.getEncoder().encodeToString(Files.readAllBytes(path));
                result.add(new DirectLlmClient.AttachmentInput(
                        path.toString(), mimeType, true, base64, null));
            } else {
                String text = Files.readString(path, StandardCharsets.UTF_8);
                result.add(new DirectLlmClient.AttachmentInput(
                        path.toString(), mimeType, false, null, text));
            }
        }
        return List.copyOf(result);
    }

    static String detectMimeType(Path path) throws IOException {
        String detected = Files.probeContentType(path);
        if (detected != null && !detected.isBlank()) {
            return detected;
        }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".md")) return "text/markdown";
        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".csv")) return "text/csv";
        return "text/plain";
    }
}
