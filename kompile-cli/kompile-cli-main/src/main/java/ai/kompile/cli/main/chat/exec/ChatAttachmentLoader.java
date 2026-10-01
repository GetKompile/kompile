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
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Loads bounded chat attachment files into the harness's provider-neutral form. The gate and
 * the reading are shared by every entry point: a headless {@code --attachment}, the REPL's
 * {@code /image} and {@code /file}, and the REPL turn that sends what they queued.
 */
public final class ChatAttachmentLoader {

    public static final long MAX_ATTACHMENT_BYTES = 5L * 1024L * 1024L;
    public static final long MAX_TOTAL_BYTES = 20L * 1024L * 1024L;

    /**
     * The image types every chat entry point attaches, whatever the route: the ones the
     * remote vision APIs accept. Local serving decodes PNG, JPEG and GIF and names any
     * other type it is sent.
     */
    public static final Set<String> IMAGE_MIME_TYPES =
            Set.of("image/png", "image/jpeg", "image/gif", "image/webp");

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
            String mimeType = attachableMimeType(path);
            total += Files.size(path);
            if (total > MAX_TOTAL_BYTES) {
                throw new IOException("Attachments exceed the 20 MiB total limit");
            }
            result.add(read(path, mimeType, IMAGE_MIME_TYPES.contains(mimeType)));
        }
        return List.copyOf(result);
    }

    /**
     * The per-file gate every chat entry point applies: a regular file within
     * {@link #MAX_ATTACHMENT_BYTES} that is one of {@link #IMAGE_MIME_TYPES} or not an image
     * at all (SVG is XML, so it goes as text).
     *
     * @return the file's MIME type
     * @throws IOException naming why the file can't be attached
     */
    public static String attachableMimeType(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException("Attachment is not a regular file: " + path);
        }
        if (Files.size(path) > MAX_ATTACHMENT_BYTES) {
            throw new IOException("Attachment exceeds 5 MiB: " + path.getFileName());
        }
        String mimeType = detectMimeType(path);
        if (!IMAGE_MIME_TYPES.contains(mimeType) && mimeType.startsWith("image/")
                && !mimeType.equals("image/svg+xml")) {
            throw new IOException("Unsupported image type " + mimeType + ": "
                    + path.getFileName() + " (attach PNG, JPEG, GIF or WebP)");
        }
        return mimeType;
    }

    /** Reads one gated file for a turn: base64 for an image, {@link #readTextContent} otherwise. */
    public static DirectLlmClient.AttachmentInput read(Path path, String mimeType, boolean image)
            throws IOException {
        if (image) {
            String base64 = Base64.getEncoder().encodeToString(Files.readAllBytes(path));
            return new DirectLlmClient.AttachmentInput(path.toString(), mimeType, true, base64, null);
        }
        return new DirectLlmClient.AttachmentInput(
                path.toString(), mimeType, false, null, readTextContent(path));
    }

    /**
     * The text a non-image attachment inlines. A file that isn't UTF-8 text (a PDF, an
     * archive) is not inlined: every route puts the file's path before this text, and the
     * note after it tells the model to open the file with a tool if it needs the contents.
     */
    public static String readTextContent(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .decode(ByteBuffer.wrap(bytes)).toString();
            if (text.indexOf('\0') < 0) {
                return text;
            }
        } catch (CharacterCodingException binary) {
            // not text: described below instead of inlined
        }
        return "[Binary file (" + bytes.length + " bytes), not inlined: open it from the path"
                + " above with a tool if the task needs its contents.]";
    }

    /** The image type a file's extension names, or null when it is none of {@link #IMAGE_MIME_TYPES}. */
    public static String imageMimeType(Path path) {
        Path fileName = path == null ? null : path.getFileName();
        if (fileName == null) return null;
        String name = fileName.toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return switch (dot < 0 ? "" : name.substring(dot + 1)) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            default -> null;
        };
    }

    static String detectMimeType(Path path) throws IOException {
        String image = imageMimeType(path);
        if (image != null) {
            return image;
        }
        String detected = Files.probeContentType(path);
        if (detected != null && !detected.isBlank()) {
            return detected;
        }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".md")) return "text/markdown";
        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".csv")) return "text/csv";
        return "text/plain";
    }
}
