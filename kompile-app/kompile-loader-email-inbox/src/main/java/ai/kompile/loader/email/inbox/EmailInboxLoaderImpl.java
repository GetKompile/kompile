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

import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.core.loaders.DocumentSourceDescriptor.SourceType;
import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.table.TableCellGraphBuilder;
import org.apache.james.mime4j.mboxiterator.CharBufferWrapper;
import org.apache.james.mime4j.mboxiterator.MboxIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Document loader for local email inboxes.
 *
 * <p>Supports two source types:</p>
 * <ul>
 *   <li><b>MBOX</b>: Parses mbox files (concatenated RFC 2822 messages separated by "From " lines)
 *       using the MIME4J MboxIterator for correct message boundary detection.</li>
 *   <li><b>MAILDIR</b>: Walks Maildir directory structures (cur/, new/, tmp/ subdirectories)
 *       and parses each file as an individual RFC 2822 message. Also detects nested
 *       Maildir++ and Thunderbird .sbd folder hierarchies.</li>
 * </ul>
 *
 * <p>Configuration via {@code sourceDescriptor.getMetadata()}:</p>
 * <ul>
 *   <li>{@code includeAttachments} (boolean, default false) — extract attachments as separate documents</li>
 *   <li>{@code includeHtmlBody} (boolean, default true) — convert HTML bodies to text</li>
 *   <li>{@code messageLimit} (int, default 0 = unlimited) — cap the number of messages loaded</li>
 *   <li>{@code folders} (List or comma-separated String) — restrict to specific Maildir subfolders</li>
 * </ul>
 */
@Component
public class EmailInboxLoaderImpl implements DocumentLoader {

    private static final Logger logger = LoggerFactory.getLogger(EmailInboxLoaderImpl.class);

    private static final Set<String> MAILDIR_SUBDIRS = Set.of("cur", "new", "tmp");

    @Override
    public String getName() {
        return "Email Inbox Loader";
    }

    @Override
    public boolean supports(DocumentSourceDescriptor sourceDescriptor) {
        SourceType type = sourceDescriptor.getType();
        if (type == SourceType.MBOX || type == SourceType.MAILDIR
                || type == SourceType.PST || type == SourceType.EMLX_DIR) {
            return true;
        }

        // Also support FILE type for .mbox/.pst files, and DIRECTORY for Maildir/EMLX
        if (type == SourceType.FILE && sourceDescriptor.getPathOrUrl() != null) {
            String path = sourceDescriptor.getPathOrUrl().toLowerCase();
            return path.endsWith(".mbox") || path.endsWith(".mbx")
                    || path.endsWith(".pst") || path.endsWith(".emlx");
        }
        if (type == SourceType.DIRECTORY && sourceDescriptor.getPathOrUrl() != null) {
            Path dirPath = Paths.get(sourceDescriptor.getPathOrUrl());
            return isMaildir(dirPath) || EmlxMessageParser.isAppleMailDirectory(dirPath);
        }

        return false;
    }

    @Override
    public List<Document> load(DocumentSourceDescriptor sourceDescriptor) throws Exception {
        return load(sourceDescriptor, null);
    }

    @Override
    public List<Document> load(DocumentSourceDescriptor sourceDescriptor,
                               Consumer<LoaderProgress> progressCallback) throws Exception {
        Map<String, Object> meta = sourceDescriptor.getMetadata() != null
                ? sourceDescriptor.getMetadata() : Collections.emptyMap();

        boolean includeAttachments = Boolean.TRUE.equals(meta.get("includeAttachments"));
        boolean includeHtmlBody = meta.get("includeHtmlBody") != null
                ? Boolean.TRUE.equals(meta.get("includeHtmlBody")) : true;
        int messageLimit = meta.containsKey("messageLimit")
                ? ((Number) meta.get("messageLimit")).intValue() : 0;

        Mime4jMessageParser parser = new Mime4jMessageParser(includeAttachments, includeHtmlBody);

        Path path = Paths.get(sourceDescriptor.getPathOrUrl());
        SourceType type = sourceDescriptor.getType();

        if (type == SourceType.PST || (type == SourceType.FILE && isPstFile(path))) {
            return loadPst(path, progressCallback);
        } else if (type == SourceType.EMLX_DIR
                || (type == SourceType.DIRECTORY && EmlxMessageParser.isAppleMailDirectory(path))) {
            return loadEmlxDirectory(path, parser, messageLimit, progressCallback);
        } else if (type == SourceType.FILE && isEmlxFile(path)) {
            return loadEmlxFile(path, parser);
        } else if (type == SourceType.MBOX || (type == SourceType.FILE && isMboxFile(path))) {
            return loadMbox(path, parser, messageLimit, progressCallback);
        } else if (type == SourceType.MAILDIR || type == SourceType.DIRECTORY) {
            List<String> folderFilter = parseFolderFilter(meta);
            return loadMaildir(path, parser, messageLimit, folderFilter, progressCallback);
        }

        throw new IllegalArgumentException("Unsupported source type for EmailInboxLoader: " + type);
    }

    // ── mbox loading ──────────────────────────────────────────────────────

    private List<Document> loadMbox(Path mboxPath, Mime4jMessageParser parser,
                                    int messageLimit, Consumer<LoaderProgress> progressCallback)
            throws IOException {

        if (!Files.exists(mboxPath) || !Files.isRegularFile(mboxPath)) {
            throw new IllegalArgumentException("mbox file does not exist: " + mboxPath);
        }

        List<Document> documents = new ArrayList<>();
        long fileSize = Files.size(mboxPath);
        int count = 0;

        logger.info("Loading mbox file: {} ({} bytes)", mboxPath, fileSize);

        try (MboxIterator mbox = MboxIterator.fromFile(mboxPath.toFile())
                .charset(StandardCharsets.UTF_8)
                .build()) {

            Iterator<CharBufferWrapper> iterator = mbox.iterator();
            while (iterator.hasNext()) {
                if (Thread.currentThread().isInterrupted()) {
                    logger.info("Mbox loading interrupted at message {}", count);
                    break;
                }

                if (messageLimit > 0 && count >= messageLimit) {
                    break;
                }

                CharBufferWrapper wrapper = iterator.next();
                count++;

                try {
                    byte[] messageBytes = wrapper.toString().getBytes(StandardCharsets.UTF_8);
                    ByteArrayInputStream bais = new ByteArrayInputStream(messageBytes);
                    List<Document> docs = parser.parse(bais, mboxPath.toString() + "#" + count);

                    // Tag each doc with its position in the mbox
                    for (Document doc : docs) {
                        doc.getMetadata().put("email.mboxIndex", count);
                        doc.getMetadata().put("email.mboxFile", mboxPath.toString());
                    }
                    documents.addAll(docs);
                } catch (Exception e) {
                    logger.warn("Failed to parse message #{} in {}: {}", count, mboxPath, e.getMessage());
                }

                if (progressCallback != null && count % 100 == 0) {
                    progressCallback.accept(new LoaderProgress(
                            "Parsing mbox", -1,
                            "Message " + count, "Parsed " + count + " messages from " + mboxPath.getFileName(),
                            Map.of("messagesProcessed", count, "documentsCreated", documents.size())
                    ));
                }
            }
        }

        logger.info("Loaded {} documents from {} messages in {}", documents.size(), count, mboxPath);
        return documents;
    }

    // ── Maildir loading ───────────────────────────────────────────────────

    private List<Document> loadMaildir(Path maildirRoot, Mime4jMessageParser parser,
                                       int messageLimit, List<String> folderFilter,
                                       Consumer<LoaderProgress> progressCallback)
            throws IOException {

        if (!Files.exists(maildirRoot) || !Files.isDirectory(maildirRoot)) {
            throw new IllegalArgumentException("Maildir directory does not exist: " + maildirRoot);
        }

        List<Document> documents = new ArrayList<>();
        int count = 0;

        // Discover all Maildir folders (including nested Maildir++ and .sbd hierarchies)
        List<MaildirFolder> folders = discoverMaildirFolders(maildirRoot);

        if (!folderFilter.isEmpty()) {
            folders = folders.stream()
                    .filter(f -> folderFilter.stream().anyMatch(
                            filter -> f.name.equalsIgnoreCase(filter) ||
                                    f.name.toLowerCase().contains(filter.toLowerCase())))
                    .toList();
        }

        logger.info("Discovered {} Maildir folders under {}", folders.size(), maildirRoot);

        for (MaildirFolder folder : folders) {
            if (Thread.currentThread().isInterrupted()) break;
            if (messageLimit > 0 && count >= messageLimit) break;

            logger.debug("Processing Maildir folder: {} ({})", folder.name, folder.path);

            for (String subdir : List.of("new", "cur")) {
                Path subdirPath = folder.path.resolve(subdir);
                if (!Files.isDirectory(subdirPath)) continue;

                try (Stream<Path> files = Files.list(subdirPath)) {
                    Iterator<Path> fileIterator = files
                            .filter(Files::isRegularFile)
                            .filter(p -> !p.getFileName().toString().startsWith("."))
                            .iterator();

                    while (fileIterator.hasNext()) {
                        if (Thread.currentThread().isInterrupted()) break;
                        if (messageLimit > 0 && count >= messageLimit) break;

                        Path emailFile = fileIterator.next();
                        count++;

                        try (InputStream is = Files.newInputStream(emailFile)) {
                            List<Document> docs = parser.parse(is, emailFile.toString());
                            for (Document doc : docs) {
                                doc.getMetadata().put("email.folder", folder.name);
                                doc.getMetadata().put("email.maildirSubdir", subdir);
                                doc.getMetadata().put("email.maildirFlags",
                                        parseMaildirFlags(emailFile.getFileName().toString()));
                            }
                            documents.addAll(docs);
                        } catch (Exception e) {
                            logger.warn("Failed to parse email file {}: {}",
                                    emailFile.getFileName(), e.getMessage());
                        }

                        if (progressCallback != null && count % 50 == 0) {
                            progressCallback.accept(new LoaderProgress(
                                    "Parsing Maildir",
                                    folders.size() > 1 ? -1 : -1,
                                    folder.name + "/" + subdir,
                                    "Parsed " + count + " emails, " + documents.size() + " documents",
                                    Map.of("messagesProcessed", count, "documentsCreated", documents.size(),
                                            "currentFolder", folder.name)
                            ));
                        }
                    }
                }
            }
        }

        logger.info("Loaded {} documents from {} messages across {} folders in {}",
                documents.size(), count, folders.size(), maildirRoot);
        return documents;
    }

    // ── Maildir folder discovery ──────────────────────────────────────────

    /**
     * Discovers all Maildir-format folders under a root directory.
     * Supports standard Maildir, Maildir++ (dot-prefixed subfolders),
     * and Thunderbird .sbd hierarchies.
     */
    private List<MaildirFolder> discoverMaildirFolders(Path root) throws IOException {
        List<MaildirFolder> folders = new ArrayList<>();

        // Check if root itself is a Maildir
        if (isMaildir(root)) {
            folders.add(new MaildirFolder("INBOX", root));
        }

        // Maildir++ convention: subfolders are .FolderName under root
        try (Stream<Path> entries = Files.list(root)) {
            entries.filter(Files::isDirectory).forEach(dir -> {
                String name = dir.getFileName().toString();

                // Maildir++ subfolders: .Sent, .Drafts, .Trash, etc.
                if (name.startsWith(".") && !name.equals(".") && !name.equals("..")) {
                    if (isMaildir(dir)) {
                        String folderName = name.substring(1).replace(".", "/");
                        folders.add(new MaildirFolder(folderName, dir));
                    }
                }

                // Thunderbird .sbd directories contain nested folders
                if (name.endsWith(".sbd")) {
                    String baseName = name.substring(0, name.length() - 4);
                    try {
                        discoverThunderbirdFolders(dir, baseName, folders);
                    } catch (IOException e) {
                        logger.warn("Error scanning Thunderbird folder {}: {}", dir, e.getMessage());
                    }
                }

                // Plain subdirectories that are Maildirs (e.g., dovecot layout)
                if (!name.startsWith(".") && isMaildir(dir)) {
                    folders.add(new MaildirFolder(name, dir));
                }
            });
        }

        // Also look for mbox files at the root level (Thunderbird stores
        // each folder as an mbox file alongside its .sbd directory)
        try (Stream<Path> entries = Files.list(root)) {
            entries.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .filter(this::looksLikeMbox)
                    .forEach(mboxFile -> {
                        String name = mboxFile.getFileName().toString();
                        // Skip index files
                        if (!name.endsWith(".msf") && !name.endsWith(".dat")) {
                            folders.add(new MaildirFolder("mbox:" + name, mboxFile));
                        }
                    });
        }

        return folders;
    }

    private void discoverThunderbirdFolders(Path sbdDir, String parentName,
                                             List<MaildirFolder> folders) throws IOException {
        try (Stream<Path> entries = Files.list(sbdDir)) {
            entries.forEach(entry -> {
                String name = entry.getFileName().toString();
                if (Files.isRegularFile(entry) && !name.endsWith(".msf") && !name.endsWith(".dat")) {
                    if (looksLikeMbox(entry)) {
                        folders.add(new MaildirFolder(parentName + "/" + name, entry));
                    }
                }
                if (Files.isDirectory(entry) && name.endsWith(".sbd")) {
                    String childName = parentName + "/" + name.substring(0, name.length() - 4);
                    try {
                        discoverThunderbirdFolders(entry, childName, folders);
                    } catch (IOException e) {
                        logger.warn("Error scanning nested Thunderbird folder {}: {}", entry, e.getMessage());
                    }
                }
            });
        }
    }

    // ── PST loading ────────────────────────────────────────────────────────

    private List<Document> loadPst(Path pstPath, Consumer<LoaderProgress> progressCallback)
            throws Exception {
        if (!Files.exists(pstPath) || !Files.isRegularFile(pstPath)) {
            throw new IllegalArgumentException("PST file does not exist: " + pstPath);
        }

        logger.info("Loading Outlook PST file: {} ({} bytes)", pstPath, Files.size(pstPath));
        List<Document> documents = new ArrayList<>();

        com.pff.PSTFile pstFile = new com.pff.PSTFile(pstPath.toFile());
        com.pff.PSTFolder rootFolder = pstFile.getRootFolder();
        loadPstFolder(rootFolder, documents, pstPath, progressCallback);

        logger.info("Loaded {} documents from PST: {}", documents.size(), pstPath);
        return documents;
    }

    private void loadPstFolder(com.pff.PSTFolder folder, List<Document> documents,
                                Path pstPath, Consumer<LoaderProgress> progressCallback)
            throws Exception {
        if (folder.getContentCount() > 0) {
            Object nextChild = folder.getNextChild();
            while (nextChild != null) {
                if (Thread.currentThread().isInterrupted()) break;

                // Handle PST contacts, tasks, and appointments as distinct document types
                if (nextChild instanceof com.pff.PSTContact contact) {
                    Document contactDoc = loadPstContact(contact, folder, pstPath, documents.size());
                    if (contactDoc != null) documents.add(contactDoc);
                    nextChild = folder.getNextChild();
                    continue;
                }
                if (nextChild instanceof com.pff.PSTTask task) {
                    Document taskDoc = loadPstTask(task, folder, pstPath, documents.size());
                    if (taskDoc != null) documents.add(taskDoc);
                    nextChild = folder.getNextChild();
                    continue;
                }
                if (nextChild instanceof com.pff.PSTAppointment appointment) {
                    Document apptDoc = loadPstAppointment(appointment, folder, pstPath, documents.size());
                    if (apptDoc != null) documents.add(apptDoc);
                    nextChild = folder.getNextChild();
                    continue;
                }
                if (!(nextChild instanceof com.pff.PSTMessage message)) {
                    nextChild = folder.getNextChild();
                    continue;
                }

                StringBuilder content = new StringBuilder();
                content.append("Subject: ").append(message.getSubject()).append("\n");

                String senderName = message.getSenderName();
                String senderEmail = message.getSenderEmailAddress();
                if (senderName != null && !senderName.isEmpty()) {
                    content.append("From: ").append(senderName);
                    if (senderEmail != null && !senderEmail.isEmpty()) {
                        content.append(" <").append(senderEmail).append(">");
                    }
                    content.append("\n");
                }

                String displayTo = message.getDisplayTo();
                if (displayTo != null && !displayTo.isEmpty()) {
                    content.append("To: ").append(displayTo).append("\n");
                }

                String displayCc = message.getDisplayCC();
                if (displayCc != null && !displayCc.isEmpty()) {
                    content.append("Cc: ").append(displayCc).append("\n");
                }

                String displayBcc = message.getDisplayBCC();
                if (displayBcc != null && !displayBcc.isEmpty()) {
                    content.append("Bcc: ").append(displayBcc).append("\n");
                }

                if (message.getClientSubmitTime() != null) {
                    content.append("Date: ").append(message.getClientSubmitTime()).append("\n");
                }

                content.append("\n");
                String body = message.getBody();
                String htmlBody = message.getBodyHTML();
                if (body != null && !body.isEmpty()) {
                    content.append(body);
                } else if (htmlBody != null && !htmlBody.isEmpty()) {
                    content.append(convertPstHtmlToText(htmlBody));
                }

                Map<String, Object> meta = new HashMap<>();
                String msgId = message.getInternetMessageId();
                String sourcePath = (msgId != null && !msgId.isEmpty())
                        ? "pst:" + pstPath.getFileName() + "#" + msgId
                        : "pst:" + pstPath.getFileName() + "#" + folder.getDisplayName() + "/" + documents.size();
                meta.put(GraphConstants.META_SOURCE, sourcePath);
                meta.put(GraphConstants.META_SOURCE_PATH, sourcePath);
                meta.put(GraphConstants.META_SOURCE_TYPE, "OUTLOOK_PST");
                meta.put(GraphConstants.META_LOADER, "Email Inbox Loader");
                meta.put(GraphConstants.META_DOCUMENT_TYPE, "email");
                meta.put(GraphConstants.META_FILE_NAME, message.getSubject() != null && !message.getSubject().isEmpty()
                        ? message.getSubject() : "Email " + documents.size());
                meta.put(GraphConstants.META_CONTENT_TYPE_HINT, "email");
                if (message.getSubject() != null) meta.put("email.subject", message.getSubject());
                if (senderEmail != null && !senderEmail.isEmpty()) {
                    meta.put("email.from", senderName != null && !senderName.isEmpty()
                            ? senderName + " <" + senderEmail + ">"
                            : senderEmail);
                    meta.put("email.fromAddress", senderEmail);
                }
                if (senderName != null && !senderName.isEmpty()) {
                    meta.put("email.fromName", senderName);
                }
                if (displayTo != null && !displayTo.isEmpty()) {
                    meta.put("email.to", displayTo);
                }
                if (displayCc != null && !displayCc.isEmpty()) {
                    meta.put("email.cc", displayCc);
                }
                if (displayBcc != null && !displayBcc.isEmpty()) {
                    meta.put("email.bcc", displayBcc);
                }
                if (message.getClientSubmitTime() != null) {
                    meta.put("email.date", message.getClientSubmitTime().toInstant().toString());
                }
                if (msgId != null && !msgId.isEmpty()) {
                    meta.put("email.messageId", msgId);
                }
                String inReplyTo = message.getInReplyToId();
                if (inReplyTo != null && !inReplyTo.isEmpty()) {
                    meta.put("email.inReplyTo", inReplyTo);
                }

                // Extract headers from transport message headers
                String transportHeaders = message.getTransportMessageHeaders();
                if (transportHeaders != null && !transportHeaders.isEmpty()) {
                    String refsLine = extractHeaderValue(transportHeaders, "References");
                    if (refsLine != null && !refsLine.isEmpty()) {
                        List<String> refsList = java.util.Arrays.stream(refsLine.trim().split("\\s+"))
                                .filter(s -> !s.isBlank())
                                .collect(java.util.stream.Collectors.toList());
                        if (!refsList.isEmpty()) {
                            meta.put("email.references", refsList);
                        }
                    }

                    // Received headers (multi-value — one per SMTP hop)
                    List<String> receivedHeaders = extractAllHeaderValues(transportHeaders, "Received");
                    if (!receivedHeaders.isEmpty()) {
                        meta.put(GraphConstants.META_EMAIL_RECEIVED_HEADERS, receivedHeaders);
                    }

                    // List-Id, List-Unsubscribe
                    String listId = extractHeaderValue(transportHeaders, "List-Id");
                    if (listId != null && !listId.isBlank()) {
                        meta.put(GraphConstants.META_EMAIL_LIST_ID, listId.trim());
                    }
                    String listUnsub = extractHeaderValue(transportHeaders, "List-Unsubscribe");
                    if (listUnsub != null && !listUnsub.isBlank()) {
                        meta.put(GraphConstants.META_EMAIL_LIST_UNSUBSCRIBE, listUnsub.trim());
                    }

                    // X-Mailer / User-Agent
                    String xMailer = extractHeaderValue(transportHeaders, "X-Mailer");
                    if (xMailer != null && !xMailer.isBlank()) {
                        meta.put(GraphConstants.META_EMAIL_MAILER, xMailer.trim());
                    }
                    String userAgent = extractHeaderValue(transportHeaders, "User-Agent");
                    if (userAgent != null && !userAgent.isBlank()) {
                        meta.put(GraphConstants.META_EMAIL_USER_AGENT, userAgent.trim());
                    }

                    // Authentication-Results
                    String authResults = extractHeaderValue(transportHeaders, "Authentication-Results");
                    if (authResults != null && !authResults.isBlank()) {
                        meta.put(GraphConstants.META_EMAIL_AUTH_RESULTS, authResults.trim());
                        String authLower = authResults.toLowerCase();
                        java.util.regex.Matcher dkimM = java.util.regex.Pattern.compile("\\bdkim=(\\w+)").matcher(authLower);
                        if (dkimM.find()) meta.put(GraphConstants.META_EMAIL_DKIM_RESULT, dkimM.group(1));
                        java.util.regex.Matcher spfM = java.util.regex.Pattern.compile("\\bspf=(\\w+)").matcher(authLower);
                        if (spfM.find()) meta.put(GraphConstants.META_EMAIL_SPF_RESULT, spfM.group(1));
                        java.util.regex.Matcher dmarcM = java.util.regex.Pattern.compile("\\bdmarc=(\\w+)").matcher(authLower);
                        if (dmarcM.find()) meta.put(GraphConstants.META_EMAIL_DMARC_RESULT, dmarcM.group(1));
                    }

                    // Return-Path
                    String returnPath = extractHeaderValue(transportHeaders, "Return-Path");
                    if (returnPath != null && !returnPath.isBlank()) {
                        meta.put(GraphConstants.META_EMAIL_RETURN_PATH, returnPath.trim());
                    }

                    // Thread-Topic (Outlook conversation topic)
                    String threadTopic = extractHeaderValue(transportHeaders, "Thread-Topic");
                    if (threadTopic != null && !threadTopic.isBlank()) {
                        meta.put(GraphConstants.META_EMAIL_CONVERSATION_TOPIC, threadTopic.trim());
                    }

                    // Priority / Importance
                    String xPriority = extractHeaderValue(transportHeaders, "X-Priority");
                    if (xPriority != null && !xPriority.isBlank()) {
                        meta.put(GraphConstants.META_EMAIL_PRIORITY, xPriority.trim());
                    }
                    String importance = extractHeaderValue(transportHeaders, "Importance");
                    if (importance != null && !importance.isBlank()) {
                        meta.put(GraphConstants.META_EMAIL_IMPORTANCE, importance.trim());
                    }

                    // Auto-Submitted / Precedence
                    String autoSubmitted = extractHeaderValue(transportHeaders, "Auto-Submitted");
                    if (autoSubmitted != null && !autoSubmitted.isBlank()) {
                        meta.put(GraphConstants.META_EMAIL_AUTO_SUBMITTED, autoSubmitted.trim());
                        if (!"no".equalsIgnoreCase(autoSubmitted.trim())) {
                            meta.put(GraphConstants.META_EMAIL_IS_AUTO_REPLY, true);
                        }
                    }
                    String precedence = extractHeaderValue(transportHeaders, "Precedence");
                    if (precedence != null && !precedence.isBlank()) {
                        String prec = precedence.trim().toLowerCase();
                        if ("bulk".equals(prec) || "junk".equals(prec) || "list".equals(prec)) {
                            meta.put(GraphConstants.META_EMAIL_PRECEDENCE, prec);
                        }
                    }
                }

                meta.put("email.pstFolder", folder.getDisplayName());

                // Preserve raw HTML in metadata for downstream processors
                if (htmlBody != null && !htmlBody.isEmpty()) {
                    meta.put("email.htmlBody", htmlBody);
                    // Extract tables from HTML body for graph indexing
                    if (htmlBody.contains("<table")) {
                        extractPstEmailHtmlTables(htmlBody, sourcePath, meta);
                    }
                }

                // Extract attachment names for EmailGraphExtractor's HAS_ATTACHMENT path
                try {
                    int numAttachments = message.getNumberOfAttachments();
                    if (numAttachments > 0) {
                        List<String> attachmentNames = new ArrayList<>();
                        for (int ai = 0; ai < numAttachments; ai++) {
                            com.pff.PSTAttachment attachment = message.getAttachment(ai);
                            String filename = attachment.getLongFilename();
                            if (filename == null || filename.isEmpty()) {
                                filename = attachment.getFilename();
                            }
                            if (filename != null && !filename.isEmpty()) {
                                attachmentNames.add(filename);
                            }
                        }
                        if (!attachmentNames.isEmpty()) {
                            meta.put("email.attachmentNames", new ArrayList<>(attachmentNames));
                            meta.put("email.attachmentCount", numAttachments);
                        }
                    }
                } catch (Exception e) {
                    logger.debug("Failed to extract PST attachment names: {}", e.getMessage());
                }

                documents.add(new Document(content.toString(), meta));
                nextChild = folder.getNextChild();

                if (progressCallback != null && documents.size() % 100 == 0) {
                    progressCallback.accept(new LoaderProgress(
                            "Parsing PST", -1,
                            folder.getDisplayName(),
                            "Parsed " + documents.size() + " messages from " + pstPath.getFileName(),
                            Map.of("messagesProcessed", documents.size(),
                                    "currentFolder", folder.getDisplayName())
                    ));
                }
            }
        }

        if (folder.hasSubfolders()) {
            for (com.pff.PSTFolder childFolder : folder.getSubFolders()) {
                loadPstFolder(childFolder, documents, pstPath, progressCallback);
            }
        }
    }

    // ── PST special item types ─────────────────────────────────────────

    private Document loadPstContact(com.pff.PSTContact contact, com.pff.PSTFolder folder,
                                     Path pstPath, int docIndex) {
        try {
            StringBuilder content = new StringBuilder();
            String displayName = contact.getDisplayName();
            content.append("Contact: ").append(displayName != null ? displayName : "Unknown").append("\n");

            String email1 = contact.getEmail1EmailAddress();
            if (email1 != null && !email1.isEmpty()) content.append("Email: ").append(email1).append("\n");
            String email2 = contact.getEmail2EmailAddress();
            if (email2 != null && !email2.isEmpty()) content.append("Email 2: ").append(email2).append("\n");

            String businessPhone = contact.getBusinessTelephoneNumber();
            if (businessPhone != null && !businessPhone.isEmpty()) content.append("Business Phone: ").append(businessPhone).append("\n");
            String mobilePhone = contact.getMobileTelephoneNumber();
            if (mobilePhone != null && !mobilePhone.isEmpty()) content.append("Mobile: ").append(mobilePhone).append("\n");
            String homePhone = contact.getHomeTelephoneNumber();
            if (homePhone != null && !homePhone.isEmpty()) content.append("Home Phone: ").append(homePhone).append("\n");

            String company = contact.getCompanyName();
            if (company != null && !company.isEmpty()) content.append("Company: ").append(company).append("\n");
            String jobTitle = contact.getTitle();
            if (jobTitle != null && !jobTitle.isEmpty()) content.append("Title: ").append(jobTitle).append("\n");
            String department = contact.getDepartmentName();
            if (department != null && !department.isEmpty()) content.append("Department: ").append(department).append("\n");

            String businessAddress = contact.getPostalAddress();
            if (businessAddress != null && !businessAddress.isEmpty()) content.append("Address: ").append(businessAddress).append("\n");

            Map<String, Object> meta = new HashMap<>();
            String sourcePath = "pst:" + pstPath.getFileName() + "#contact:" + (email1 != null ? email1 : docIndex);
            meta.put(GraphConstants.META_SOURCE, sourcePath);
            meta.put(GraphConstants.META_SOURCE_PATH, sourcePath);
            meta.put(GraphConstants.META_SOURCE_TYPE, "OUTLOOK_PST");
            meta.put(GraphConstants.META_LOADER, "Email Inbox Loader");
            meta.put(GraphConstants.META_DOCUMENT_TYPE, "contact");
            meta.put(GraphConstants.META_CONTENT_TYPE, "contact");
            meta.put(GraphConstants.META_FILE_NAME, displayName != null ? displayName : "Contact " + docIndex);
            meta.put("contact.displayName", displayName);
            if (email1 != null && !email1.isEmpty()) meta.put("contact.email", email1);
            if (email2 != null && !email2.isEmpty()) meta.put("contact.email2", email2);
            if (businessPhone != null && !businessPhone.isEmpty()) meta.put("contact.businessPhone", businessPhone);
            if (mobilePhone != null && !mobilePhone.isEmpty()) meta.put("contact.mobilePhone", mobilePhone);
            if (homePhone != null && !homePhone.isEmpty()) meta.put("contact.homePhone", homePhone);
            if (company != null && !company.isEmpty()) meta.put("contact.company", company);
            if (jobTitle != null && !jobTitle.isEmpty()) meta.put("contact.jobTitle", jobTitle);
            if (department != null && !department.isEmpty()) meta.put("contact.department", department);
            if (businessAddress != null && !businessAddress.isEmpty()) meta.put("contact.address", businessAddress);
            meta.put("email.pstFolder", folder.getDisplayName());

            return new Document(content.toString(), meta);
        } catch (Exception e) {
            logger.debug("Failed to extract PST contact: {}", e.getMessage());
            return null;
        }
    }

    private Document loadPstTask(com.pff.PSTTask task, com.pff.PSTFolder folder,
                                  Path pstPath, int docIndex) {
        try {
            StringBuilder content = new StringBuilder();
            String subject = task.getSubject();
            content.append("Task: ").append(subject != null ? subject : "Untitled").append("\n");

            String body = task.getBody();
            if (body != null && !body.isEmpty()) content.append("\n").append(body);

            Map<String, Object> meta = new HashMap<>();
            String sourcePath = "pst:" + pstPath.getFileName() + "#task:" + docIndex;
            meta.put(GraphConstants.META_SOURCE, sourcePath);
            meta.put(GraphConstants.META_SOURCE_PATH, sourcePath);
            meta.put(GraphConstants.META_SOURCE_TYPE, "OUTLOOK_PST");
            meta.put(GraphConstants.META_LOADER, "Email Inbox Loader");
            meta.put(GraphConstants.META_DOCUMENT_TYPE, "task");
            meta.put(GraphConstants.META_CONTENT_TYPE, "task");
            meta.put(GraphConstants.META_FILE_NAME, subject != null ? subject : "Task " + docIndex);
            if (subject != null) meta.put("task.subject", subject);
            double percentComplete = task.getPercentComplete();
            meta.put("task.percentComplete", percentComplete);
            meta.put("task.complete", task.isTaskComplete());
            if (task.getTaskDueDate() != null) {
                meta.put("task.dueDate", task.getTaskDueDate().toInstant().toString());
            }
            if (task.getTaskStartDate() != null) {
                meta.put("task.startDate", task.getTaskStartDate().toInstant().toString());
            }
            String owner = task.getTaskOwner();
            if (owner != null && !owner.isEmpty()) meta.put("task.owner", owner);
            meta.put("email.pstFolder", folder.getDisplayName());

            return new Document(content.toString(), meta);
        } catch (Exception e) {
            logger.debug("Failed to extract PST task: {}", e.getMessage());
            return null;
        }
    }

    private Document loadPstAppointment(com.pff.PSTAppointment appointment, com.pff.PSTFolder folder,
                                         Path pstPath, int docIndex) {
        try {
            StringBuilder content = new StringBuilder();
            String subject = appointment.getSubject();
            content.append("Appointment: ").append(subject != null ? subject : "Untitled").append("\n");

            if (appointment.getLocation() != null && !appointment.getLocation().isEmpty()) {
                content.append("Location: ").append(appointment.getLocation()).append("\n");
            }
            if (appointment.getStartTime() != null) {
                content.append("Start: ").append(appointment.getStartTime()).append("\n");
            }
            if (appointment.getEndTime() != null) {
                content.append("End: ").append(appointment.getEndTime()).append("\n");
            }

            String body = appointment.getBody();
            if (body != null && !body.isEmpty()) content.append("\n").append(body);

            Map<String, Object> meta = new HashMap<>();
            String sourcePath = "pst:" + pstPath.getFileName() + "#appointment:" + docIndex;
            meta.put(GraphConstants.META_SOURCE, sourcePath);
            meta.put(GraphConstants.META_SOURCE_PATH, sourcePath);
            meta.put(GraphConstants.META_SOURCE_TYPE, "OUTLOOK_PST");
            meta.put(GraphConstants.META_LOADER, "Email Inbox Loader");
            meta.put(GraphConstants.META_DOCUMENT_TYPE, "calendar_event");
            meta.put(GraphConstants.META_CONTENT_TYPE, "calendar_event");
            meta.put(GraphConstants.META_FILE_NAME, subject != null ? subject : "Appointment " + docIndex);
            if (subject != null) meta.put("calendar.subject", subject);
            if (appointment.getLocation() != null && !appointment.getLocation().isEmpty()) {
                meta.put("calendar.location", appointment.getLocation());
            }
            if (appointment.getStartTime() != null) {
                meta.put("calendar.startTime", appointment.getStartTime().toInstant().toString());
            }
            if (appointment.getEndTime() != null) {
                meta.put("calendar.endTime", appointment.getEndTime().toInstant().toString());
            }
            meta.put("calendar.allDayEvent", appointment.getSubType());
            meta.put("calendar.isRecurring", appointment.isRecurring());
            String organizer = appointment.getSenderName();
            if (organizer != null && !organizer.isEmpty()) {
                meta.put("calendar.organizer", organizer);
            }
            String organizerEmail = appointment.getSenderEmailAddress();
            if (organizerEmail != null && !organizerEmail.isEmpty()) {
                meta.put("calendar.organizerEmail", organizerEmail);
            }
            meta.put("email.pstFolder", folder.getDisplayName());

            return new Document(content.toString(), meta);
        } catch (Exception e) {
            logger.debug("Failed to extract PST appointment: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Extracts tables from PST email HTML bodies using regex + TableCellGraphBuilder.
     * Stores cell-level graph JSON in metadata for the pipeline to persist.
     */
    private void extractPstEmailHtmlTables(String html, String sourcePath, Map<String, Object> metadata) {
        try {
            java.util.regex.Pattern tablePattern = java.util.regex.Pattern.compile(
                    "<table[^>]*>(.*?)</table>", java.util.regex.Pattern.DOTALL | java.util.regex.Pattern.CASE_INSENSITIVE);
            java.util.regex.Matcher tableMatcher = tablePattern.matcher(html);
            List<Graph> graphs = new ArrayList<>();
            int tableIdx = 0;

            while (tableMatcher.find() && tableIdx < 10) {
                String tableHtml = tableMatcher.group(1);
                // Strip nested tables to avoid parsing inner table rows as outer table data
                tableHtml = tableHtml.replaceAll("(?si)<table[^>]*>.*?</table>", "");
                java.util.regex.Pattern rowPattern = java.util.regex.Pattern.compile(
                        "<tr[^>]*>(.*?)</tr>", java.util.regex.Pattern.DOTALL | java.util.regex.Pattern.CASE_INSENSITIVE);
                java.util.regex.Matcher rowMatcher = rowPattern.matcher(tableHtml);

                List<List<String>> allRows = new ArrayList<>();
                boolean firstRowIsHeader = false;

                while (rowMatcher.find()) {
                    String rowHtml = rowMatcher.group(1);
                    List<String> cells = new ArrayList<>();
                    boolean rowHasHeaders = false;
                    java.util.regex.Pattern cellPattern = java.util.regex.Pattern.compile(
                            "<(th|td)[^>]*>(.*?)</\\1>", java.util.regex.Pattern.DOTALL | java.util.regex.Pattern.CASE_INSENSITIVE);
                    java.util.regex.Matcher cellMatcher = cellPattern.matcher(rowHtml);
                    while (cellMatcher.find()) {
                        if ("th".equalsIgnoreCase(cellMatcher.group(1))) rowHasHeaders = true;
                        String cellText = cellMatcher.group(2).replaceAll("<[^>]+>", "").trim();
                        cellText = cellText.replace("&amp;", "&").replace("&lt;", "<")
                                .replace("&gt;", ">").replace("&nbsp;", " ").replace("&quot;", "\"");
                        cells.add(cellText);
                    }
                    if (!cells.isEmpty()) {
                        if (allRows.isEmpty() && rowHasHeaders) firstRowIsHeader = true;
                        allRows.add(cells);
                    }
                }

                if (allRows.size() < 2) continue;

                List<String> headers = firstRowIsHeader ? allRows.get(0) : null;
                TableCellGraphBuilder builder = new TableCellGraphBuilder()
                        .namespace("pst-email:" + sourcePath + "/tbl:" + tableIdx)
                        .tableName("Email-Table-" + (tableIdx + 1))
                        .firstRowIsHeader(firstRowIsHeader);
                if (headers != null) builder.headers(headers);
                for (List<String> row : allRows) {
                    builder.addRow(row);
                }
                Graph cellGraph = builder.build();
                if (!cellGraph.getEntities().isEmpty()) {
                    graphs.add(cellGraph);
                    tableIdx++;
                }
            }

            if (!graphs.isEmpty()) {
                List<ai.kompile.core.graphrag.model.Entity> allEntities = new ArrayList<>();
                List<ai.kompile.core.graphrag.model.Relationship> allRels = new ArrayList<>();
                for (Graph g : graphs) {
                    allEntities.addAll(g.getEntities());
                    allRels.addAll(g.getRelationships());
                }
                Graph combined = new Graph();
                combined.setEntities(allEntities);
                combined.setRelationships(allRels);
                metadata.put(GraphConstants.META_TABLE_GRAPH, TableCellGraphBuilder.toJson(combined));
                metadata.put("email.tableCount", tableIdx);
            }
        } catch (Exception e) {
            logger.debug("Failed to extract tables from PST email HTML body: {}", e.getMessage());
        }
    }

    // ── EMLX loading ─────────────────────────────────────────────────────

    private List<Document> loadEmlxDirectory(Path dir, Mime4jMessageParser parser,
                                              int messageLimit,
                                              Consumer<LoaderProgress> progressCallback)
            throws IOException {
        logger.info("Loading Apple Mail directory: {}", dir);
        EmlxMessageParser emlxParser = new EmlxMessageParser(parser);
        List<Document> docs = emlxParser.loadAccountDirectory(dir);

        if (messageLimit > 0 && docs.size() > messageLimit) {
            docs = docs.subList(0, messageLimit);
        }

        if (progressCallback != null) {
            progressCallback.accept(new LoaderProgress(
                    "Parsing EMLX", -1, dir.getFileName().toString(),
                    "Loaded " + docs.size() + " messages from Apple Mail",
                    Map.of("documentsLoaded", docs.size())
            ));
        }

        return docs;
    }

    private List<Document> loadEmlxFile(Path file, Mime4jMessageParser parser) throws IOException {
        EmlxMessageParser emlxParser = new EmlxMessageParser(parser);
        return emlxParser.parseEmlxFile(file, file.toString());
    }

    // ── Utility methods ───────────────────────────────────────────────────

    static boolean isMaildir(Path dir) {
        if (!Files.isDirectory(dir)) return false;
        // A Maildir has at least cur/ and new/ subdirectories
        return Files.isDirectory(dir.resolve("cur")) && Files.isDirectory(dir.resolve("new"));
    }

    private boolean isMboxFile(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".mbox") || name.endsWith(".mbx");
    }

    private boolean isPstFile(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".pst") || name.endsWith(".ost");
    }

    private boolean isEmlxFile(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".emlx");
    }

    private boolean looksLikeMbox(Path file) {
        if (!Files.isRegularFile(file)) return false;
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".mbox") || name.endsWith(".mbx")) return true;

        // Thunderbird stores mbox files without extension; sniff the first line
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String firstLine = reader.readLine();
            return firstLine != null && firstLine.startsWith("From ");
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Parses Maildir info flags from the filename.
     * Maildir filenames have the format: unique_id:2,FLAGS
     * Flags: S=Seen, R=Replied, F=Flagged, T=Trashed, D=Draft, P=Passed
     */
    static String parseMaildirFlags(String filename) {
        int infoSep = filename.lastIndexOf(":2,");
        if (infoSep < 0) return "";
        return filename.substring(infoSep + 3);
    }

    @SuppressWarnings("unchecked")
    private List<String> parseFolderFilter(Map<String, Object> meta) {
        Object foldersObj = meta.get("folders");
        if (foldersObj instanceof List) {
            return (List<String>) foldersObj;
        } else if (foldersObj instanceof String) {
            return Arrays.asList(((String) foldersObj).split(","));
        }
        return Collections.emptyList();
    }

    /**
     * Converts HTML to structured plain text for PST messages.
     * Similar to Mime4jMessageParser.convertHtmlToText — removes non-content
     * elements and inserts newlines around block elements.
     */
    private String convertPstHtmlToText(String html) {
        if (html == null) return null;

        org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(html);
        doc.select("script, style, head, nav, footer").remove();

        doc.select("br").before("\\n");
        doc.select("p, div, h1, h2, h3, h4, h5, h6, li, tr").before("\\n\\n");

        String text = doc.text().replace("\\n", "\n");
        text = text.replaceAll("\\n{3,}", "\n\n");
        text = text.replaceAll("[ \\t]+", " ");
        return text.trim();
    }

    /**
     * Extracts the value of a specific header from raw RFC 2822 headers text.
     * Handles continuation lines (lines starting with whitespace).
     */
    private static String extractHeaderValue(String headers, String headerName) {
        String prefix = headerName + ":";
        String prefixLower = prefix.toLowerCase();
        StringBuilder value = new StringBuilder();
        boolean found = false;
        for (String line : headers.split("\r?\n")) {
            if (found) {
                // Continuation line (starts with whitespace)
                if (!line.isEmpty() && (line.charAt(0) == ' ' || line.charAt(0) == '\t')) {
                    value.append(" ").append(line.trim());
                } else {
                    break;
                }
            } else if (line.toLowerCase().startsWith(prefixLower)) {
                found = true;
                value.append(line.substring(prefix.length()).trim());
            }
        }
        return found ? value.toString() : null;
    }

    /**
     * Extracts ALL values for a multi-value header (e.g. Received) from raw RFC 2822 headers.
     * Returns a list with one entry per occurrence, respecting continuation lines.
     */
    private static List<String> extractAllHeaderValues(String headers, String headerName) {
        List<String> results = new ArrayList<>();
        String prefix = headerName + ":";
        String prefixLower = prefix.toLowerCase();
        StringBuilder current = null;
        for (String line : headers.split("\r?\n")) {
            if (current != null) {
                if (!line.isEmpty() && (line.charAt(0) == ' ' || line.charAt(0) == '\t')) {
                    current.append(" ").append(line.trim());
                    continue;
                } else {
                    results.add(current.toString());
                    current = null;
                }
            }
            if (line.toLowerCase().startsWith(prefixLower)) {
                current = new StringBuilder(line.substring(prefix.length()).trim());
            }
        }
        if (current != null) results.add(current.toString());
        return results;
    }

    /**
     * Represents a discovered Maildir folder or mbox file.
     */
    record MaildirFolder(String name, Path path) {}
}
