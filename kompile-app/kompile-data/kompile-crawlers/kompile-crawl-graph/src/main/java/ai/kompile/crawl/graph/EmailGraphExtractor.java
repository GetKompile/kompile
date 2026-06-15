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

package ai.kompile.crawl.graph;

import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.knowledgegraph.domain.EdgeProvenance;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

/**
 * Extracts email graph entities (EMAIL_MESSAGE, PERSON, ATTACHMENT) and relationships
 * (SENT_BY, SENT_TO, CC_TO, BCC_TO, HAS_ATTACHMENT, REPLIED_TO, REFERENCES) from
 * email metadata — no LLM required.
 *
 * <p>Extracted from {@link UnifiedCrawlGraphServiceImpl} to reduce class size.
 */
@Component("crawlGraphEmailExtractor")
class EmailGraphExtractor {

    private static final Logger log = LoggerFactory.getLogger(EmailGraphExtractor.class);

    private static final List<String> EXTRACTORS_EMAIL = List.of("EmailGraphExtraction");

    @Autowired(required = false)
    KnowledgeGraphService knowledgeGraphService;

    @Autowired
    GraphPersistenceHelper graphPersistenceHelper;

    @Autowired
    CrawlDocumentTracker documentTracker;

    /**
     * Creates email graph entities and relationships from the metadata of the supplied documents.
     *
     * @param job       the crawl job driving this extraction
     * @param documents the documents whose metadata contains email headers
     */
    void applyEmailGraphExtraction(UnifiedCrawlJob job, List<Document> documents) {
        if (knowledgeGraphService == null || documents == null) return;

        if (job != null && isCancelled(job)) {
            return;
        }

        String jobId = job != null ? job.getJobId() : null;
        Long factSheetId = jobFactSheetId(job);
        String crawlSource = "crawl:" + jobId;
        Map<String, Optional<GraphNode>> docNodeCache = new HashMap<>();
        // Cross-document cache: same entity ID → same node (avoids N+1 for repeated persons, emails)
        Map<String, Optional<GraphNode>> entityNodeCache = new HashMap<>();
        // Pre-compute constant labels — avoids per-email ConcurrentHashMap lookups
        String containsLabel = graphPersistenceHelper.semanticRelationLabel(GraphConstants.REL_CONTAINS);
        String hasAttachmentLabel = graphPersistenceHelper.semanticRelationLabel(GraphConstants.REL_HAS_ATTACHMENT);
        String repliedToLabel = graphPersistenceHelper.semanticRelationLabel(GraphConstants.REL_REPLIED_TO);
        String referencesLabel = graphPersistenceHelper.semanticRelationLabel(GraphConstants.REL_REFERENCES);
        // Pre-compute address relation labels — 4 fixed relation types used in inner address loop
        String sentByLabel = graphPersistenceHelper.semanticRelationLabel("SENT_BY");
        String sentToLabel = graphPersistenceHelper.semanticRelationLabel("SENT_TO");
        String ccToLabel = graphPersistenceHelper.semanticRelationLabel("CC_TO");
        String bccToLabel = graphPersistenceHelper.semanticRelationLabel("BCC_TO");
        int entitiesCreated = 0;
        int relationsCreated = 0;

        for (Document doc : documents) {
            if (job != null && isCancelled(job)) {
                return;
            }
            Map<String, Object> meta = doc.getMetadata();
            if (meta == null) continue;

            // Detect email.* or gmail.* namespace and normalize to common keys
            String emailFrom = meta.get("email.from") instanceof String
                    ? (String) meta.get("email.from") : null;
            String emailTo = meta.get("email.to") instanceof String
                    ? (String) meta.get("email.to") : null;
            String emailCc = meta.get("email.cc") instanceof String
                    ? (String) meta.get("email.cc") : null;
            String emailBcc = meta.get("email.bcc") instanceof String
                    ? (String) meta.get("email.bcc") : null;
            String emailSubject = meta.get("email.subject") instanceof String
                    ? (String) meta.get("email.subject") : null;
            String emailInReplyTo = meta.get("email.inReplyTo") instanceof String
                    ? (String) meta.get("email.inReplyTo") : null;
            Object emailRefsObj = meta.get("email.references");
            String emailMessageId = meta.get("email.messageId") instanceof String
                    ? (String) meta.get("email.messageId") : null;
            Object attachObj = meta.get(GraphConstants.META_EMAIL_ATTACHMENT_NAMES);

            // Fall back to gmail.* namespace
            if (emailFrom == null) {
                emailFrom = meta.get("gmail.from") instanceof String
                        ? (String) meta.get("gmail.from") : null;
            }
            if (emailTo == null) {
                emailTo = meta.get("gmail.to") instanceof String
                        ? (String) meta.get("gmail.to") : null;
            }
            if (emailCc == null) {
                emailCc = meta.get("gmail.cc") instanceof String
                        ? (String) meta.get("gmail.cc") : null;
            }
            if (emailSubject == null) {
                emailSubject = meta.get("gmail.subject") instanceof String
                        ? (String) meta.get("gmail.subject") : null;
            }
            if (emailBcc == null) {
                emailBcc = meta.get("gmail.bcc") instanceof String
                        ? (String) meta.get("gmail.bcc") : null;
            }
            if (emailInReplyTo == null) {
                emailInReplyTo = meta.get("gmail.inReplyTo") instanceof String
                        ? (String) meta.get("gmail.inReplyTo") : null;
            }
            if (emailRefsObj == null) {
                emailRefsObj = meta.get("gmail.references");
            }
            if (emailMessageId == null) {
                emailMessageId = meta.get("gmail.messageId") instanceof String
                        ? (String) meta.get("gmail.messageId") : null;
            }
            if (attachObj == null) {
                attachObj = meta.get("gmail.attachments");
            }

            if (emailFrom == null) continue;
            if (emailSubject == null) emailSubject = "Email";

            int entitiesBeforeDoc = entitiesCreated;
            int relationsBeforeDoc = relationsCreated;
            documentTracker.recordDocumentProgress(job, doc, "EMAIL_GRAPH", "RUNNING", 0, 0, 0,
                    "Extracting email message graph", null, EXTRACTORS_EMAIL, false);

            try {
                String sourcePath = meta.get(GraphConstants.META_SOURCE_PATH) instanceof String
                        ? (String) meta.get(GraphConstants.META_SOURCE_PATH)
                        : meta.get(GraphConstants.META_SOURCE) instanceof String ? (String) meta.get(GraphConstants.META_SOURCE) : null;

                // Find or create parent DOCUMENT node
                String parentNodeId = null;
                if (sourcePath != null) {
                    Optional<GraphNode> docNode = docNodeCache.computeIfAbsent(sourcePath,
                            sp -> knowledgeGraphService.getNodeByExternalId(sp, NodeLevel.DOCUMENT, factSheetId));
                    if (docNode.isEmpty()) {
                        String fileName = meta.get(GraphConstants.META_FILE_NAME) instanceof String
                                ? (String) meta.get(GraphConstants.META_FILE_NAME) : sourcePath;
                        String sourceType = meta.get(GraphConstants.META_SOURCE_TYPE) instanceof String
                                ? (String) meta.get(GraphConstants.META_SOURCE_TYPE) : "EMAIL";
                        String loaderName = meta.get(GraphConstants.META_LOADER) instanceof String
                                ? (String) meta.get(GraphConstants.META_LOADER) : "unknown";
                        Map<String, Object> docMeta = new LinkedHashMap<>();
                        docMeta.put(GraphConstants.META_LOADER, loaderName);
                        docMeta.put("jobId", job != null ? job.getJobId() : null);
                        GraphNode created = knowledgeGraphService.addDocument(
                                crawlSource, jobId, sourceType, sourcePath, fileName, null, docMeta, factSheetId);
                        if (created != null) {
                            docNode = Optional.of(created);
                            docNodeCache.put(sourcePath, docNode);
                        }
                    }
                    if (docNode.isPresent()) parentNodeId = docNode.get().getNodeId();
                }

                // Create EMAIL_MESSAGE entity
                String emailId = "email-msg:" + UUID.nameUUIDFromBytes(
                        (emailFrom + "|" + emailSubject).getBytes()).toString();
                Map<String, Object> emailMeta = new LinkedHashMap<>();
                emailMeta.put("entity_type", "EMAIL_MESSAGE");
                emailMeta.put(GraphConstants.META_SOURCE, jobId);
                if (sourcePath != null) emailMeta.put(GraphConstants.META_SOURCE_PATH, sourcePath);

                GraphNode emailNode;
                Optional<GraphNode> existingEmail = entityNodeCache.computeIfAbsent(emailId,
                        eid -> knowledgeGraphService.getNodeByExternalId(eid, NodeLevel.ENTITY, factSheetId));
                if (existingEmail.isPresent()) {
                    emailNode = existingEmail.get();
                } else {
                    emailNode = knowledgeGraphService.createNode(NodeLevel.ENTITY, emailId,
                            emailSubject, "Email from " + emailFrom, emailMeta, factSheetId);
                    entityNodeCache.put(emailId, Optional.of(emailNode));
                    entitiesCreated++;
                }

                // Link email to parent DOCUMENT
                if (parentNodeId != null) {
                    String description = graphPersistenceHelper.semanticRelationDescription(
                            "Document contains email message '" + emailSubject + "'", containsLabel);
                    String metaJson = graphPersistenceHelper.semanticRelationMetadataJson(jobId, sourcePath,
                            "email_graph", sourcePath, emailId, containsLabel, description, 1.0,
                            graphPersistenceHelper.metadataProperties(
                                    "entityType", "EMAIL_MESSAGE",
                                    "subject", emailSubject,
                                    "from", emailFrom));
                    knowledgeGraphService.createEdgeWithMetadata(parentNodeId, emailNode.getNodeId(),
                            EdgeType.CONTAINS, 1.0, containsLabel, description, metaJson,
                            EdgeProvenance.EXTRACTED, factSheetId);
                }

                // Create PERSON entities from From/To/Cc/Bcc fields
                String finalEmailSubject = emailSubject;
                String[][] addressFields = {
                        {emailFrom, "SENT_BY"},
                        {emailTo, "SENT_TO"},
                        {emailCc, "CC_TO"},
                        {emailBcc, "BCC_TO"}
                };
                for (String[] pair : addressFields) {
                    String addressField = pair[0];
                    String relationType = pair[1];
                    if (addressField == null || addressField.isBlank()) continue;

                    for (String addr : addressField.split(",")) {
                        addr = addr.trim();
                        if (addr.isEmpty()) continue;

                        String emailAddr = addr;
                        String personName = addr;
                        int ltIdx = addr.indexOf('<');
                        if (ltIdx >= 0) {
                            personName = addr.substring(0, ltIdx).trim();
                            int gtIdx = addr.indexOf('>', ltIdx);
                            emailAddr = gtIdx > ltIdx
                                    ? addr.substring(ltIdx + 1, gtIdx).trim()
                                    : addr.substring(ltIdx + 1).trim();
                        }
                        if (personName.isEmpty() || personName.equals(emailAddr)) {
                            personName = emailAddr.contains("@")
                                    ? emailAddr.substring(0, emailAddr.indexOf('@'))
                                    : emailAddr;
                        }

                        String personId = "person:" + UUID.nameUUIDFromBytes(
                                emailAddr.toLowerCase().getBytes()).toString();
                        Map<String, Object> personMeta = new LinkedHashMap<>();
                        personMeta.put("entity_type", "PERSON");
                        personMeta.put("email", emailAddr);
                        personMeta.put(GraphConstants.META_SOURCE, jobId);
                        if (sourcePath != null) personMeta.put(GraphConstants.META_SOURCE_PATH, sourcePath);

                        GraphNode personNode;
                        Optional<GraphNode> existingPerson = entityNodeCache.computeIfAbsent(personId,
                                pid -> knowledgeGraphService.getNodeByExternalId(pid, NodeLevel.ENTITY, factSheetId));
                        if (existingPerson.isPresent()) {
                            personNode = existingPerson.get();
                        } else {
                            personNode = knowledgeGraphService.createNode(NodeLevel.ENTITY, personId,
                                    personName, "Email contact: " + emailAddr, personMeta, factSheetId);
                            entityNodeCache.put(personId, Optional.of(personNode));
                            entitiesCreated++;
                        }

                        String srcId = "SENT_BY".equals(relationType)
                                ? personNode.getNodeId() : emailNode.getNodeId();
                        String tgtId = "SENT_BY".equals(relationType)
                                ? emailNode.getNodeId() : personNode.getNodeId();
                        String label = switch (relationType) {
                            case "SENT_BY" -> sentByLabel;
                            case "SENT_TO" -> sentToLabel;
                            case "CC_TO" -> ccToLabel;
                            case "BCC_TO" -> bccToLabel;
                            default -> graphPersistenceHelper.semanticRelationLabel(relationType);
                        };
                        String finalPersonName = personName;
                        String rawDesc = switch (relationType) {
                            case "SENT_BY" -> finalPersonName + " sent email '" + finalEmailSubject + "'";
                            case "SENT_TO" -> "Email '" + finalEmailSubject + "' was sent to " + finalPersonName;
                            case "CC_TO" -> "Email '" + finalEmailSubject + "' copied " + finalPersonName;
                            case "BCC_TO" -> "Email '" + finalEmailSubject + "' blind-copied " + finalPersonName;
                            default -> relationType + " relationship";
                        };
                        String finalEmailAddr = emailAddr;
                        String description = graphPersistenceHelper.semanticRelationDescription(rawDesc, label);
                        String metaJson = graphPersistenceHelper.semanticRelationMetadataJson(jobId, sourcePath,
                                "email_graph",
                                "SENT_BY".equals(relationType) ? personId : emailId,
                                "SENT_BY".equals(relationType) ? emailId : personId,
                                label, description, 1.0,
                                graphPersistenceHelper.metadataProperties(
                                        "email", finalEmailAddr,
                                        "personName", finalPersonName,
                                        "subject", finalEmailSubject));
                        knowledgeGraphService.createEdgeWithMetadata(srcId, tgtId,
                                EdgeType.USER_DEFINED, 1.0, label,
                                description, metaJson,
                                EdgeProvenance.EXTRACTED, factSheetId);
                        relationsCreated++;
                    }
                }

                // Create ATTACHMENT entities
                List<String> attachments = null;
                if (attachObj instanceof List<?> attachList) {
                    attachments = new ArrayList<>(attachList.size());
                    for (Object o : attachList) {
                        if (o instanceof String s) attachments.add(s);
                    }
                } else if (attachObj instanceof String attachStr && !attachStr.isBlank()) {
                    attachments = Arrays.asList(attachStr.split(","));
                }

                if (attachments != null) {
                    for (String attName : attachments) {
                        attName = attName.trim();
                        if (attName.isEmpty()) continue;
                        String attId = "attachment:" + UUID.nameUUIDFromBytes(
                                (emailId + "|" + attName).getBytes()).toString();
                        Map<String, Object> attMeta = new LinkedHashMap<>();
                        attMeta.put("entity_type", "ATTACHMENT");
                        attMeta.put("filename", attName);
                        attMeta.put(GraphConstants.META_SOURCE, jobId);
                        if (sourcePath != null) attMeta.put(GraphConstants.META_SOURCE_PATH, sourcePath);

                        GraphNode attNode;
                        Optional<GraphNode> existingAtt = entityNodeCache.computeIfAbsent(attId,
                                aid -> knowledgeGraphService.getNodeByExternalId(aid, NodeLevel.ENTITY, factSheetId));
                        if (existingAtt.isPresent()) {
                            attNode = existingAtt.get();
                        } else {
                            attNode = knowledgeGraphService.createNode(NodeLevel.ENTITY, attId,
                                    attName, "Email attachment", attMeta, factSheetId);
                            entityNodeCache.put(attId, Optional.of(attNode));
                            entitiesCreated++;
                        }

                        String description = graphPersistenceHelper.semanticRelationDescription(
                                "Email '" + finalEmailSubject + "' has attachment " + attName, hasAttachmentLabel);
                        String metaJson = graphPersistenceHelper.semanticRelationMetadataJson(jobId, sourcePath,
                                "email_graph", emailId, attId, hasAttachmentLabel, description, 1.0,
                                graphPersistenceHelper.metadataProperties(
                                        "subject", finalEmailSubject,
                                        "attachmentName", attName));
                        knowledgeGraphService.createEdgeWithMetadata(emailNode.getNodeId(), attNode.getNodeId(),
                                EdgeType.USER_DEFINED, 1.0, hasAttachmentLabel, description, metaJson,
                                EdgeProvenance.EXTRACTED, factSheetId);
                        relationsCreated++;
                    }
                }

                // Create REPLIED_TO edge from In-Reply-To header
                if (emailInReplyTo != null && !emailInReplyTo.isBlank()) {
                    String repliedMsgId = "email-msg:" + UUID.nameUUIDFromBytes(
                            emailInReplyTo.getBytes()).toString();
                    Map<String, Object> repliedMeta = new LinkedHashMap<>();
                    repliedMeta.put("entity_type", "EMAIL_MESSAGE");
                    repliedMeta.put("messageId", emailInReplyTo);
                    repliedMeta.put(GraphConstants.META_SOURCE, jobId);

                    GraphNode repliedNode;
                    Optional<GraphNode> existingReplied = entityNodeCache.computeIfAbsent(repliedMsgId,
                            rid -> knowledgeGraphService.getNodeByExternalId(rid, NodeLevel.ENTITY, factSheetId));
                    if (existingReplied.isPresent()) {
                        repliedNode = existingReplied.get();
                    } else {
                        repliedNode = knowledgeGraphService.createNode(NodeLevel.ENTITY, repliedMsgId,
                                "Message " + emailInReplyTo, "Referenced email message", repliedMeta, factSheetId);
                        entityNodeCache.put(repliedMsgId, Optional.of(repliedNode));
                        entitiesCreated++;
                    }

                    String finalEmailInReplyTo = emailInReplyTo;
                    String description = graphPersistenceHelper.semanticRelationDescription(
                            "Email '" + finalEmailSubject + "' replies to " + finalEmailInReplyTo, repliedToLabel);
                    String metaJson = graphPersistenceHelper.semanticRelationMetadataJson(jobId, sourcePath,
                            "email_graph", emailId, repliedMsgId, repliedToLabel, description, 1.0,
                            graphPersistenceHelper.metadataProperties(
                                    "subject", finalEmailSubject,
                                    "messageId", finalEmailInReplyTo));
                    knowledgeGraphService.createEdgeWithMetadata(emailNode.getNodeId(), repliedNode.getNodeId(),
                            EdgeType.USER_DEFINED, 1.0, repliedToLabel, description, metaJson,
                            EdgeProvenance.EXTRACTED, factSheetId);
                    relationsCreated++;
                }

                // Create REFERENCES edges from References header
                List<String> refsList = null;
                if (emailRefsObj instanceof List<?> refObjList) {
                    refsList = new ArrayList<>(refObjList.size());
                    for (Object o : refObjList) {
                        if (o instanceof String s) {
                            String trimmed = s.trim();
                            if (!trimmed.isEmpty()) refsList.add(trimmed);
                        }
                    }
                } else if (emailRefsObj instanceof String refsStr && !refsStr.isBlank()) {
                    refsList = Arrays.asList(refsStr.trim().split("\\s+"));
                }

                if (refsList != null) {
                    String finalEmailInReplyTo = emailInReplyTo;
                    for (String ref : refsList) {
                        // Skip the In-Reply-To message — already handled above
                        if (ref.equals(finalEmailInReplyTo)) continue;
                        String refMsgId = "email-msg:" + UUID.nameUUIDFromBytes(
                                ref.getBytes()).toString();
                        Map<String, Object> refMeta = new LinkedHashMap<>();
                        refMeta.put("entity_type", "EMAIL_MESSAGE");
                        refMeta.put("messageId", ref);
                        refMeta.put(GraphConstants.META_SOURCE, jobId);

                        GraphNode refNode;
                        Optional<GraphNode> existingRef = entityNodeCache.computeIfAbsent(refMsgId,
                                rid -> knowledgeGraphService.getNodeByExternalId(rid, NodeLevel.ENTITY, factSheetId));
                        if (existingRef.isPresent()) {
                            refNode = existingRef.get();
                        } else {
                            refNode = knowledgeGraphService.createNode(NodeLevel.ENTITY, refMsgId,
                                    "Message " + ref, "Referenced email message", refMeta, factSheetId);
                            entityNodeCache.put(refMsgId, Optional.of(refNode));
                            entitiesCreated++;
                        }

                        String description = graphPersistenceHelper.semanticRelationDescription(
                                "Email '" + finalEmailSubject + "' references " + ref, referencesLabel);
                        String metaJson = graphPersistenceHelper.semanticRelationMetadataJson(jobId, sourcePath,
                                "email_graph", emailId, refMsgId, referencesLabel, description, 0.9,
                                graphPersistenceHelper.metadataProperties(
                                        "subject", finalEmailSubject,
                                        "messageId", ref));
                        knowledgeGraphService.createEdgeWithMetadata(emailNode.getNodeId(), refNode.getNodeId(),
                                EdgeType.USER_DEFINED, 0.9, referencesLabel, description, metaJson,
                                EdgeProvenance.EXTRACTED, factSheetId);
                        relationsCreated++;
                    }
                }

                if (job == null || !isCancelled(job)) {
                    documentTracker.recordDocumentProgress(job, doc, "EMAIL_GRAPH", "COMPLETED", 0,
                            entitiesCreated - entitiesBeforeDoc,
                            relationsCreated - relationsBeforeDoc,
                            "Email graph extraction complete", null, EXTRACTORS_EMAIL, true);
                }
            } catch (Exception e) {
                log.warn("[Job {}] Email graph extraction failed for document: {}",
                        job != null ? job.getJobId() : "unknown", e.getMessage());
                if (job == null || !isCancelled(job)) {
                    documentTracker.recordDocumentProgress(job, doc, "EMAIL_GRAPH", "FAILED", 0, 0, 0,
                            "Email graph extraction failed", e.getMessage(), EXTRACTORS_EMAIL, true);
                }
            }
        }

        if ((job == null || !isCancelled(job)) && (entitiesCreated > 0 || relationsCreated > 0)) {
            if (job != null) {
                job.getEntitiesExtracted().addAndGet(entitiesCreated);
                job.getRelationshipsExtracted().addAndGet(relationsCreated);
            }
            log.info("[Job {}] Email graph extraction: {} entities, {} relations created",
                    jobId, entitiesCreated, relationsCreated);
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private boolean isCancelled(UnifiedCrawlJob job) {
        if (job.getStatus().get() == UnifiedCrawlJob.Status.CANCELLED) {
            job.setCompletedAt(Instant.now());
            return true;
        }
        return false;
    }

    private Long jobFactSheetId(UnifiedCrawlJob job) {
        return job != null && job.getRequest() != null ? job.getRequest().getFactSheetId() : null;
    }
}
