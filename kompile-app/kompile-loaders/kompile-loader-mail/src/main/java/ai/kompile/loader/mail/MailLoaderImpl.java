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

package ai.kompile.loader.mail;

import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.apache.james.mime4j.dom.Message;
import org.apache.james.mime4j.dom.MessageBuilder;
import org.apache.james.mime4j.dom.Multipart;
import org.apache.james.mime4j.dom.TextBody;
import org.apache.james.mime4j.dom.address.Mailbox;
import org.apache.james.mime4j.dom.address.MailboxList;
import org.apache.james.mime4j.dom.field.ContentTypeField;
import org.apache.james.mime4j.message.DefaultMessageBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;

@Component
public class MailLoaderImpl implements DocumentLoader {

    private static final Logger logger = LoggerFactory.getLogger(MailLoaderImpl.class);

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
        "eml", "msg", "mbox"
    );

    @Override
    public String getName() {
        return "Mail Message Loader";
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
            throw new IllegalArgumentException("MailLoader currently only supports FILE sources.");
        }

        File file = new File(sourceDescriptor.getPathOrUrl());
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("File does not exist or is not a regular file: " + sourceDescriptor.getPathOrUrl());
        }

        String filename = file.getName().toLowerCase();

        try {
            if (filename.endsWith(".eml")) {
                return loadEmlFile(file);
            } else if (filename.endsWith(".mbox")) {
                return loadMboxFile(file);
            } else if (filename.endsWith(".msg")) {
                return loadMsgFile(file);
            }
        } catch (Exception e) {
            // Handle corrupted or invalid mail files gracefully
            String errorMessage = e.getMessage();
            logger.warn("Unable to parse mail file '{}': {}. The file may be corrupted or in an unsupported format.",
                       file.getName(), errorMessage);

            // Return an error document so the caller knows what happened
            Document errorDoc = new Document("[Error: Unable to parse mail file. The file may be corrupted or in an unsupported format.]");
            errorDoc.getMetadata().put("source", file.getAbsolutePath());
            errorDoc.getMetadata().put("fileName", file.getName());
            errorDoc.getMetadata().put("fileSize", file.length());
            errorDoc.getMetadata().put("lastModified", file.lastModified());
            errorDoc.getMetadata().put("loader", getName());
            errorDoc.getMetadata().put("parseError", true);
            errorDoc.getMetadata().put("errorMessage", errorMessage != null ? errorMessage : "Unknown error");
            return List.of(errorDoc);
        }

        throw new IllegalArgumentException("Unsupported mail file type: " + filename);
    }

    private List<Document> loadEmlFile(File file) throws Exception {
        MessageBuilder builder = new DefaultMessageBuilder();
        try (FileInputStream fis = new FileInputStream(file)) {
            Message message = builder.parseMessage(fis);
            Document document = convertMessageToDocument(message, file);
            return List.of(document);
        }
    }

    private List<Document> loadMboxFile(File file) throws Exception {
        List<Document> documents = new ArrayList<>();
        
        try (BufferedReader reader = Files.newBufferedReader(file.toPath())) {
            StringBuilder messageBuilder = new StringBuilder();
            String line;
            boolean inMessage = false;
            
            while ((line = reader.readLine()) != null) {
                // mbox format: messages start with "From " line
                if (line.startsWith("From ") && inMessage) {
                    // Process previous message
                    if (messageBuilder.length() > 0) {
                        Document doc = parseMboxMessage(messageBuilder.toString(), file);
                        if (doc != null) {
                            documents.add(doc);
                        }
                        messageBuilder.setLength(0);
                    }
                    inMessage = true;
                } else if (line.startsWith("From ") && !inMessage) {
                    inMessage = true;
                    continue;
                }
                
                if (inMessage) {
                    messageBuilder.append(line).append("\n");
                }
            }
            
            // Process the last message
            if (messageBuilder.length() > 0) {
                Document doc = parseMboxMessage(messageBuilder.toString(), file);
                if (doc != null) {
                    documents.add(doc);
                }
            }
        }
        
        return documents;
    }

    private Document parseMboxMessage(String messageContent, File originalFile) {
        try {
            MessageBuilder builder = new DefaultMessageBuilder();
            ByteArrayInputStream bais = new ByteArrayInputStream(messageContent.getBytes());
            Message message = builder.parseMessage(bais);
            return convertMessageToDocument(message, originalFile);
        } catch (Exception e) {
            // If parsing fails, create a simple document with raw content
            Document doc = new Document(messageContent);
            addMetadata(doc, originalFile, "Raw mbox message");
            return doc;
        }
    }

    private List<Document> loadMsgFile(File file) throws Exception {
        // .msg files are Microsoft Outlook format
        // For now, we'll try to read them as binary and extract what we can
        // A more sophisticated implementation would use a dedicated .msg parser
        
        try {
            Properties props = new Properties();
            Session session = Session.getDefaultInstance(props);
            
            try (FileInputStream fis = new FileInputStream(file)) {
                MimeMessage mimeMessage = new MimeMessage(session, fis);
                Document document = convertMimeMessageToDocument(mimeMessage, file);
                return List.of(document);
            }
        } catch (Exception e) {
            // Fallback: read as text if MIME parsing fails
            String content = Files.readString(file.toPath());
            Document doc = new Document(content);
            addMetadata(doc, file, "Microsoft Outlook Message (raw)");
            return List.of(doc);
        }
    }

    private Document convertMessageToDocument(Message message, File originalFile) throws IOException {
        StringBuilder content = new StringBuilder();
        
        // Extract headers
        if (message.getSubject() != null) {
            content.append("Subject: ").append(message.getSubject()).append("\n");
        }
        
        if (message.getFrom() != null) {
            content.append("From: ").append(formatMailboxList(message.getFrom())).append("\n");
        }
        
        if (message.getTo() != null) {
            content.append("To: ").append(formatMailboxList(message.getTo().flatten())).append("\n");
        }
        
        if (message.getCc() != null) {
            content.append("Cc: ").append(formatMailboxList(message.getCc().flatten())).append("\n");
        }
        
        if (message.getDate() != null) {
            content.append("Date: ").append(message.getDate()).append("\n");
        }
        
        content.append("\n");
        
        // Extract body
        String bodyText = extractBodyText(message);
        if (bodyText != null && !bodyText.trim().isEmpty()) {
            content.append(bodyText);
        }
        
        Document document = new Document(content.toString());
        addMetadata(document, originalFile, "Email Message");
        
        // Add email-specific metadata
        if (message.getSubject() != null) {
            document.getMetadata().put("subject", message.getSubject());
        }
        if (message.getFrom() != null) {
            document.getMetadata().put("from", formatMailboxList(message.getFrom()));
        }
        if (message.getDate() != null) {
            document.getMetadata().put("messageDate", message.getDate());
        }
        
        return document;
    }

    private Document convertMimeMessageToDocument(MimeMessage mimeMessage, File originalFile) throws Exception {
        StringBuilder content = new StringBuilder();
        
        // Extract headers
        String subject = mimeMessage.getSubject();
        if (subject != null) {
            content.append("Subject: ").append(subject).append("\n");
        }
        
        String from = mimeMessage.getFrom() != null && mimeMessage.getFrom().length > 0 
            ? mimeMessage.getFrom()[0].toString() : null;
        if (from != null) {
            content.append("From: ").append(from).append("\n");
        }
        
        String to = mimeMessage.getAllRecipients() != null && mimeMessage.getAllRecipients().length > 0 
            ? mimeMessage.getAllRecipients()[0].toString() : null;
        if (to != null) {
            content.append("To: ").append(to).append("\n");
        }
        
        if (mimeMessage.getSentDate() != null) {
            content.append("Date: ").append(mimeMessage.getSentDate()).append("\n");
        }
        
        content.append("\n");
        
        // Extract body
        Object messageContent = mimeMessage.getContent();
        if (messageContent instanceof String) {
            content.append((String) messageContent);
        } else if (messageContent instanceof Multipart) {
            // Handle multipart messages - extract text parts
            content.append("Multipart message content extracted");
        }
        
        Document document = new Document(content.toString());
        addMetadata(document, originalFile, "MIME Email Message");
        
        // Add email-specific metadata
        if (subject != null) {
            document.getMetadata().put("subject", subject);
        }
        if (from != null) {
            document.getMetadata().put("from", from);
        }
        if (mimeMessage.getSentDate() != null) {
            document.getMetadata().put("messageDate", mimeMessage.getSentDate());
        }
        
        return document;
    }

    private String extractBodyText(org.apache.james.mime4j.dom.Entity entity) throws IOException {
        if (entity.getBody() instanceof TextBody) {
            TextBody textBody = (TextBody) entity.getBody();
            return readTextBody(textBody);
        } else if (entity.getBody() instanceof Multipart) {
            Multipart multipart = (Multipart) entity.getBody();
            StringBuilder text = new StringBuilder();
            
            for (org.apache.james.mime4j.dom.Entity part : multipart.getBodyParts()) {
                String partText = extractBodyText(part);
                if (partText != null && !partText.trim().isEmpty()) {
                    text.append(partText).append("\n");
                }
            }
            
            return text.toString();
        }
        
        return null;
    }

    private String readTextBody(TextBody textBody) throws IOException {
        try (Reader reader = textBody.getReader()) {
            StringBuilder sb = new StringBuilder();
            char[] buffer = new char[1024];
            int bytesRead;
            while ((bytesRead = reader.read(buffer)) != -1) {
                sb.append(buffer, 0, bytesRead);
            }
            return sb.toString();
        }
    }

    private String formatMailboxList(MailboxList mailboxList) {
        if (mailboxList == null || mailboxList.isEmpty()) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mailboxList.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            Mailbox mailbox = mailboxList.get(i);
            if (mailbox.getName() != null && !mailbox.getName().isEmpty()) {
                sb.append(mailbox.getName()).append(" <").append(mailbox.getAddress()).append(">");
            } else {
                sb.append(mailbox.getAddress());
            }
        }
        return sb.toString();
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
