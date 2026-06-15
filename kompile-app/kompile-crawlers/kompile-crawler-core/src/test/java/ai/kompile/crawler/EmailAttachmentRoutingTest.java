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

package ai.kompile.crawler;

import ai.kompile.core.crawler.CrawlConfig;
import ai.kompile.core.crawler.CrawlItem;
import ai.kompile.core.crawler.pipeline.ContentRouteRule;
import ai.kompile.core.crawler.pipeline.IngestPipelineDefinition;
import ai.kompile.core.crawler.pipeline.RoutedCrawlItem;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.core.loaders.DocumentSourceDescriptor.SourceType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that email attachment CrawlItems route correctly through
 * CrawlPipelineRouter to appropriate pipelines based on content type,
 * file extension, and source type.
 *
 * This verifies the end-to-end contract between the email crawlers
 * (which emit attachment CrawlItems) and the pipeline router
 * (which dispatches them to the right loader/parser).
 */
class EmailAttachmentRoutingTest {

    // ── PDF attachment routes to VLM pipeline ────────────────────────────

    @Test
    void pdfAttachmentRoutesToVlmPipeline() {
        CrawlPipelineRouter router = buildEmailRouter();

        CrawlItem pdfAttachment = emailAttachmentItem(
                "/path/to/mbox#msg123#attachment:report.pdf",
                "/path/to/mbox#msg123",
                "application/pdf",
                "report.pdf",
                102400L
        );

        RoutedCrawlItem routed = router.routeWithDetails(pdfAttachment);
        assertEquals("pdf-pipeline", routed.pipeline().getPipelineId());
        assertEquals(IngestPipelineDefinition.PipelineType.VLM, routed.pipeline().getPipelineType());
        assertNotNull(routed.matchedRule());
    }

    // ── Office document attachment routes to office pipeline ─────────────

    @Test
    void docxAttachmentRoutesToOfficePipeline() {
        CrawlPipelineRouter router = buildEmailRouter();

        CrawlItem docxAttachment = emailAttachmentItem(
                "/maildir/cur/1.M1:2,S#attachment:meeting_notes.docx",
                "/maildir/cur/1.M1:2,S",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "meeting_notes.docx",
                51200L
        );

        assertEquals("office-pipeline", router.route(docxAttachment).getPipelineId());
    }

    @Test
    void xlsxAttachmentRoutesToOfficePipeline() {
        CrawlPipelineRouter router = buildEmailRouter();

        CrawlItem xlsxAttachment = emailAttachmentItem(
                "/mbox#msg456#attachment:data.xlsx",
                "/mbox#msg456",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "data.xlsx",
                32768L
        );

        assertEquals("office-pipeline", router.route(xlsxAttachment).getPipelineId());
    }

    // ── Text attachment routes to text pipeline ─────────────────────────

    @Test
    void plainTextAttachmentRoutesToTextPipeline() {
        CrawlPipelineRouter router = buildEmailRouter();

        CrawlItem txtAttachment = emailAttachmentItem(
                "/inbox#msg789#attachment:notes.txt",
                "/inbox#msg789",
                "text/plain",
                "notes.txt",
                1024L
        );

        assertEquals("text-pipeline", router.route(txtAttachment).getPipelineId());
    }

    @Test
    void csvAttachmentRoutesToTextPipeline() {
        CrawlPipelineRouter router = buildEmailRouter();

        CrawlItem csvAttachment = emailAttachmentItem(
                "/inbox#msg101#attachment:export.csv",
                "/inbox#msg101",
                "text/csv",
                "export.csv",
                4096L
        );

        assertEquals("text-pipeline", router.route(csvAttachment).getPipelineId());
    }

    // ── Image attachment routes to image pipeline ────────────────────────

    @Test
    void imageAttachmentRoutesToImagePipeline() {
        CrawlPipelineRouter router = buildEmailRouter();

        CrawlItem pngAttachment = emailAttachmentItem(
                "/inbox#msg202#attachment:screenshot.png",
                "/inbox#msg202",
                "image/png",
                "screenshot.png",
                204800L
        );

        assertEquals("image-pipeline", router.route(pngAttachment).getPipelineId());
    }

    @Test
    void jpegAttachmentRoutesToImagePipeline() {
        CrawlPipelineRouter router = buildEmailRouter();

        CrawlItem jpgAttachment = emailAttachmentItem(
                "/inbox#msg303#attachment:photo.jpg",
                "/inbox#msg303",
                "image/jpeg",
                "photo.jpg",
                1048576L
        );

        assertEquals("image-pipeline", router.route(jpgAttachment).getPipelineId());
    }

    // ── Unknown attachment falls to default ──────────────────────────────

    @Test
    void unknownAttachmentFallsToDefaultPipeline() {
        CrawlPipelineRouter router = buildEmailRouter();

        CrawlItem unknownAttachment = emailAttachmentItem(
                "/inbox#msg404#attachment:archive.7z",
                "/inbox#msg404",
                "application/x-7z-compressed",
                "archive.7z",
                5242880L
        );

        assertEquals("email-text", router.route(unknownAttachment).getPipelineId());
    }

    // ── Email message itself routes to email-text pipeline ──────────────

    @Test
    void emailMessageRoutesToEmailTextPipeline() {
        CrawlPipelineRouter router = buildEmailRouter();

        CrawlItem emailItem = CrawlItem.builder()
                .url("/maildir/cur/1.M1:2,S")
                .parentUrl("/maildir")
                .contentType("message/rfc822")
                .sourceDescriptor(DocumentSourceDescriptor.builder()
                        .type(SourceType.MAILDIR)
                        .build())
                .discoveredAt(Instant.now())
                .build();

        assertEquals("email-text", router.route(emailItem).getPipelineId());
    }

    // ── Source type routing ──────────────────────────────────────────────

    @Test
    void attachmentWithFileSourceTypeRoutesCorrectly() {
        CrawlPipelineRouter router = buildEmailRouter();

        // Email attachments are emitted with SourceType.FILE
        CrawlItem attachment = emailAttachmentItem(
                "/inbox#msg#attachment:doc.pdf",
                "/inbox#msg",
                "application/pdf",
                "doc.pdf",
                1024L
        );

        assertEquals(SourceType.FILE, attachment.getSourceDescriptor().getType());
        assertEquals("pdf-pipeline", router.route(attachment).getPipelineId());
    }

    // ── Attachment metadata survives routing ─────────────────────────────

    @Test
    void attachmentMetadataPreservedThroughRouting() {
        CrawlPipelineRouter router = buildEmailRouter();

        CrawlItem attachment = emailAttachmentItem(
                "/inbox#msg#attachment:report.pdf",
                "/inbox#msg",
                "application/pdf",
                "report.pdf",
                102400L
        );

        RoutedCrawlItem routed = router.routeWithDetails(attachment);

        // The routed item should still reference the original CrawlItem
        CrawlItem routedItem = routed.item();
        assertEquals(true, routedItem.getMetadata().get("email.isAttachment"));
        assertEquals("<msg123@example.com>", routedItem.getMetadata().get("email.parentMessageId"));
        assertEquals("Test Email", routedItem.getMetadata().get("email.parentSubject"));
        assertEquals("report.pdf", routedItem.getMetadata().get("email.attachmentName"));
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Builds a router with pipelines typical for an email crawling scenario:
     * PDF → VLM, Office docs → office loader, text → text loader, images → image loader,
     * everything else (including email messages) → default email text pipeline.
     */
    private CrawlPipelineRouter buildEmailRouter() {
        List<IngestPipelineDefinition> pipelines = List.of(
                IngestPipelineDefinition.builder()
                        .pipelineId("pdf-pipeline")
                        .displayName("PDF Pipeline")
                        .pipelineType(IngestPipelineDefinition.PipelineType.VLM)
                        .loaderName("pdf-extended")
                        .collectionName("emails")
                        .build(),
                IngestPipelineDefinition.builder()
                        .pipelineId("office-pipeline")
                        .displayName("Office Pipeline")
                        .pipelineType(IngestPipelineDefinition.PipelineType.STANDARD_TEXT)
                        .loaderName("office")
                        .collectionName("emails")
                        .build(),
                IngestPipelineDefinition.builder()
                        .pipelineId("text-pipeline")
                        .displayName("Text Pipeline")
                        .pipelineType(IngestPipelineDefinition.PipelineType.STANDARD_TEXT)
                        .loaderName("tika")
                        .collectionName("emails")
                        .build(),
                IngestPipelineDefinition.builder()
                        .pipelineId("image-pipeline")
                        .displayName("Image Pipeline")
                        .pipelineType(IngestPipelineDefinition.PipelineType.VLM)
                        .loaderName("vlm")
                        .collectionName("emails")
                        .build(),
                IngestPipelineDefinition.builder()
                        .pipelineId("email-text")
                        .displayName("Email Text Pipeline")
                        .pipelineType(IngestPipelineDefinition.PipelineType.STANDARD_TEXT)
                        .loaderName("email-inbox")
                        .collectionName("emails")
                        .build()
        );

        List<ContentRouteRule> rules = List.of(
                ContentRouteRule.builder()
                        .pipelineId("pdf-pipeline")
                        .contentTypes(List.of("application/pdf"))
                        .priority(10)
                        .build(),
                ContentRouteRule.builder()
                        .pipelineId("office-pipeline")
                        .contentTypes(List.of(
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                                "application/msword",
                                "application/vnd.ms-excel",
                                "application/vnd.ms-powerpoint"
                        ))
                        .priority(20)
                        .build(),
                ContentRouteRule.builder()
                        .pipelineId("text-pipeline")
                        .contentTypes(List.of("text/plain", "text/csv", "text/markdown"))
                        .priority(30)
                        .build(),
                ContentRouteRule.builder()
                        .pipelineId("image-pipeline")
                        .contentTypes(List.of("image/*"))
                        .priority(40)
                        .build()
        );

        CrawlConfig config = CrawlConfig.builder()
                .seed("/path/to/mailbox")
                .pipelines(pipelines)
                .routeRules(rules)
                .defaultPipelineId("email-text")
                .build();

        return new CrawlPipelineRouter(config);
    }

    /**
     * Creates a CrawlItem that matches what EmailInboxCrawler.emitAttachmentItems
     * and emitPstAttachmentItems produce.
     */
    private CrawlItem emailAttachmentItem(String url, String parentUrl,
                                           String mimeType, String filename,
                                           long sizeBytes) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("email.isAttachment", true);
        meta.put("email.parentMessageId", "<msg123@example.com>");
        meta.put("email.parentSubject", "Test Email");
        meta.put("email.from", "sender@example.com");
        meta.put("email.attachmentName", filename);
        meta.put("email.attachmentMimeType", mimeType);
        meta.put("email.attachmentSize", (int) sizeBytes);
        meta.put("source_type", "EMAIL_ATTACHMENT");

        return CrawlItem.builder()
                .url(url)
                .parentUrl(parentUrl)
                .depth(1)
                .sourceDescriptor(DocumentSourceDescriptor.builder()
                        .type(SourceType.FILE)
                        .pathOrUrl("/tmp/attachment-" + filename)
                        .originalFileName(filename)
                        .collectionName("emails")
                        .metadata(meta)
                        .build())
                .contentType(mimeType)
                .contentLength(sizeBytes)
                .discoveredAt(Instant.now())
                .metadata(new LinkedHashMap<>(meta))
                .build();
    }
}
