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

import ai.kompile.core.crawler.*;
import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.core.loaders.DocumentSourceDescriptor.SourceType;
import ai.kompile.crawler.AbstractCrawlJob;
import ai.kompile.crawler.AbstractCrawler;
import org.apache.james.mime4j.dom.Message;
import org.apache.james.mime4j.message.DefaultMessageBuilder;
import org.apache.james.mime4j.mboxiterator.CharBufferWrapper;
import org.apache.james.mime4j.mboxiterator.MboxIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.pff.PSTAttachment;
import com.pff.PSTFile;
import com.pff.PSTFolder;
import com.pff.PSTMessage;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

/**
 * Crawler for local email inboxes. Discovers email messages in:
 * <ul>
 *   <li><b>Maildir</b> directories (cur/new/tmp subdirectory layout)</li>
 *   <li><b>mbox</b> files (concatenated RFC 2822 messages)</li>
 *   <li><b>Thunderbird</b> profiles (.sbd hierarchies + mbox files)</li>
 *   <li><b>PST</b> files (Outlook Personal Storage Table)</li>
 *   <li><b>EMLX</b> directories (Apple Mail)</li>
 * </ul>
 *
 * <p>Supports incremental crawling via last-modified timestamps, so only
 * new or changed emails are re-indexed on subsequent runs.</p>
 *
 * <p>Configuration via {@code config.getProperties()}:</p>
 * <ul>
 *   <li>{@code includeHidden} (boolean, default false) — include hidden files/dirs</li>
 *   <li>{@code folders} (comma-separated String) — restrict to specific folder names</li>
 *   <li>{@code discover} (boolean, default false) — auto-discover local mailboxes instead of using seed path</li>
 *   <li>{@code discoverClients} (comma-separated String) — restrict discovery to specific clients
 *       (thunderbird, outlook, applemail, evolution, kmail, geary)</li>
 * </ul>
 */
@Component
public class EmailInboxCrawler extends AbstractCrawler {

    private static final Logger logger = LoggerFactory.getLogger(EmailInboxCrawler.class);

    @Override
    public String getId() {
        return "email-inbox";
    }

    @Override
    public String getName() {
        return "Email Inbox Crawler";
    }

    @Override
    public String getDescription() {
        return "Crawls local email inboxes in Maildir, mbox, PST, and Apple Mail EMLX formats";
    }

    @Override
    public Set<SourceType> getSupportedSourceTypes() {
        return Set.of(SourceType.MAILDIR, SourceType.MBOX, SourceType.PST, SourceType.EMLX_DIR);
    }

    @Override
    public List<String> validate(CrawlConfig config) {
        boolean discoverMode = Boolean.parseBoolean(
                String.valueOf(config.getProperties().getOrDefault("discover", "false")));
        if (discoverMode) {
            // Skip seed validation in discover mode — we auto-discover mailboxes
            List<String> errors = new ArrayList<>();
            errors.addAll(validateSpecific(config));
            return errors;
        }
        return super.validate(config);
    }

    @Override
    protected List<String> validateSpecific(CrawlConfig config) {
        List<String> errors = new ArrayList<>();

        // Discovery mode doesn't need a valid seed path
        boolean discoverMode = Boolean.parseBoolean(
                String.valueOf(config.getProperties().getOrDefault("discover", "false")));
        if (discoverMode) {
            return errors;
        }

        Path seed = Paths.get(config.getSeed());

        if (!Files.exists(seed)) {
            errors.add("Path does not exist: " + config.getSeed());
            return errors;
        }

        if (Files.isDirectory(seed)) {
            if (!Files.isReadable(seed)) {
                errors.add("Directory is not readable: " + config.getSeed());
            }
        } else if (Files.isRegularFile(seed)) {
            if (!Files.isReadable(seed)) {
                errors.add("File is not readable: " + config.getSeed());
            }
        } else {
            errors.add("Path is neither a file nor directory: " + config.getSeed());
        }

        return errors;
    }

    @Override
    protected AbstractCrawlJob createJob(String jobId, CrawlConfig config, CrawlEventListener listener) {
        return new EmailInboxCrawlJob(jobId, config, listener);
    }

    @Override
    protected void executeCrawl(AbstractCrawlJob abstractJob) throws Exception {
        EmailInboxCrawlJob job = (EmailInboxCrawlJob) abstractJob;
        CrawlConfig config = job.getConfig();

        boolean includeHidden = Boolean.parseBoolean(
                String.valueOf(config.getProperties().getOrDefault("includeHidden", "false")));
        boolean extractAttachments = Boolean.parseBoolean(
                String.valueOf(config.getProperties().getOrDefault("extractAttachments", "true")));
        boolean discoverMode = Boolean.parseBoolean(
                String.valueOf(config.getProperties().getOrDefault("discover", "false")));
        List<String> folderFilter = parseFolderFilter(config);

        EmailAttachmentExtractor attachmentExtractor = extractAttachments
                ? new EmailAttachmentExtractor() : null;

        if (discoverMode) {
            crawlDiscoveredMailboxes(job, config, includeHidden, folderFilter, attachmentExtractor);
            return;
        }

        Path seedPath = Paths.get(config.getSeed());

        if (Files.isRegularFile(seedPath)) {
            String name = seedPath.getFileName().toString().toLowerCase();
            if (name.endsWith(".pst") || name.endsWith(".ost")) {
                crawlPstFile(seedPath, job, config, attachmentExtractor);
            } else {
                crawlMboxFile(seedPath, job, config, attachmentExtractor);
            }
        } else if (Files.isDirectory(seedPath)) {
            if (EmlxMessageParser.isAppleMailDirectory(seedPath)) {
                crawlEmlxDirectory(seedPath, job, config, attachmentExtractor);
            } else {
                crawlDirectory(seedPath, job, config, includeHidden, folderFilter, attachmentExtractor);
            }
        }
    }

    // ── mbox file crawl ───────────────────────────────────────────────────

    private void crawlMboxFile(Path mboxPath, EmailInboxCrawlJob job, CrawlConfig config,
                               EmailAttachmentExtractor attachmentExtractor)
            throws IOException {

        logger.info("Crawling mbox file: {}", mboxPath);

        // Check if file itself has been modified
        long fileModified = Files.getLastModifiedTime(mboxPath).toMillis();
        if (!job.shouldProcess(mboxPath.toString(), fileModified)) {
            logger.info("Skipping unmodified mbox file: {}", mboxPath);
            job.getListener().onDocumentSkipped(mboxPath.toString(), "Not modified since last crawl");
            job.incrementSkipped();
            return;
        }

        int messageIndex = 0;

        try (MboxIterator mbox = MboxIterator.fromFile(mboxPath.toFile())
                .charset(StandardCharsets.UTF_8)
                .build()) {

            Iterator<CharBufferWrapper> iterator = mbox.iterator();
            while (iterator.hasNext()) {
                if (!job.checkPauseAndContinue() || job.shouldStop()) break;

                CharBufferWrapper wrapper = iterator.next();
                messageIndex++;

                String itemUrl = mboxPath + "#" + messageIndex;
                job.setCurrentItem(itemUrl);

                Map<String, Object> mboxItemMeta = new LinkedHashMap<>();
                mboxItemMeta.put("mboxIndex", messageIndex);
                mboxItemMeta.put("mboxFile", mboxPath.toString());
                mboxItemMeta.put(GraphConstants.META_SOURCE, itemUrl);
                mboxItemMeta.put(GraphConstants.META_SOURCE_PATH, itemUrl);
                mboxItemMeta.put(GraphConstants.META_FILE_NAME, mboxPath.getFileName().toString());
                mboxItemMeta.put(GraphConstants.META_LOADER, "Email Inbox Crawler");
                mboxItemMeta.put(GraphConstants.META_DOCUMENT_TYPE, "email");

                CrawlItem item = CrawlItem.builder()
                        .url(itemUrl)
                        .parentUrl(mboxPath.toString())
                        .depth(0)
                        .sourceDescriptor(DocumentSourceDescriptor.builder()
                                .type(SourceType.MBOX)
                                .pathOrUrl(mboxPath.toString())
                                .sourceId(itemUrl)
                                .originalFileName(mboxPath.getFileName().toString())
                                .collectionName(config.getCollectionName())
                                .metadata(Map.of("mboxIndex", messageIndex))
                                .build())
                        .contentType("message/rfc822")
                        .discoveredAt(Instant.now())
                        .metadata(mboxItemMeta)
                        .build();

                job.incrementDiscovered();
                job.getListener().onDocumentDiscovered(item);
                job.incrementProcessed();
                job.getListener().onDocumentProcessed(item);

                // Extract and emit attachment CrawlItems
                if (attachmentExtractor != null) {
                    try {
                        String rawMessage = wrapper.toString();
                        // MboxIterator includes the "From " separator line; strip it
                        // so DefaultMessageBuilder sees a clean RFC 2822 message
                        rawMessage = stripMboxFromLine(rawMessage);
                        byte[] messageBytes = rawMessage.getBytes(StandardCharsets.UTF_8);
                        DefaultMessageBuilder builder = new DefaultMessageBuilder();
                        Message message = builder.parseMessage(new ByteArrayInputStream(messageBytes));
                        emitAttachmentItems(message, itemUrl, job, config, attachmentExtractor);
                    } catch (Exception e) {
                        logger.warn("Could not extract attachments from mbox message #{}: {}",
                                messageIndex, e.getMessage());
                    }
                }

                if (messageIndex % 100 == 0) {
                    reportProgress(job);
                }
            }
        }

        job.markVisited(mboxPath.toString(), fileModified);
        logger.info("Discovered {} messages in mbox: {}", messageIndex, mboxPath);
    }

    // ── Directory crawl ───────────────────────────────────────────────────

    private void crawlDirectory(Path root, EmailInboxCrawlJob job, CrawlConfig config,
                                boolean includeHidden, List<String> folderFilter,
                                EmailAttachmentExtractor attachmentExtractor)
            throws IOException {

        logger.info("Crawling email directory: {}", root);

        // Discover all Maildir folders and mbox files
        List<EmailInboxLoaderImpl.MaildirFolder> folders = discoverFolders(root, includeHidden);

        if (!folderFilter.isEmpty()) {
            folders = folders.stream()
                    .filter(f -> folderFilter.stream().anyMatch(
                            filter -> f.name().equalsIgnoreCase(filter) ||
                                    f.name().toLowerCase().contains(filter.toLowerCase())))
                    .toList();
        }

        logger.info("Discovered {} email folders under {}", folders.size(), root);

        for (EmailInboxLoaderImpl.MaildirFolder folder : folders) {
            if (!job.checkPauseAndContinue() || job.shouldStop()) break;

            if (Files.isRegularFile(folder.path())) {
                crawlMboxFile(folder.path(), job, config, attachmentExtractor);
            } else {
                crawlMaildirFolder(folder, job, config, includeHidden, attachmentExtractor);
            }
        }
    }

    private void crawlMaildirFolder(EmailInboxLoaderImpl.MaildirFolder folder,
                                     EmailInboxCrawlJob job, CrawlConfig config,
                                     boolean includeHidden,
                                     EmailAttachmentExtractor attachmentExtractor) throws IOException {

        logger.debug("Crawling Maildir folder: {} at {}", folder.name(), folder.path());

        for (String subdir : List.of("new", "cur")) {
            Path subdirPath = folder.path().resolve(subdir);
            if (!Files.isDirectory(subdirPath)) continue;
            if (!job.checkPauseAndContinue() || job.shouldStop()) break;

            try (Stream<Path> files = Files.list(subdirPath)) {
                Iterator<Path> fileIterator = files
                        .filter(Files::isRegularFile)
                        .filter(p -> includeHidden || !p.getFileName().toString().startsWith("."))
                        .sorted()
                        .iterator();

                while (fileIterator.hasNext()) {
                    if (!job.checkPauseAndContinue() || job.shouldStop()) break;

                    Path emailFile = fileIterator.next();
                    String filePath = emailFile.toString();

                    // Incremental: skip unmodified files
                    long modified = Files.getLastModifiedTime(emailFile).toMillis();
                    if (!job.shouldProcess(filePath, modified)) {
                        job.getListener().onDocumentSkipped(filePath, "Not modified since last crawl");
                        job.incrementSkipped();
                        continue;
                    }

                    job.setCurrentItem(filePath);

                    long fileSize = Files.size(emailFile);
                    String flags = EmailInboxLoaderImpl.parseMaildirFlags(
                            emailFile.getFileName().toString());

                    Map<String, Object> itemMeta = new HashMap<>();
                    itemMeta.put("folder", folder.name());
                    itemMeta.put("maildirSubdir", subdir);
                    itemMeta.put("maildirFlags", flags);
                    itemMeta.put(GraphConstants.META_SOURCE, filePath);
                    itemMeta.put(GraphConstants.META_SOURCE_PATH, filePath);
                    itemMeta.put(GraphConstants.META_FILE_NAME, emailFile.getFileName().toString());
                    itemMeta.put(GraphConstants.META_LOADER, "Email Inbox Crawler");
                    itemMeta.put(GraphConstants.META_DOCUMENT_TYPE, "email");

                    CrawlItem item = CrawlItem.builder()
                            .url(filePath)
                            .parentUrl(folder.path().toString())
                            .depth(0)
                            .sourceDescriptor(DocumentSourceDescriptor.builder()
                                    .type(SourceType.MAILDIR)
                                    .pathOrUrl(filePath)
                                    .sourceId(filePath)
                                    .originalFileName(emailFile.getFileName().toString())
                                    .collectionName(config.getCollectionName())
                                    .metadata(itemMeta)
                                    .build())
                            .contentType("message/rfc822")
                            .contentLength(fileSize)
                            .discoveredAt(Instant.now())
                            .metadata(itemMeta)
                            .build();

                    job.incrementDiscovered();
                    job.getListener().onDocumentDiscovered(item);
                    job.incrementProcessed();
                    job.getListener().onDocumentProcessed(item);
                    job.markVisited(filePath, modified);

                    // Extract and emit attachment CrawlItems
                    if (attachmentExtractor != null) {
                        try (InputStream is = Files.newInputStream(emailFile)) {
                            DefaultMessageBuilder builder = new DefaultMessageBuilder();
                            Message message = builder.parseMessage(is);
                            emitAttachmentItems(message, filePath, job, config, attachmentExtractor);
                        } catch (Exception e) {
                            logger.debug("Could not extract attachments from {}: {}",
                                    filePath, e.getMessage());
                        }
                    }

                    if (job.getDiscoveredCount() % 50 == 0) {
                        reportProgress(job);
                    }
                }
            }
        }
    }

    // ── Discovery mode ────────────────────────────────────────────────────

    /**
     * Auto-discovers local email client mailboxes and crawls them all.
     * Uses {@link MailboxDiscoveryService} to find Thunderbird, Outlook,
     * Apple Mail, Evolution, KMail, and other local email stores.
     */
    private void crawlDiscoveredMailboxes(EmailInboxCrawlJob job, CrawlConfig config,
                                           boolean includeHidden, List<String> folderFilter,
                                           EmailAttachmentExtractor attachmentExtractor) throws Exception {

        MailboxDiscoveryService discoveryService = new MailboxDiscoveryService();
        List<DiscoveredMailbox> mailboxes = discoveryService.discoverAll();

        // Optionally filter by client name
        Object clientsObj = config.getProperties().get("discoverClients");
        if (clientsObj instanceof String clientStr && !clientStr.isBlank()) {
            Set<String> allowed = new HashSet<>(Arrays.asList(clientStr.toLowerCase().split(",")));
            mailboxes = mailboxes.stream()
                    .filter(mb -> allowed.contains(mb.clientName().toLowerCase())
                            || allowed.contains(mb.clientName().toLowerCase().replace(" ", "")))
                    .toList();
        }

        logger.info("Auto-discovered {} mailbox locations", mailboxes.size());

        for (DiscoveredMailbox mailbox : mailboxes) {
            if (!job.checkPauseAndContinue() || job.shouldStop()) break;

            logger.info("Crawling discovered mailbox: {}", mailbox);

            try {
                switch (mailbox.sourceType()) {
                    case PST -> crawlPstFile(mailbox.path(), job, config, attachmentExtractor);
                    case EMLX_DIR -> crawlEmlxDirectory(mailbox.path(), job, config, attachmentExtractor);
                    case MBOX -> {
                        if (Files.isRegularFile(mailbox.path())) {
                            crawlMboxFile(mailbox.path(), job, config, attachmentExtractor);
                        } else {
                            crawlDirectory(mailbox.path(), job, config, includeHidden,
                                    folderFilter, attachmentExtractor);
                        }
                    }
                    case MAILDIR -> crawlDirectory(mailbox.path(), job, config, includeHidden,
                            folderFilter, attachmentExtractor);
                    default -> logger.warn("Unsupported source type for discovered mailbox: {}",
                            mailbox.sourceType());
                }
            } catch (Exception e) {
                logger.warn("Error crawling discovered mailbox {}: {}", mailbox.path(), e.getMessage());
            }
        }
    }

    // ── PST file crawl ───────────────────────────────────────────────────

    /**
     * Crawls an Outlook PST file, emitting a CrawlItem for each message.
     */
    private void crawlPstFile(Path pstPath, EmailInboxCrawlJob job, CrawlConfig config,
                              EmailAttachmentExtractor attachmentExtractor)
            throws Exception {

        logger.info("Crawling PST file: {}", pstPath);

        long fileModified = Files.getLastModifiedTime(pstPath).toMillis();
        if (!job.shouldProcess(pstPath.toString(), fileModified)) {
            logger.info("Skipping unmodified PST file: {}", pstPath);
            job.getListener().onDocumentSkipped(pstPath.toString(), "Not modified since last crawl");
            job.incrementSkipped();
            return;
        }

        PSTFile pstFile = new PSTFile(pstPath.toFile());
        PSTFolder rootFolder = pstFile.getRootFolder();
        crawlPstFolder(rootFolder, pstPath, job, config, attachmentExtractor);

        job.markVisited(pstPath.toString(), fileModified);
        logger.info("Finished crawling PST: {}", pstPath);
    }

    private void crawlPstFolder(PSTFolder folder, Path pstPath,
                                 EmailInboxCrawlJob job, CrawlConfig config,
                                 EmailAttachmentExtractor attachmentExtractor) throws Exception {

        String folderName = folder.getDisplayName();
        if (folderName == null) folderName = "root";

        if (folder.getContentCount() > 0) {
            PSTMessage message = (PSTMessage) folder.getNextChild();
            int messageIndex = 0;

            while (message != null) {
                if (!job.checkPauseAndContinue() || job.shouldStop()) break;
                messageIndex++;

                String messageId = message.getInternetMessageId();
                String itemUrl = pstPath + "#" + folderName + "/" + messageIndex;
                if (messageId != null && !messageId.isEmpty()) {
                    itemUrl = pstPath + "#" + messageId;
                }

                job.setCurrentItem(itemUrl);

                Map<String, Object> itemMeta = new HashMap<>();
                itemMeta.put("pstFolder", folderName);
                itemMeta.put("pstFile", pstPath.toString());
                if (message.getSubject() != null) itemMeta.put("subject", message.getSubject());
                if (message.getSenderEmailAddress() != null) {
                    itemMeta.put("from", message.getSenderEmailAddress());
                }
                itemMeta.put(GraphConstants.META_SOURCE, itemUrl);
                itemMeta.put(GraphConstants.META_SOURCE_PATH, itemUrl);
                itemMeta.put(GraphConstants.META_FILE_NAME, pstPath.getFileName().toString());
                itemMeta.put(GraphConstants.META_LOADER, "Email Inbox Crawler");
                itemMeta.put(GraphConstants.META_DOCUMENT_TYPE, "email");

                CrawlItem item = CrawlItem.builder()
                        .url(itemUrl)
                        .parentUrl(pstPath.toString())
                        .depth(0)
                        .sourceDescriptor(DocumentSourceDescriptor.builder()
                                .type(SourceType.PST)
                                .pathOrUrl(pstPath.toString())
                                .sourceId(itemUrl)
                                .originalFileName(pstPath.getFileName().toString())
                                .collectionName(config.getCollectionName())
                                .metadata(itemMeta)
                                .build())
                        .contentType("message/rfc822")
                        .discoveredAt(Instant.now())
                        .metadata(itemMeta)
                        .build();

                job.incrementDiscovered();
                job.getListener().onDocumentDiscovered(item);
                job.incrementProcessed();
                job.getListener().onDocumentProcessed(item);

                // Extract attachments from PST message
                if (attachmentExtractor != null) {
                    emitPstAttachmentItems(message, itemUrl, job, config);
                }

                if (job.getDiscoveredCount() % 100 == 0) {
                    reportProgress(job);
                }

                message = (PSTMessage) folder.getNextChild();
            }
        }

        if (folder.hasSubfolders()) {
            for (PSTFolder childFolder : folder.getSubFolders()) {
                if (!job.checkPauseAndContinue() || job.shouldStop()) break;
                crawlPstFolder(childFolder, pstPath, job, config, attachmentExtractor);
            }
        }
    }

    /**
     * Extracts attachments from a PST message and emits CrawlItems for each.
     * Uses the java-libpst API directly since PST attachments use a different
     * format from MIME attachments handled by {@link EmailAttachmentExtractor}.
     */
    private void emitPstAttachmentItems(PSTMessage message, String parentUrl,
                                         EmailInboxCrawlJob job, CrawlConfig config) {
        try {
            int numAttachments = message.getNumberOfAttachments();
            if (numAttachments == 0) return;

            for (int i = 0; i < numAttachments; i++) {
                if (!job.checkPauseAndContinue() || job.shouldStop()) break;

                PSTAttachment attachment = message.getAttachment(i);
                String filename = attachment.getLongFilename();
                if (filename == null || filename.isEmpty()) {
                    filename = attachment.getFilename();
                }
                if (filename == null || filename.isEmpty()) {
                    filename = "attachment-" + i;
                }

                String mimeType = attachment.getMimeTag();
                if (mimeType == null || mimeType.isEmpty()) {
                    mimeType = "application/octet-stream";
                }

                int attachSize = 0;
                Path tempFile = null;
                try {
                    attachSize = attachment.getFilesize();
                    // Write attachment content to temp file for downstream loaders
                    InputStream is = attachment.getFileInputStream();
                    if (is != null) {
                        tempFile = Files.createTempFile("pst-attach-", "-" + filename);
                        tempFile.toFile().deleteOnExit();
                        Files.copy(is, tempFile, StandardCopyOption.REPLACE_EXISTING);
                        is.close();
                        attachSize = (int) Files.size(tempFile);
                    }
                } catch (Exception e) {
                    logger.debug("Could not extract PST attachment content for {}: {}",
                            filename, e.getMessage());
                }

                String attachUrl = parentUrl + "#attachment:" + filename;

                Map<String, Object> attachMeta = new LinkedHashMap<>();
                attachMeta.put("email.isAttachment", true);
                attachMeta.put("email.parentMessageId", message.getInternetMessageId());
                if (message.getSubject() != null) {
                    attachMeta.put("email.parentSubject", message.getSubject());
                }
                if (message.getSenderEmailAddress() != null) {
                    attachMeta.put("email.from", message.getSenderEmailAddress());
                }
                attachMeta.put("email.attachmentName", filename);
                attachMeta.put("email.attachmentMimeType", mimeType);
                attachMeta.put("email.attachmentSize", attachSize);
                attachMeta.put(GraphConstants.META_SOURCE, attachUrl);
                attachMeta.put(GraphConstants.META_SOURCE_PATH, attachUrl);
                attachMeta.put(GraphConstants.META_FILE_NAME, filename);
                attachMeta.put(GraphConstants.META_LOADER, "Email Inbox Crawler");
                attachMeta.put(GraphConstants.META_SOURCE_TYPE, "EMAIL_ATTACHMENT");

                DocumentSourceDescriptor attachDescriptor = DocumentSourceDescriptor.builder()
                        .type(SourceType.FILE)
                        .pathOrUrl(tempFile != null ? tempFile.toString() : attachUrl)
                        .originalFileName(filename)
                        .collectionName(config.getCollectionName())
                        .metadata(attachMeta)
                        .build();

                CrawlItem attachItem = CrawlItem.builder()
                        .url(attachUrl)
                        .parentUrl(parentUrl)
                        .depth(1)
                        .sourceDescriptor(attachDescriptor)
                        .contentType(mimeType)
                        .contentLength((long) attachSize)
                        .discoveredAt(Instant.now())
                        .metadata(new LinkedHashMap<>(attachMeta))
                        .build();

                job.incrementDiscovered();
                job.getListener().onDocumentDiscovered(attachItem);
                job.incrementProcessed();
                job.getListener().onDocumentProcessed(attachItem);

                logger.debug("Emitted PST attachment CrawlItem: {} ({}, {} bytes)",
                        filename, mimeType, attachSize);
            }
        } catch (Exception e) {
            logger.warn("Failed to extract attachments from PST message {}: {}",
                    parentUrl, e.getMessage());
        }
    }

    // ── EMLX directory crawl ─────────────────────────────────────────────

    /**
     * Crawls an Apple Mail directory, emitting a CrawlItem for each .emlx file.
     */
    private void crawlEmlxDirectory(Path emlxDir, EmailInboxCrawlJob job, CrawlConfig config,
                                     EmailAttachmentExtractor attachmentExtractor) throws Exception {

        logger.info("Crawling Apple Mail directory: {}", emlxDir);

        walkEmlxBundles(emlxDir, "", job, config, attachmentExtractor);

        logger.info("Finished crawling Apple Mail directory: {}", emlxDir);
    }

    private void walkEmlxBundles(Path dir, String prefix, EmailInboxCrawlJob job,
                                  CrawlConfig config, EmailAttachmentExtractor attachmentExtractor)
            throws IOException {

        if (!Files.isDirectory(dir)) return;

        try (Stream<Path> entries = Files.list(dir)) {
            List<Path> subdirs = entries.filter(Files::isDirectory).sorted().toList();
            for (Path subdir : subdirs) {
                if (!job.checkPauseAndContinue() || job.shouldStop()) break;

                String name = subdir.getFileName().toString();
                if (name.endsWith(".mbox")) {
                    String folderName = prefix + name.replace(".mbox", "");
                    Path messagesDir = subdir.resolve("Messages");
                    if (Files.isDirectory(messagesDir)) {
                        crawlEmlxMessagesDir(messagesDir, folderName, job, config, attachmentExtractor);
                    }
                    // Recurse for nested bundles
                    walkEmlxBundles(subdir, folderName + "/", job, config, attachmentExtractor);
                }
            }
        }
    }

    private void crawlEmlxMessagesDir(Path messagesDir, String folderName,
                                       EmailInboxCrawlJob job, CrawlConfig config,
                                       EmailAttachmentExtractor attachmentExtractor) throws IOException {

        try (Stream<Path> files = Files.list(messagesDir)) {
            List<Path> emlxFiles = files
                    .filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().endsWith(".emlx"))
                    .sorted()
                    .toList();

            for (Path emlxFile : emlxFiles) {
                if (!job.checkPauseAndContinue() || job.shouldStop()) break;

                String filePath = emlxFile.toString();
                long modified = Files.getLastModifiedTime(emlxFile).toMillis();
                if (!job.shouldProcess(filePath, modified)) {
                    job.getListener().onDocumentSkipped(filePath, "Not modified since last crawl");
                    job.incrementSkipped();
                    continue;
                }

                job.setCurrentItem(filePath);
                long fileSize = Files.size(emlxFile);

                Map<String, Object> itemMeta = new HashMap<>();
                itemMeta.put("folder", folderName);
                itemMeta.put("format", "emlx");
                itemMeta.put(GraphConstants.META_SOURCE, filePath);
                itemMeta.put(GraphConstants.META_SOURCE_PATH, filePath);
                itemMeta.put(GraphConstants.META_FILE_NAME, emlxFile.getFileName().toString());
                itemMeta.put(GraphConstants.META_LOADER, "Email Inbox Crawler");
                itemMeta.put(GraphConstants.META_DOCUMENT_TYPE, "email");

                CrawlItem item = CrawlItem.builder()
                        .url(filePath)
                        .parentUrl(messagesDir.getParent().toString())
                        .depth(0)
                        .sourceDescriptor(DocumentSourceDescriptor.builder()
                                .type(SourceType.EMLX_DIR)
                                .pathOrUrl(filePath)
                                .sourceId(filePath)
                                .originalFileName(emlxFile.getFileName().toString())
                                .collectionName(config.getCollectionName())
                                .metadata(itemMeta)
                                .build())
                        .contentType("message/rfc822")
                        .contentLength(fileSize)
                        .discoveredAt(Instant.now())
                        .metadata(itemMeta)
                        .build();

                job.incrementDiscovered();
                job.getListener().onDocumentDiscovered(item);
                job.incrementProcessed();
                job.getListener().onDocumentProcessed(item);
                job.markVisited(filePath, modified);

                // Extract attachments from EMLX
                if (attachmentExtractor != null) {
                    try {
                        EmlxMessageParser emlxParser = new EmlxMessageParser();
                        // We need to re-parse to get the MIME message for attachment extraction
                        byte[] fileBytes = Files.readAllBytes(emlxFile);
                        int newlinePos = indexOf(fileBytes, (byte) '\n');
                        if (newlinePos >= 0) {
                            String byteCountStr = new String(fileBytes, 0, newlinePos,
                                    StandardCharsets.UTF_8).trim();
                            try {
                                int byteCount = Integer.parseInt(byteCountStr);
                                int messageStart = newlinePos + 1;
                                int messageEnd = Math.min(messageStart + byteCount, fileBytes.length);
                                byte[] messageBytes = Arrays.copyOfRange(fileBytes, messageStart, messageEnd);
                                DefaultMessageBuilder builder = new DefaultMessageBuilder();
                                Message message = builder.parseMessage(new ByteArrayInputStream(messageBytes));
                                emitAttachmentItems(message, filePath, job, config, attachmentExtractor);
                            } catch (NumberFormatException e) {
                                // Not a valid byte count, try parsing full file as MIME
                                DefaultMessageBuilder builder = new DefaultMessageBuilder();
                                Message message = builder.parseMessage(new ByteArrayInputStream(fileBytes));
                                emitAttachmentItems(message, filePath, job, config, attachmentExtractor);
                            }
                        }
                    } catch (Exception e) {
                        logger.debug("Could not extract attachments from {}: {}",
                                filePath, e.getMessage());
                    }
                }

                if (job.getDiscoveredCount() % 50 == 0) {
                    reportProgress(job);
                }
            }
        }
    }

    // ── Folder discovery ──────────────────────────────────────────────────

    /**
     * Discovers Maildir folders and mbox files under a root directory.
     * Reuses the discovery logic from {@link EmailInboxLoaderImpl}.
     */
    private List<EmailInboxLoaderImpl.MaildirFolder> discoverFolders(Path root, boolean includeHidden)
            throws IOException {

        List<EmailInboxLoaderImpl.MaildirFolder> folders = new ArrayList<>();

        if (EmailInboxLoaderImpl.isMaildir(root)) {
            folders.add(new EmailInboxLoaderImpl.MaildirFolder("INBOX", root));
        }

        try (Stream<Path> entries = Files.list(root)) {
            entries.filter(Files::isDirectory)
                    .filter(d -> includeHidden || !d.getFileName().toString().startsWith(".") ||
                            // Always include Maildir++ dot-folders
                            (d.getFileName().toString().startsWith(".") &&
                                    EmailInboxLoaderImpl.isMaildir(d)))
                    .forEach(dir -> {
                        String name = dir.getFileName().toString();

                        // Maildir++ subfolders
                        if (name.startsWith(".") && EmailInboxLoaderImpl.isMaildir(dir)) {
                            String folderName = name.substring(1).replace(".", "/");
                            folders.add(new EmailInboxLoaderImpl.MaildirFolder(folderName, dir));
                        }

                        // Thunderbird .sbd directories
                        if (name.endsWith(".sbd")) {
                            discoverThunderbirdFolders(dir, name.substring(0, name.length() - 4), folders);
                        }

                        // Plain subdirectories that are Maildirs
                        if (!name.startsWith(".") && EmailInboxLoaderImpl.isMaildir(dir)) {
                            folders.add(new EmailInboxLoaderImpl.MaildirFolder(name, dir));
                        }
                    });
        }

        // Look for mbox files at root level
        try (Stream<Path> entries = Files.list(root)) {
            entries.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .filter(p -> looksLikeMbox(p))
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return !n.endsWith(".msf") && !n.endsWith(".dat");
                    })
                    .forEach(mboxFile -> folders.add(
                            new EmailInboxLoaderImpl.MaildirFolder(
                                    "mbox:" + mboxFile.getFileName(), mboxFile)));
        }

        return folders;
    }

    private void discoverThunderbirdFolders(Path sbdDir, String parentName,
                                             List<EmailInboxLoaderImpl.MaildirFolder> folders) {
        try (Stream<Path> entries = Files.list(sbdDir)) {
            entries.forEach(entry -> {
                String name = entry.getFileName().toString();
                if (Files.isRegularFile(entry) && !name.endsWith(".msf") && !name.endsWith(".dat")) {
                    if (looksLikeMbox(entry)) {
                        folders.add(new EmailInboxLoaderImpl.MaildirFolder(
                                parentName + "/" + name, entry));
                    }
                }
                if (Files.isDirectory(entry) && name.endsWith(".sbd")) {
                    discoverThunderbirdFolders(entry,
                            parentName + "/" + name.substring(0, name.length() - 4), folders);
                }
            });
        } catch (IOException e) {
            logger.warn("Error scanning Thunderbird folder {}: {}", sbdDir, e.getMessage());
        }
    }

    private boolean looksLikeMbox(Path file) {
        if (!Files.isRegularFile(file)) return false;
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".mbox") || name.endsWith(".mbx")) return true;

        try (java.io.BufferedReader reader = Files.newBufferedReader(file)) {
            String firstLine = reader.readLine();
            return firstLine != null && firstLine.startsWith("From ");
        } catch (IOException e) {
            return false;
        }
    }

    // ── Attachment emission ─────────────────────────────────────────────

    /**
     * Parses a MIME message and emits separate CrawlItems for each attachment.
     * Each attachment is extracted to a temp file and emitted with its native
     * MIME type (e.g., application/pdf) so the pipeline router can dispatch
     * it to the appropriate loader (PDF, Office, etc.).
     */
    private void emitAttachmentItems(Message message, String parentUrl,
                                      EmailInboxCrawlJob job, CrawlConfig config,
                                      EmailAttachmentExtractor extractor) {
        // Build quick parent metadata for linking attachments back to their email
        Map<String, Object> emailMeta = new HashMap<>();
        if (message.getHeader().getField("Message-ID") != null) {
            emailMeta.put("email.messageId", message.getHeader().getField("Message-ID").getBody().trim());
        }
        if (message.getSubject() != null) {
            emailMeta.put("email.subject", message.getSubject());
        }
        if (message.getFrom() != null && !message.getFrom().isEmpty()) {
            emailMeta.put("email.from", message.getFrom().get(0).getAddress());
        }
        if (message.getDate() != null) {
            emailMeta.put("email.date", message.getDate().toInstant().toString());
        }

        List<EmailAttachmentExtractor.ExtractedAttachment> attachments =
                extractor.extractAttachments(message, parentUrl, emailMeta);

        for (EmailAttachmentExtractor.ExtractedAttachment attachment : attachments) {
            if (!job.checkPauseAndContinue() || job.shouldStop()) break;

            String attachFilename = attachment.originalFilename() != null
                    ? attachment.originalFilename() : "unnamed";
            String attachUrl = parentUrl + "#attachment:" + attachFilename;

            // Set collectionName on the descriptor to match the crawl config
            attachment.sourceDescriptor().setCollectionName(config.getCollectionName());

            CrawlItem attachItem = CrawlItem.builder()
                    .url(attachUrl)
                    .parentUrl(parentUrl)
                    .depth(1)
                    .sourceDescriptor(attachment.sourceDescriptor())
                    .contentType(attachment.mimeType())
                    .contentLength(attachment.sizeBytes())
                    .discoveredAt(Instant.now())
                    .metadata(new LinkedHashMap<>(attachment.sourceDescriptor().getMetadata()))
                    .build();

            job.incrementDiscovered();
            job.getListener().onDocumentDiscovered(attachItem);
            job.incrementProcessed();
            job.getListener().onDocumentProcessed(attachItem);

            logger.debug("Emitted attachment CrawlItem: {} ({}, {} bytes)",
                    attachFilename, attachment.mimeType(), attachment.sizeBytes());
        }
    }

    // ── Utility ───────────────────────────────────────────────────────────

    /**
     * Cleans up the raw message from MboxIterator for re-parsing.
     * MboxIterator strips the "From " envelope line but may leave a leading
     * newline that causes DefaultMessageBuilder to treat the entire content
     * as a plain text body instead of parsing headers. Also handles the case
     * where the "From " line is still present.
     */
    static String stripMboxFromLine(String rawMessage) {
        if (rawMessage == null) return rawMessage;
        // Strip leading whitespace/newlines that MboxIterator may leave
        String trimmed = rawMessage;
        while (trimmed.startsWith("\r") || trimmed.startsWith("\n")) {
            trimmed = trimmed.substring(1);
        }
        // Also strip "From " envelope line if still present
        if (trimmed.startsWith("From ")) {
            int rnIndex = trimmed.indexOf("\r\n");
            int nIndex = trimmed.indexOf('\n');
            if (rnIndex >= 0 && (nIndex < 0 || rnIndex <= nIndex)) {
                trimmed = trimmed.substring(rnIndex + 2);
            } else if (nIndex >= 0) {
                trimmed = trimmed.substring(nIndex + 1);
            }
        }
        return trimmed;
    }

    private List<String> parseFolderFilter(CrawlConfig config) {
        Object foldersObj = config.getProperties().get("folders");
        if (foldersObj instanceof String && !((String) foldersObj).isBlank()) {
            return Arrays.asList(((String) foldersObj).split(","));
        }
        return Collections.emptyList();
    }

    private void reportProgress(EmailInboxCrawlJob job) {
        job.getListener().onProgress(job.getProgress());
    }

    private static int indexOf(byte[] data, byte target) {
        for (int i = 0; i < data.length; i++) {
            if (data[i] == target) return i;
        }
        return -1;
    }
}
