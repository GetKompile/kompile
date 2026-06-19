/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.fpna.v3;

import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedEntity;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedRelation;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractionResult;
import ai.kompile.knowledgegraph.impl.EntityExtractionServiceImpl;
import ai.kompile.knowledgegraph.resolution.EntityResolutionService;
import ai.kompile.knowledgegraph.service.EntityExtractionService;
import ai.kompile.knowledgegraph.service.EntityExtractionService.EntityType;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cross-document entity resolution tests that read actual HTML source files
 * from the FP&A workflow artifacts directory. Validates that:
 * <ul>
 *   <li>Pattern-based NER extracts persons from all 3 regional emails</li>
 *   <li>Entity resolution merges cross-document mentions (Mei Chen in 3+ docs)</li>
 *   <li>Different persons with same surname stay separate (Sarah Chen != Mei Chen)</li>
 *   <li>Organization suffix variants merge (Northstar Goods Inc = Northstar Goods)</li>
 *   <li>Channel taxonomy aliases resolve per the fpna-cpg-channel-v1 schema</li>
 *   <li>Currency registry entities from EMEA and APAC resolve correctly</li>
 * </ul>
 *
 * Ground truth is documented in {@code fpna-expected-graph.html}.
 */
class FpnaCrossEntityResolutionTest {

    private static final Path ARTIFACTS_DIR = resolveArtifactsDir();
    private EntityExtractionServiceImpl extractionService;
    private EntityResolutionService resolutionService;

    // Loaded HTML text content (HTML tags stripped)
    private String amerEmailText;
    private String emeaEmailText;
    private String apacEmailText;
    private String inboxText;

    @BeforeEach
    void setUp() throws Exception {
        extractionService = new EntityExtractionServiceImpl();
        resolutionService = new EntityResolutionService();

        amerEmailText = loadAndStripHtml("06a_email_AMER.html");
        emeaEmailText = loadAndStripHtml("06b_email_EMEA.html");
        apacEmailText = loadAndStripHtml("06c_email_APAC.html");
        inboxText = loadAndStripHtml("06_inbox.html");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CROSS-DOCUMENT PERSON EXTRACTION FROM LIVE HTML
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AMER email HTML: extract Sarah Chen and Mei Chen")
    void amerEmailExtractsPersons() {
        List<EntityExtractionService.ExtractedEntity> persons = extractionService.extractEntities(
                amerEmailText, List.of(EntityType.PERSON));
        Set<String> names = personNames(persons);

        assertTrue(names.contains("Sarah Chen"),
                "AMER email should yield 'Sarah Chen'; found: " + names);
        assertTrue(names.contains("Mei Chen"),
                "AMER email should yield 'Mei Chen'; found: " + names);
    }

    @Test
    @DisplayName("EMEA email HTML: extract François Vasseur (Unicode accented name)")
    void emeaEmailExtractsVasseur() {
        List<EntityExtractionService.ExtractedEntity> persons = extractionService.extractEntities(
                emeaEmailText, List.of(EntityType.PERSON));
        Set<String> names = personNames(persons);

        boolean hasVasseur = names.stream().anyMatch(n -> n.contains("Vasseur"));
        assertTrue(hasVasseur,
                "EMEA email should extract 'François Vasseur'; found: " + names);
    }

    @Test
    @DisplayName("APAC email HTML: extract Ayako Tanaka")
    void apacEmailExtractsTanaka() {
        List<EntityExtractionService.ExtractedEntity> persons = extractionService.extractEntities(
                apacEmailText, List.of(EntityType.PERSON));
        Set<String> names = personNames(persons);

        boolean hasTanaka = names.stream().anyMatch(n -> n.contains("Tanaka"));
        assertTrue(hasTanaka,
                "APAC email should extract 'Ayako Tanaka'; found: " + names);
    }

    @Test
    @DisplayName("All 3 emails + inbox: Mei Chen appears in all documents")
    void meiChenAppearsInAllDocuments() {
        assertTrue(amerEmailText.contains("Mei Chen"),
                "AMER email text should contain 'Mei Chen'");
        assertTrue(emeaEmailText.contains("Mei Chen"),
                "EMEA email text should contain 'Mei Chen'");
        assertTrue(apacEmailText.contains("Mei Chen") || apacEmailText.contains("m.chen"),
                "APAC email text should reference Mei Chen");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CROSS-DOCUMENT ENTITY RESOLUTION
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Mei Chen from 3 regional emails resolves to 1 canonical entity")
    void meiChenResolvesAcrossThreeEmails() {
        ExtractionResult resolved = resolutionService.resolve(List.of(
                buildPersonExtraction("amer", "Mei Chen", "AMER forecast recipient"),
                buildPersonExtraction("emea", "Mei Chen", "EMEA forecast recipient"),
                buildPersonExtraction("apac", "Mei Chen", "APAC forecast recipient")
        ));

        long count = resolved.entities().stream()
                .filter(e -> e.name().equals("Mei Chen") && e.type().equals("PERSON"))
                .count();
        assertEquals(1, count, "Mei Chen should resolve to exactly 1 entity across 3 emails");
    }

    @Test
    @DisplayName("Sarah Chen and Mei Chen stay separate (different persons, same surname)")
    void sarahAndMeiChenStaySeparate() {
        ExtractionResult resolved = resolutionService.resolve(List.of(
                buildPersonExtraction("amer-1", "Sarah Chen", "AMER Sales Ops"),
                buildPersonExtraction("amer-2", "Mei Chen", "VP FP&A")
        ));

        assertEquals(2, resolved.entities().size(),
                "Sarah Chen and Mei Chen must remain as 2 separate entities");
    }

    @Test
    @DisplayName("J. Park from AMER email, inbox Slack, and process map resolves to 1")
    void jParkResolvesAcrossDocuments() {
        ExtractionResult resolved = resolutionService.resolve(List.of(
                buildPersonExtraction("amer", "J. Park", "AMER email recipient"),
                buildPersonExtraction("inbox", "J. Park", "Slack mention in #fpa-close-cycle"),
                buildPersonExtraction("process", "J. Park", "Director FP&A, triage lead")
        ));

        long count = resolved.entities().stream()
                .filter(e -> e.name().equals("J. Park") && e.type().equals("PERSON"))
                .count();
        assertEquals(1, count, "J. Park should resolve to 1 entity across 3 documents");
    }

    @Test
    @DisplayName("Full person resolution: 12 persons across all emails merge correctly")
    void fullPersonResolutionAcrossAllEmails() {
        ExtractionResult resolved = resolutionService.resolve(List.of(
                // AMER email persons
                ExtractionResult.of(List.of(
                        person("a1", "Sarah Chen", "AMER Sales Ops Senior Manager"),
                        person("a2", "Mei Chen", "Forecast recipient"),
                        person("a3", "J. Park", "Forecast recipient")
                ), List.of(), null),
                // EMEA email persons
                ExtractionResult.of(List.of(
                        person("e1", "François Vasseur", "EMEA Sales Director"),
                        person("e2", "Mei Chen", "Consolidation recipient"),
                        person("e3", "Kira O'Donnell", "UK Retail Manager"),
                        person("e4", "Lukas Schmidt", "Germany/EU North"),
                        person("e5", "Paolo Greco", "Southern Europe")
                ), List.of(), null),
                // APAC email persons
                ExtractionResult.of(List.of(
                        person("p1", "Ayako Tanaka", "APAC Senior Sales Analyst"),
                        person("p2", "Mei Chen", "Consolidation recipient"),
                        person("p3", "M. Whitfield", "Australia lead")
                ), List.of(), null),
                // Process map persons
                ExtractionResult.of(List.of(
                        person("m1", "Mei Chen", "VP FP&A"),
                        person("m2", "J. Park", "Director FP&A"),
                        person("m3", "S. Reyes", "Senior Analyst"),
                        person("m4", "L. Okafor", "Controller"),
                        person("m5", "M. Sato", "CFO")
                ), List.of(), null)
        ));

        // Mei Chen: a2, e2, p2, m1 → 1 entity
        assertEquals(1, countByName(resolved, "Mei Chen"),
                "Mei Chen from 4 docs → 1 entity");

        // J. Park: a3, m2 → 1 entity
        assertEquals(1, countByName(resolved, "J. Park"),
                "J. Park from 2 docs → 1 entity");

        // Sarah Chen stays separate from Mei Chen
        assertEquals(1, countByName(resolved, "Sarah Chen"),
                "Sarah Chen should be its own entity");

        // Total unique persons: Sarah Chen, Mei Chen, J. Park, François Vasseur,
        // Kira O'Donnell, Lukas Schmidt, Paolo Greco, Ayako Tanaka, M. Whitfield,
        // S. Reyes, L. Okafor, M. Sato = 12
        assertEquals(12, resolved.entities().size(),
                "12 unique persons after cross-document resolution");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ORGANIZATION RESOLUTION
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Northstar Goods Inc + Northstar Goods merge (suffix stripping)")
    void northstarGoodsSuffixResolution() {
        ExtractionResult resolved = resolutionService.resolve(List.of(
                ExtractionResult.of(List.of(
                        org("o1", "Northstar Goods Inc", "AMER email signature"),
                        org("o2", "Northstar Goods", "Process map reference")
                ), List.of(), null)
        ));

        long count = resolved.entities().stream()
                .filter(e -> e.type().equals("ORGANIZATION"))
                .count();
        assertEquals(1, count, "Northstar Goods Inc and Northstar Goods should merge");
    }

    @Test
    @DisplayName("Northstar Goods Inc + Northstar Goods Corporation merge")
    void northstarCorporationVariant() {
        ExtractionResult resolved = resolutionService.resolve(List.of(
                ExtractionResult.of(List.of(
                        org("o1", "Northstar Goods Inc", "From email"),
                        org("o2", "Northstar Goods Corporation", "From legal doc")
                ), List.of(), null)
        ));

        long count = resolved.entities().stream()
                .filter(e -> e.type().equals("ORGANIZATION"))
                .count();
        assertEquals(1, count, "Inc and Corporation variants should merge");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CHANNEL TAXONOMY ALIAS RESOLUTION
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("DTC aliases from 3 regions resolve: DTC, Direct-to-Consumer, ecom")
    void dtcAliasResolution() {
        ExtractionResult resolved = resolutionService.resolve(List.of(
                ExtractionResult.of(List.of(
                        new ExtractedEntity("ch-amer-1", "DTC", "CHANNEL_TAXONOMY",
                                List.of("Direct-to-Consumer", "ecom"), "AMER DTC", 0.9, Map.of()),
                        new ExtractedEntity("ch-emea-1", "Direct-to-Consumer", "CHANNEL_TAXONOMY",
                                List.of(), "EMEA DTC variant", 0.8, Map.of()),
                        new ExtractedEntity("ch-apac-1", "EC", "CHANNEL_TAXONOMY",
                                List.of(), "APAC EC = DTC", 0.8, Map.of())
                ), List.of(), null)
        ));

        long channelCount = resolved.entities().stream()
                .filter(e -> e.type().equals("CHANNEL_TAXONOMY"))
                .count();

        // DTC + Direct-to-Consumer should merge (alias match). EC may stay separate.
        assertTrue(channelCount <= 2,
                "DTC and Direct-to-Consumer must merge via alias; EC may or may not merge. Got " + channelCount);
    }

    @Test
    @DisplayName("Wholesale aliases resolve: Wholesale, Retail, Distributor, B2B")
    void wholesaleAliasResolution() {
        ExtractionResult resolved = resolutionService.resolve(List.of(
                ExtractionResult.of(List.of(
                        new ExtractedEntity("ch1", "Wholesale", "CHANNEL_TAXONOMY",
                                List.of("Retail", "Distributor", "B2B", "Whlsl"),
                                "Wholesale umbrella", 0.9, Map.of()),
                        new ExtractedEntity("ch2", "Retail", "CHANNEL_TAXONOMY",
                                List.of(), "EMEA Retail = Wholesale", 0.8, Map.of()),
                        new ExtractedEntity("ch3", "B2B", "CHANNEL_TAXONOMY",
                                List.of(), "APAC B2B = Wholesale", 0.8, Map.of())
                ), List.of(), null)
        ));

        long count = resolved.entities().stream()
                .filter(e -> e.type().equals("CHANNEL_TAXONOMY"))
                .count();
        assertEquals(1, count,
                "Wholesale + Retail + B2B should all merge via alias to 1 channel entity");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CURRENCY RESOLUTION
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Currency entities from EMEA and APAC stay distinct per ISO code")
    void currencyEntitiesStayDistinct() {
        ExtractionResult resolved = resolutionService.resolve(List.of(
                ExtractionResult.of(List.of(
                        new ExtractedEntity("cur1", "GBP", "CURRENCY_REGISTRY",
                                List.of("British Pound"), "UK currency", 0.95, Map.of()),
                        new ExtractedEntity("cur2", "EUR", "CURRENCY_REGISTRY",
                                List.of("Euro"), "Eurozone", 0.95, Map.of()),
                        new ExtractedEntity("cur3", "JPY", "CURRENCY_REGISTRY",
                                List.of("Japanese Yen"), "Japan", 0.95, Map.of()),
                        new ExtractedEntity("cur4", "AUD", "CURRENCY_REGISTRY",
                                List.of("Australian Dollar"), "Australia", 0.95, Map.of()),
                        new ExtractedEntity("cur5", "SGD", "CURRENCY_REGISTRY",
                                List.of("Singapore Dollar"), "Singapore", 0.95, Map.of()),
                        new ExtractedEntity("cur6", "USD", "CURRENCY_REGISTRY",
                                List.of("US Dollar"), "Reporting currency", 0.95, Map.of())
                ), List.of(), null)
        ));

        long count = resolved.entities().stream()
                .filter(e -> e.type().equals("CURRENCY_REGISTRY"))
                .count();
        assertEquals(6, count, "All 6 currencies (USD, EUR, GBP, JPY, AUD, SGD) must stay separate");
    }

    @Test
    @DisplayName("Same currency from different docs merges: GBP from EMEA appears once")
    void sameCurrencyFromDifferentDocsMerges() {
        ExtractionResult resolved = resolutionService.resolve(List.of(
                ExtractionResult.of(List.of(
                        new ExtractedEntity("e-gbp", "GBP", "CURRENCY_REGISTRY",
                                List.of(), "EMEA GBP", 0.9, Map.of())
                ), List.of(), null),
                ExtractionResult.of(List.of(
                        new ExtractedEntity("s-gbp", "GBP", "CURRENCY_REGISTRY",
                                List.of("British Pound"), "Semantic layer GBP", 0.95, Map.of())
                ), List.of(), null)
        ));

        long count = resolved.entities().stream()
                .filter(e -> e.type().equals("CURRENCY_REGISTRY"))
                .count();
        assertEquals(1, count, "GBP from two docs should merge to 1 entity");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RELATION REMAPPING ACROSS MERGED ENTITIES
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("SENT_TO relations from 3 emails all point to canonical Mei Chen")
    void sentToRelationsRemapToCanonicalMei() {
        ExtractionResult resolved = resolutionService.resolve(List.of(
                ExtractionResult.of(
                        List.of(
                                person("a-mei", "Mei Chen", "AMER recipient"),
                                new ExtractedEntity("a-email", "AMER forecast email from Sarah Chen", "EMAIL_MESSAGE",
                                        List.of(), "AMER email", 0.95, Map.of())
                        ),
                        List.of(new ExtractedRelation("a-email", "a-mei", "SENT_TO", "To Mei", 0.95, Map.of())),
                        null),
                ExtractionResult.of(
                        List.of(
                                person("e-mei", "Mei Chen", "EMEA recipient"),
                                new ExtractedEntity("e-email", "EMEA forecast email from Vasseur", "EMAIL_MESSAGE",
                                        List.of(), "EMEA email", 0.95, Map.of())
                        ),
                        List.of(new ExtractedRelation("e-email", "e-mei", "SENT_TO", "To Mei", 0.95, Map.of())),
                        null),
                ExtractionResult.of(
                        List.of(
                                person("p-mei", "Mei Chen", "APAC recipient"),
                                new ExtractedEntity("p-email", "APAC forecast email from Tanaka", "EMAIL_MESSAGE",
                                        List.of(), "APAC email", 0.95, Map.of())
                        ),
                        List.of(new ExtractedRelation("p-email", "p-mei", "SENT_TO", "To Mei", 0.95, Map.of())),
                        null)
        ));

        // All 3 Mei Chens merge → 1
        assertEquals(1, countByName(resolved, "Mei Chen"));

        // All SENT_TO relations should point to the same canonical ID
        Set<String> targets = resolved.relations().stream()
                .filter(r -> r.type().equals("SENT_TO"))
                .map(ExtractedRelation::target)
                .collect(Collectors.toSet());

        assertEquals(1, targets.size(),
                "All 3 SENT_TO relations should point to the same canonical Mei Chen ID");
        assertEquals(3, resolved.relations().stream()
                        .filter(r -> r.type().equals("SENT_TO")).count(),
                "Should have 3 SENT_TO relations (one per email)");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HTML ANSWER FILE VALIDATION
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Expected graph HTML answer file exists and contains ground truth")
    void expectedGraphHtmlExists() throws Exception {
        String html = new String(getClass().getClassLoader()
                .getResourceAsStream("fpna-expected-graph.html")
                .readAllBytes());

        // Verify the answer file documents all expected merge groups
        assertTrue(html.contains("Mei Chen"), "Answer file should document Mei Chen");
        assertTrue(html.contains("Sarah Chen"), "Answer file should document Sarah Chen");
        assertTrue(html.contains("Vasseur"), "Answer file should document Vasseur");
        assertTrue(html.contains("Ayako Tanaka"), "Answer file should document Tanaka");
        assertTrue(html.contains("J. Park"), "Answer file should document J. Park");
        assertTrue(html.contains("Northstar Goods"), "Answer file should document Northstar Goods");
        assertTrue(html.contains("DTC"), "Answer file should document channel taxonomy");
        assertTrue(html.contains("ChannelMismatch"), "Answer file should document variance triage");
        assertTrue(html.contains("C-01"), "Answer file should document controls");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════

    private static Path resolveArtifactsDir() {
        Path dir = Path.of(System.getProperty("user.home"),
                "Documents/GitHub/kompile/FP&A workflow artifacts 2026-05");
        if (!Files.isDirectory(dir)) {
            // Fallback: relative from project root
            dir = Path.of("../../FP&A workflow artifacts 2026-05").toAbsolutePath().normalize();
        }
        return dir;
    }

    private String loadAndStripHtml(String filename) throws IOException {
        Path file = ARTIFACTS_DIR.resolve(filename);
        if (!Files.exists(file)) {
            fail("Source HTML not found: " + file + " — ensure FP&A workflow artifacts are present");
        }
        String html = Files.readString(file);
        // Strip HTML tags, collapse whitespace
        String text = html.replaceAll("<style[^>]*>[\\s\\S]*?</style>", " ")
                .replaceAll("<script[^>]*>[\\s\\S]*?</script>", " ")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<")
                .replaceAll("&gt;", ">")
                .replaceAll("&nbsp;", " ")
                .replaceAll("&#x[0-9a-fA-F]+;", " ")
                .replaceAll("&[a-z]+;", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return text;
    }

    private ExtractionResult buildPersonExtraction(String prefix, String name, String desc) {
        return ExtractionResult.of(
                List.of(person(prefix + "-p", name, desc)),
                List.of(), null);
    }

    /** Creates a GraphExtractionSchema.ExtractedEntity of type PERSON */
    private static ExtractedEntity person(String id, String name, String desc) {
        return new ExtractedEntity(id, name, "PERSON", List.of(), desc, 0.9, Map.of());
    }

    /** Creates a GraphExtractionSchema.ExtractedEntity of type ORGANIZATION */
    private static ExtractedEntity org(String id, String name, String desc) {
        return new ExtractedEntity(id, name, "ORGANIZATION", List.of(), desc, 0.9, Map.of());
    }

    private Set<String> personNames(List<EntityExtractionService.ExtractedEntity> entities) {
        return entities.stream()
                .map(EntityExtractionService.ExtractedEntity::name)
                .collect(Collectors.toSet());
    }

    private long countByName(ExtractionResult result, String name) {
        return result.entities().stream()
                .filter(e -> e.name().equals(name))
                .count();
    }
}
