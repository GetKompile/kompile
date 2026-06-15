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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Parser for Apple Mail's .emlx format.
 *
 * <p>The .emlx format is:</p>
 * <pre>
 * &lt;byte-count&gt;\n
 * &lt;RFC 2822 message of exactly byte-count bytes&gt;
 * &lt;optional Apple plist XML metadata&gt;
 * </pre>
 *
 * <p>This parser reads the byte count prefix, extracts exactly that many
 * bytes of RFC 2822 content, and delegates to {@link Mime4jMessageParser}
 * for full MIME parsing. The trailing Apple plist metadata is discarded
 * (Apple-specific flags, read state, etc.).</p>
 *
 * <p>Also handles loading entire Apple Mail directories. An Apple Mail
 * mailbox bundle is a directory named {@code *.mbox} containing a
 * {@code Messages/} subdirectory with numbered {@code *.emlx} files.</p>
 */
public class EmlxMessageParser {

    private static final Logger logger = LoggerFactory.getLogger(EmlxMessageParser.class);

    private final Mime4jMessageParser mimeParser;

    public EmlxMessageParser() {
        this(new Mime4jMessageParser());
    }

    public EmlxMessageParser(Mime4jMessageParser mimeParser) {
        this.mimeParser = mimeParser;
    }

    /**
     * Parses a single .emlx file into Documents.
     *
     * @param emlxFile   path to the .emlx file
     * @param sourcePath source identifier for metadata
     * @return parsed documents (usually 1 email, plus attachments if enabled)
     */
    public List<Document> parseEmlxFile(Path emlxFile, String sourcePath) throws IOException {
        byte[] fileBytes = Files.readAllBytes(emlxFile);

        // Find the first newline — everything before it is the byte count
        int newlinePos = indexOf(fileBytes, (byte) '\n');
        if (newlinePos < 0) {
            logger.warn("Invalid .emlx file (no byte count line): {}", emlxFile);
            return List.of();
        }

        String byteCountStr = new String(fileBytes, 0, newlinePos, StandardCharsets.US_ASCII).trim();
        int byteCount;
        try {
            byteCount = Integer.parseInt(byteCountStr);
        } catch (NumberFormatException e) {
            // Not a valid .emlx — might be a regular .eml, try parsing directly
            logger.debug("No valid byte count in {}, attempting direct MIME parse", emlxFile);
            return mimeParser.parse(new ByteArrayInputStream(fileBytes), sourcePath);
        }

        // Extract exactly byteCount bytes of RFC 2822 content after the newline
        int messageStart = newlinePos + 1;
        int messageEnd = Math.min(messageStart + byteCount, fileBytes.length);
        byte[] messageBytes = Arrays.copyOfRange(fileBytes, messageStart, messageEnd);

        List<Document> docs = mimeParser.parse(new ByteArrayInputStream(messageBytes), sourcePath);

        // Tag documents with Apple Mail metadata
        for (Document doc : docs) {
            doc.getMetadata().put("email.format", "emlx");
        }

        return docs;
    }

    /**
     * Loads all .emlx messages from an Apple Mail mailbox bundle directory.
     * A mailbox bundle is a {@code *.mbox} directory containing a
     * {@code Messages/} subdirectory with {@code *.emlx} files.
     *
     * @param mboxBundleDir the .mbox bundle directory
     * @param folderName    the logical folder name
     * @return list of parsed documents
     */
    public List<Document> loadMailboxBundle(Path mboxBundleDir, String folderName) throws IOException {
        List<Document> documents = new ArrayList<>();
        Path messagesDir = mboxBundleDir.resolve("Messages");
        if (!Files.isDirectory(messagesDir)) {
            logger.debug("No Messages/ directory in bundle: {}", mboxBundleDir);
            return documents;
        }

        try (Stream<Path> files = Files.list(messagesDir)) {
            files.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().endsWith(".emlx"))
                    .sorted()
                    .forEach(emlxFile -> {
                        try {
                            List<Document> docs = parseEmlxFile(emlxFile, emlxFile.toString());
                            for (Document doc : docs) {
                                doc.getMetadata().put("email.folder", folderName);
                            }
                            documents.addAll(docs);
                        } catch (IOException e) {
                            logger.warn("Failed to parse .emlx file {}: {}",
                                    emlxFile.getFileName(), e.getMessage());
                        }
                    });
        }

        return documents;
    }

    /**
     * Loads all mailbox bundles from an Apple Mail account directory.
     * Walks the directory tree to find all *.mbox/Messages/ paths
     * and parses every .emlx file within them.
     *
     * @param accountDir the Apple Mail account directory (under ~/Library/Mail/Vn/account-id/)
     * @return list of parsed documents from all mailbox bundles
     */
    public List<Document> loadAccountDirectory(Path accountDir) throws IOException {
        List<Document> allDocs = new ArrayList<>();

        walkMailboxBundles(accountDir, "", allDocs);

        logger.info("Loaded {} documents from Apple Mail account: {}",
                allDocs.size(), accountDir.getFileName());
        return allDocs;
    }

    private void walkMailboxBundles(Path dir, String prefix, List<Document> allDocs) {
        if (!Files.isDirectory(dir)) return;

        try (Stream<Path> entries = Files.list(dir)) {
            entries.filter(Files::isDirectory).forEach(subdir -> {
                String name = subdir.getFileName().toString();
                if (name.endsWith(".mbox")) {
                    String folderName = prefix + name.replace(".mbox", "");
                    try {
                        allDocs.addAll(loadMailboxBundle(subdir, folderName));
                    } catch (IOException e) {
                        logger.warn("Error loading mailbox bundle {}: {}", subdir, e.getMessage());
                    }
                    // Recurse for nested bundles
                    walkMailboxBundles(subdir, folderName + "/", allDocs);
                }
            });
        } catch (IOException e) {
            logger.debug("Error walking Apple Mail directory {}: {}", dir, e.getMessage());
        }
    }

    /**
     * Checks if a directory looks like an Apple Mail account directory
     * (contains at least one .mbox bundle with a Messages/ subdirectory).
     */
    public static boolean isAppleMailDirectory(Path dir) {
        if (!Files.isDirectory(dir)) return false;
        try (Stream<Path> walk = Files.walk(dir, 3)) {
            return walk.anyMatch(p ->
                    Files.isDirectory(p) &&
                    p.getFileName().toString().endsWith(".mbox") &&
                    Files.isDirectory(p.resolve("Messages")));
        } catch (IOException e) {
            return false;
        }
    }

    private static int indexOf(byte[] data, byte target) {
        for (int i = 0; i < data.length; i++) {
            if (data[i] == target) return i;
        }
        return -1;
    }
}
