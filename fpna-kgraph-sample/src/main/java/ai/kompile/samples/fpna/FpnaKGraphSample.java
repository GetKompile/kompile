package ai.kompile.samples.fpna;

import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.explain.ReasoningTrace;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.graph.reasoning.unified.UnifiedGraphFormat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Hand-built FPNA graph fixture for testing the store-agnostic graph-reasoning API.
 */
public final class FpnaKGraphSample {

    private static final String GRAPH_ID = "fpna-pristine-sample-v2";
    private static final String SRC_EXPECTED_GRAPH = "kompile-fpna-v4/project/src/test/resources/fpna-expected-graph.html";
    private static final String SRC_CRAWL_RESULTS = "kompile-fpna-v4/project/src/test/resources/fpna-business-process-crawl-results.html";
    private static final String SRC_EXTRACTION_TEXT = "kompile-fpna-v4/project/src/test/java/ai/kompile/fpna/v3/FpnaEntityExtractionTest.java";
    private static final String SRC_CRAWL_BUILDERS = "kompile-fpna-v4/project/src/test/java/ai/kompile/fpna/v3/FpnaCrawlGraphExtractionTest.java";
    private static final String SRC_V8_CHECKPOINTS = "kompile-fpna-v8/data/graph/1/graph-extraction-checkpoints.json";
    private static final Pattern DURATION_PATTERN = Pattern.compile(
            "\\b([0-9]+(?:\\.[0-9]+)?)\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?|days?)\\b",
            Pattern.CASE_INSENSITIVE);

    private FpnaKGraphSample() {
    }

    public static void main(String[] args) throws Exception {
        Path output = args.length == 0 ? defaultOutputPath() : Path.of(args[0]);

        UnifiedGraph graph = buildGraph();
        List<ReasoningTrace> traces = buildReasoningTraces();

        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        graph.save(output);

        UnifiedGraph loaded = UnifiedGraph.load(output);
        printSummary(loaded, output, traces);
    }

    private static Path defaultOutputPath() {
        String baseDir = System.getProperty("fpna.sample.basedir", System.getProperty("user.dir"));
        return Path.of(baseDir, "target", "fpna-pristine-sample" + UnifiedGraphFormat.EXTENSION);
    }

    public static UnifiedGraph buildGraph() {
        UnifiedGraph graph = new UnifiedGraph()
                .graphId(GRAPH_ID)
                .factSheetId(1L)
                .meta("domain", "FPNA CPG channel workflow")
                .meta("buildMode", "manual-pristine-fixture")
                .meta("generatedAt", Instant.parse("2026-07-09T00:00:00Z").toString())
                .meta("sourceFixtures", List.of(
                        SRC_EXPECTED_GRAPH,
                        SRC_CRAWL_RESULTS,
                        SRC_EXTRACTION_TEXT,
                        SRC_CRAWL_BUILDERS,
                        SRC_V8_CHECKPOINTS));

        addPeople(graph);
        addEmailsAndArtifacts(graph);
        addForecastDomain(graph);
        addCloseProcess(graph);
        addCrawlerExpectedProcessGraph(graph);
        addRemainingCrawlerProcessGraph(graph);
        addObservedRelations(graph);
        addDerivedRelations(graph);

        graph.putWeightMap("manualRuleWeights", ruleWeights());
        graph.putArtifactText("reasoning-traces.jsonl", renderTraceJsonl(buildReasoningTraces()));
        graph.putArtifactText("source-notes.md", sourceNotes());
        return graph;
    }

    private static void addPeople(UnifiedGraph graph) {
        entity(graph, "person:mei_chen", "PERSON", "Mei Chen", 0.97,
                tags("person", "canonical", "consolidation"),
                attrs("source", SRC_EXPECTED_GRAPH,
                        "email", "m.chen@northstar.co",
                        "role", "VP FP&A, forecast consolidation lead",
                        "canonicalizedFrom", "AMER email, EMEA email, APAC email, inbox, process map"));
        entity(graph, "person:j_park", "PERSON", "J. Park", 0.93,
                tags("person", "approver"),
                attrs("source", SRC_EXPECTED_GRAPH,
                        "email", "j.park@northstar.co",
                        "role", "Forecast recipient and version gate approver"));
        entity(graph, "person:sarah_chen", "PERSON", "Sarah Chen", 0.95,
                tags("person", "sender", "amer"),
                attrs("source", SRC_CRAWL_BUILDERS,
                        "email", "s.chen@northstar.co",
                        "role", "Senior Manager, Sales Ops",
                        "region", "AMER"));
        entity(graph, "person:francois_vasseur", "PERSON", "Francois Vasseur", 0.95,
                tags("person", "sender", "emea"),
                attrs("source", SRC_CRAWL_BUILDERS,
                        "email", "f.vasseur@northstar.eu",
                        "role", "Sales Director",
                        "region", "EMEA"));
        entity(graph, "person:ayako_tanaka", "PERSON", "Ayako Tanaka", 0.92,
                tags("person", "sender", "apac"),
                attrs("source", SRC_EXPECTED_GRAPH,
                        "role", "Senior Sales Analyst, APAC",
                        "region", "APAC"));
        entity(graph, "person:s_reyes", "PERSON", "S. Reyes", 0.90,
                tags("person", "approver"),
                attrs("source", SRC_EXPECTED_GRAPH,
                        "role", "Version gate approver"));
        entity(graph, "person:kira_odonnell", "PERSON", "Kira O'Donnell", 0.90,
                tags("person", "emea"),
                attrs("source", SRC_CRAWL_BUILDERS,
                        "role", "UK Retail Manager"));
        entity(graph, "person:lukas_schmidt", "PERSON", "Lukas Schmidt", 0.90,
                tags("person", "emea"),
                attrs("source", SRC_CRAWL_BUILDERS,
                        "role", "Germany/EU North Manager"));
        entity(graph, "person:paolo_greco", "PERSON", "Paolo Greco", 0.90,
                tags("person", "emea"),
                attrs("source", SRC_CRAWL_BUILDERS,
                        "role", "Southern Europe Manager"));
    }

    private static void addEmailsAndArtifacts(UnifiedGraph graph) {
        entity(graph, "email:amer_q3_forecast", "EMAIL_MESSAGE", "AMER forecast Q3 email", 0.95,
                tags("email", "amer", "observed"),
                attrs("source", SRC_CRAWL_BUILDERS,
                        "sourceDocument", "06a_email_AMER.html",
                        "subject", "AMER forecast Q3 (FINAL v2)",
                        "from", "s.chen@northstar.co",
                        "to", "m.chen@northstar.co; j.park@northstar.co",
                        "date", "2026-05-04"));
        entity(graph, "email:emea_q3_forecast", "EMAIL_MESSAGE", "EMEA Q3 forecast email", 0.95,
                tags("email", "emea", "observed"),
                attrs("source", SRC_CRAWL_BUILDERS,
                        "sourceDocument", "06b_email_EMEA.html",
                        "subject", "EMEA Q3 2026 forecast - for consolidation",
                        "from", "f.vasseur@northstar.eu",
                        "to", "m.chen@northstar.co",
                        "date", "2026-05-05"));
        entity(graph, "email:apac_forecast", "EMAIL_MESSAGE", "APAC forecast email", 0.88,
                tags("email", "apac", "expected"),
                attrs("source", SRC_EXPECTED_GRAPH,
                        "sourceDocument", "06c_email_APAC.html",
                        "from", "ayako.tanaka@northstar.jp",
                        "to", "m.chen@northstar.co",
                        "date", "2026-05-06"));

        entity(graph, "spreadsheet:amer_forecast_q3_final_v2", "SPREADSHEET", "AMER_Forecast_Q3_v3_FINAL_v2.xlsx", 0.95,
                tags("spreadsheet", "amer", "attachment"),
                attrs("source", SRC_CRAWL_BUILDERS,
                        "sheetCount", "3",
                        "version", "v3 FINAL v2",
                        "qualityNote", "Correct version; stale subtotal warning exists"));
        entity(graph, "spreadsheet:emea_forecast_jun_aug_2026", "SPREADSHEET", "EMEA forecast Jun-Aug 2026.xlsx", 0.95,
                tags("spreadsheet", "emea", "attachment"),
                attrs("source", SRC_CRAWL_BUILDERS,
                        "currencyBasis", "local currency",
                        "vatTreatment", "gross of VAT"));
        entity(graph, "spreadsheet:apac_fcst_fy27q1", "SPREADSHEET", "APAC fcst FY27Q1.xlsx", 0.88,
                tags("spreadsheet", "apac", "attachment"),
                attrs("source", SRC_EXPECTED_GRAPH,
                        "fiscalBasis", "FY27 Q1"));

        entity(graph, "table:amer_wholesale", "TABLE", "AMER Wholesale tab", 0.90,
                tags("table", "amer", "wholesale"), attrs("source", SRC_CRAWL_BUILDERS, "rowCount", "45"));
        entity(graph, "table:amer_dtc", "TABLE", "AMER DTC tab", 0.90,
                tags("table", "amer", "dtc"), attrs("source", SRC_CRAWL_BUILDERS, "rowCount", "32"));
        entity(graph, "table:emea_uk", "TABLE", "EMEA UK tab", 0.88,
                tags("table", "emea", "uk"), attrs("source", SRC_EXPECTED_GRAPH));
        entity(graph, "table:emea_de", "TABLE", "EMEA DE tab", 0.88,
                tags("table", "emea", "de"), attrs("source", SRC_EXPECTED_GRAPH));
        entity(graph, "table:apac_jp", "TABLE", "APAC JP tab", 0.86,
                tags("table", "apac", "jp"), attrs("source", SRC_EXPECTED_GRAPH));
        entity(graph, "table:apac_au", "TABLE", "APAC AU tab", 0.86,
                tags("table", "apac", "au"), attrs("source", SRC_EXPECTED_GRAPH));
        entity(graph, "table:apac_sg", "TABLE", "APAC SG tab", 0.86,
                tags("table", "apac", "sg"), attrs("source", SRC_EXPECTED_GRAPH));
    }

    private static void addForecastDomain(UnifiedGraph graph) {
        entity(graph, "forecast:amer_q3_2026", "REGIONAL_FORECAST", "Americas Regional Forecast Q3 2026", 0.92,
                tags("forecast", "amer"),
                attrs("source", SRC_CRAWL_BUILDERS,
                        "region", "AMER",
                        "forecastCycle", "Q3 2026",
                        "submittedBy", "Sarah Chen"));
        entity(graph, "forecast:emea_q3_2026", "REGIONAL_FORECAST", "Europe Middle East Africa Regional Forecast Q3 2026", 0.92,
                tags("forecast", "emea"),
                attrs("source", SRC_CRAWL_BUILDERS,
                        "region", "EMEA",
                        "forecastCycle", "Q3 2026",
                        "submittedBy", "Francois Vasseur"));
        entity(graph, "forecast:apac_fy27q1", "REGIONAL_FORECAST", "APAC Regional Forecast FY27 Q1", 0.88,
                tags("forecast", "apac"),
                attrs("source", SRC_EXPECTED_GRAPH,
                        "region", "APAC",
                        "forecastCycle", "FY27 Q1",
                        "submittedBy", "Ayako Tanaka"));
        entity(graph, "sku:hyd_110", "SKU_MASTER", "HYD-110", 0.86,
                tags("sku", "amer", "launch"),
                attrs("source", SRC_EXTRACTION_TEXT,
                        "canonicalName", "Sleep Serum",
                        "launchDate", "2026-07-01",
                        "lifecycleStatus", "LAUNCH"));
        entity(graph, "dq:amer_stale_subtotals", "DATA_QUALITY_FLAG", "Stale subtotals in AMER forecast", 0.80,
                tags("data-quality", "amer", "warning"),
                attrs("source", SRC_CRAWL_BUILDERS,
                        "flagType", "WARN",
                        "description", "Subtotals and GRAND TOTAL are stale"));

        entity(graph, "channel:dtc", "CHANNEL_TAXONOMY", "DTC", 0.90,
                tags("channel", "canonical"), attrs("source", SRC_EXPECTED_GRAPH, "aliases", "Direct-to-Consumer,ecom,EC"));
        entity(graph, "channel:marketplace", "CHANNEL_TAXONOMY", "Marketplace", 0.88,
                tags("channel"), attrs("source", SRC_CRAWL_BUILDERS, "aliases", "Amazon"));
        entity(graph, "channel:wholesale", "CHANNEL_TAXONOMY", "Wholesale", 0.88,
                tags("channel"), attrs("source", SRC_EXPECTED_GRAPH, "aliases", "Retail,Distributor,B2B"));
        entity(graph, "channel:amazon", "CHANNEL_TAXONOMY", "Amazon", 0.86,
                tags("channel", "marketplace"), attrs("source", SRC_EXPECTED_GRAPH));
        entity(graph, "currency:gbp", "CURRENCY_REGISTRY", "GBP", 0.90,
                tags("currency", "emea"), attrs("source", SRC_CRAWL_BUILDERS, "currencyCode", "GBP"));
        entity(graph, "currency:eur", "CURRENCY_REGISTRY", "EUR", 0.90,
                tags("currency", "emea"), attrs("source", SRC_CRAWL_BUILDERS, "currencyCode", "EUR"));
    }

    private static void addCloseProcess(UnifiedGraph graph) {
        entity(graph, "close:regional_workbook_intake", "CLOSE_STEP", "Receive regional forecast workbooks", 0.90,
                tags("close-step", "inputs-intake"),
                attrs("source", SRC_EXPECTED_GRAPH,
                        "stepId", "1.4",
                        "phase", "Inputs and Intake",
                        "automationStatus", "AUTO"));
        entity(graph, "close:version_assertion_gate", "CLOSE_STEP", "Version Assertion Gate", 0.90,
                tags("close-step", "approval"),
                attrs("source", SRC_CRAWL_BUILDERS,
                        "stepId", "1.5",
                        "phase", "Pre-Processing",
                        "automationStatus", "HITL"));
        entity(graph, "close:validate_triage_variances", "CLOSE_STEP", "Validate forecast workbook and triage variances", 0.90,
                tags("close-step", "variance"),
                attrs("source", SRC_EXPECTED_GRAPH,
                        "stepId", "2.1",
                        "phase", "Validate and Reconcile",
                        "automationStatus", "AUTO + HITL"));
        entity(graph, "close:fx_translate_forecast", "CLOSE_STEP", "FX-translate forecast lines to USD", 0.88,
                tags("close-step", "fx"),
                attrs("source", SRC_EXPECTED_GRAPH,
                        "stepId", "2.3",
                        "phase", "Validate and Reconcile",
                        "automationStatus", "AUTO"));
        entity(graph, "close:tb_tie_control", "CLOSE_STEP", "Trial Balance Tie Control", 0.90,
                tags("close-step", "control"),
                attrs("source", SRC_CRAWL_BUILDERS,
                        "stepId", "4.1",
                        "phase", "Controls and Publish",
                        "automationStatus", "CONTROL_GATE"));

        entity(graph, "control:c01_tb_tie", "CONTROL_ASSERTION", "C-01 TB Tie", 0.95,
                tags("control", "sox", "hard"),
                attrs("source", SRC_CRAWL_BUILDERS,
                        "controlId", "C-01",
                        "controlType", "HARD",
                        "severity", "CRITICAL"));
        entity(graph, "control:c03_pipeline_coverage", "CONTROL_ASSERTION", "C-03 Pipeline Coverage", 0.88,
                tags("control"), attrs("source", SRC_EXPECTED_GRAPH));
        entity(graph, "control:c04_sku_mapping", "CONTROL_ASSERTION", "C-04 SKU Mapping", 0.88,
                tags("control"), attrs("source", SRC_EXPECTED_GRAPH));
        entity(graph, "control:c05_channel_gm", "CONTROL_ASSERTION", "C-05 Channel GM", 0.88,
                tags("control"), attrs("source", SRC_EXPECTED_GRAPH));
    }

    private static void addCrawlerExpectedProcessGraph(UnifiedGraph graph) {
        crawlEntity(graph, "person:l_okafor", "PERSON", "L. Okafor", 0.94,
                tags("crawler-expected", "person", "controller", "approver"),
                "role", "Controller close mechanics approver", "sourceDocument", "09_kaa_report.html");
        crawlEntity(graph, "person:m_sato", "PERSON", "M. Sato", 0.94,
                tags("crawler-expected", "person", "cfo", "approver"),
                "role", "CFO projection reasonableness approver", "sourceDocument", "09_kaa_report.html");
        crawlEntity(graph, "person:d_singh", "PERSON", "D. Singh", 0.90,
                tags("crawler-expected", "person", "data-quality"),
                "role", "Data quality and source-system support", "sourceDocument", "09_kaa_report.html");

        crawlStep(graph, "d0_s1", "1.1", 1, "Pull NetSuite Trial Balance & AR/AP", "Inputs and Intake", "AUTO",
                "netsuite", "trial-balance", "source-system");
        crawlStep(graph, "d0_s2", "1.2", 2, "Pull Shopify & Amazon Channel Revenue", "Inputs and Intake", "AUTO",
                "channel", "revenue", "source-system");
        crawlStep(graph, "d0_s3", "1.3", 3, "Pull FX Rates & Forward Curve", "Inputs and Intake", "AUTO",
                "fx", "source-system");
        crawlStep(graph, "d0_s4", "1.4", 4, "Receive Regional Forecast Workbooks", "Inputs and Intake", "AUTO",
                "forecast", "workbook", "intake");
        crawlStep(graph, "d0_s5", "1.5", 5, "Pull Workday Roster & Salesforce Pipeline", "Inputs and Intake", "AUTO",
                "workday", "salesforce", "pipeline");
        crawlStep(graph, "d0_s6", "1.6", 6, "Receive Marketing & OpEx Plan", "Inputs and Intake", "HITL",
                "marketing", "opex", "plan");
        crawlStep(graph, "d0_s7", "2.1", 7, "Validate Forecast & Triage Variances", "Validate and Reconcile", "AUTO + HITL",
                "validate", "triage", "variance");
        crawlStep(graph, "d0_s8", "2.2", 8, "Reconcile Forecast to Salesforce Pipeline", "Validate and Reconcile", "AUTO",
                "pipeline", "reconcile");
        crawlStep(graph, "d0_s9", "2.3", 9, "FX Translate to USD", "Validate and Reconcile", "AUTO",
                "fx", "currency", "usd");
        crawlStep(graph, "d0_s10", "3.1", 10, "Project COGS & OpEx", "Project and Consolidate", "AUTO + HITL",
                "cogs", "opex", "headcount", "projection");
        crawlStep(graph, "d0_s11", "3.3", 11, "Consolidate Group P&L", "Project and Consolidate", "AUTO",
                "consolidation", "intercompany", "group-pl");
        crawlStep(graph, "d0_s12", "4.1", 12, "Draft Variance Commentary", "Review and Publish", "HITL",
                "commentary", "cfo-pack");
        crawlStep(graph, "d0_s13", "4.2", 13, "CFO Sign-off", "Review and Publish", "HITL",
                "approval", "signoff");
        crawlStep(graph, "d0_s14", "4.3", 14, "Publish to Dashboard", "Review and Publish", "AUTO",
                "publish", "archive", "looker");

        crawlEntity(graph, "crawl:d0_vt1", "VARIANCE_TRIAGE", "Miscoded SKU Pattern", 0.92,
                tags("crawler-expected", "triage", "variance", "sku"),
                "crawlId", "d0_vt1", "goldPattern", "SKUTypoVariant", "routingPolicy", "AUTO-CORRECT >= 0.85; escalate below threshold");
        crawlEntity(graph, "crawl:d0_vt2", "VARIANCE_TRIAGE", "Channel Mismatch Pattern", 0.94,
                tags("crawler-expected", "triage", "variance", "channel"),
                "crawlId", "d0_vt2", "goldPattern", "ChannelMismatch", "routingPolicy", "ALWAYS ESCALATE to M. Chen");
        crawlEntity(graph, "crawl:d0_vt3", "VARIANCE_TRIAGE", "Currency Tag Mismatch Pattern", 0.92,
                tags("crawler-expected", "triage", "variance", "currency"),
                "crawlId", "d0_vt3", "goldPattern", "CurrencySymbolDrift", "routingPolicy", "AUTO-CORRECT >= 0.85; J. Park below threshold");
        crawlEntity(graph, "crawl:d0_vt4", "VARIANCE_TRIAGE", "Calendar Shift Pattern", 0.86,
                tags("crawler-expected", "triage", "variance", "calendar"),
                "crawlId", "d0_vt4", "routingPolicy", "Review calendar shift and fiscal cutover evidence");
        crawlEntity(graph, "crawl:d0_vt5", "VARIANCE_TRIAGE", "Other / Unknown Pattern", 0.86,
                tags("crawler-expected", "triage", "variance", "exception"),
                "crawlId", "d0_vt5", "routingPolicy", "Escalate unresolved variance exceptions");
        crawlEntity(graph, "crawl:gold_vt_gm_out_of_band", "VARIANCE_TRIAGE", "GM Out Of Band Pattern", 0.90,
                tags("crawler-expected", "triage", "variance", "gross-margin"),
                "goldPattern", "GMOutOfBand", "routingPolicy", "COMMENTARY REQUIRED; analyst to M. Chen");
        crawlEntity(graph, "crawl:gold_vt_stale_fx_row", "VARIANCE_TRIAGE", "Stale FX Row Pattern", 0.90,
                tags("crawler-expected", "triage", "variance", "fx", "quarantine"),
                "goldPattern", "StaleFXRow", "routingPolicy", "QUARANTINE; FX re-pull; >72h blocks close");

        crawlEntity(graph, "crawl:d0_c1", "CONTROL_ASSERTION", "D60 Regional Total Tie", 0.92,
                tags("crawler-expected", "control"), "crawlId", "d0_c1", "controlId", "D60", "expectedStepId", "2.1");
        crawlEntity(graph, "crawl:d0_c2", "CONTROL_ASSERTION", "Channel Total Tie", 0.92,
                tags("crawler-expected", "control"), "crawlId", "d0_c2", "controlId", "CHANNEL_TOTAL", "expectedStepId", "2.1");
        crawlEntity(graph, "crawl:d0_c3", "CONTROL_ASSERTION", "Currency Tag Consistency", 0.92,
                tags("crawler-expected", "control", "fx"), "crawlId", "d0_c3", "controlId", "CURRENCY_TAG", "expectedStepId", "2.1");
        crawlEntity(graph, "crawl:d0_ar1", "APPROVAL_ROLE", "CFO Projection Sign-off Approval Role", 0.90,
                tags("crawler-expected", "approval", "role"), "crawlId", "d0_ar1", "expectedStepId", "4.2");

        crawlEntity(graph, "crawl:d0_p1", "PERSON", "M. Chen", 0.92,
                tags("crawler-expected", "person", "approver"), "crawlId", "d0_p1", "canonicalEntityId", "person:mei_chen");
        crawlEntity(graph, "crawl:d0_p2", "PERSON", "J. Park", 0.92,
                tags("crawler-expected", "person", "approver"), "crawlId", "d0_p2", "canonicalEntityId", "person:j_park");
        crawlEntity(graph, "crawl:d0_p3", "PERSON", "S. Reyes", 0.90,
                tags("crawler-expected", "person", "approver"), "crawlId", "d0_p3", "canonicalEntityId", "person:s_reyes");
        crawlEntity(graph, "crawl:d0_p4", "PERSON", "A. Tanaka", 0.90,
                tags("crawler-expected", "person", "apac"), "crawlId", "d0_p4", "canonicalEntityId", "person:ayako_tanaka");

        crawlEntity(graph, "crawl:d0_rf1", "REGIONAL_FORECAST", "AMER Forecast Workbook", 0.93,
                tags("crawler-expected", "forecast", "workbook", "amer"), "crawlId", "d0_rf1", "region", "AMER");
        crawlEntity(graph, "crawl:d0_rf2", "REGIONAL_FORECAST", "EMEA Forecast Workbook", 0.93,
                tags("crawler-expected", "forecast", "workbook", "emea"), "crawlId", "d0_rf2", "region", "EMEA");
        crawlEntity(graph, "crawl:d0_rf3", "REGIONAL_FORECAST", "APAC Forecast Workbook", 0.93,
                tags("crawler-expected", "forecast", "workbook", "apac"), "crawlId", "d0_rf3", "region", "APAC");
        crawlEntity(graph, "crawl:d0_ct1", "CHANNEL_TAXONOMY", "Northstar Channel Taxonomy", 0.92,
                tags("crawler-expected", "taxonomy", "channel"), "crawlId", "d0_ct1");
        crawlEntity(graph, "crawl:d0_fx1", "FX_FORWARD_CURVE", "FX Forward Curve Apr 2026", 0.92,
                tags("crawler-expected", "fx", "currency", "source-artifact"), "crawlId", "d0_fx1");
        crawlEntity(graph, "crawl:d0_pc1", "CONTROL_ASSERTION", "Wholesale Pipeline Coverage Ratio", 0.90,
                tags("crawler-expected", "control", "pipeline"), "crawlId", "d0_pc1", "controlId", "PIPELINE_COVERAGE", "expectedStepId", "2.2");
        crawlEntity(graph, "crawl:d0_dq1", "DATA_QUALITY_FLAG", "SKU Mapping Miss Flag", 0.90,
                tags("crawler-expected", "data-quality", "sku"), "crawlId", "d0_dq1");
        crawlEntity(graph, "crawl:d0_fa1", "AUTO_FIX_BATCH", "Auto-Fix Batch", 0.90,
                tags("crawler-expected", "auto-correct", "fix-batch"), "crawlId", "d0_fa1", "expectedStepId", "2.1");

        crawlEntity(graph, "crawl:d0_sp1", "SOURCE_SYSTEM", "NetSuite Trial Balance", 0.92,
                tags("crawler-expected", "source-system", "netsuite", "trial-balance"), "crawlId", "d0_sp1");
        crawlEntity(graph, "crawl:d0_sp2", "SOURCE_SYSTEM", "NetSuite AR", 0.90,
                tags("crawler-expected", "source-system", "netsuite", "ar"), "crawlId", "d0_sp2");
        crawlEntity(graph, "crawl:d0_sp3", "SOURCE_SYSTEM", "NetSuite AP", 0.90,
                tags("crawler-expected", "source-system", "netsuite", "ap"), "crawlId", "d0_sp3");
        crawlEntity(graph, "crawl:d0_sp4", "SOURCE_SYSTEM", "Shopify Channel Revenue", 0.90,
                tags("crawler-expected", "source-system", "shopify", "channel-revenue"), "crawlId", "d0_sp4");
        crawlEntity(graph, "crawl:d0_sp5", "SOURCE_SYSTEM", "Amazon Channel Revenue", 0.90,
                tags("crawler-expected", "source-system", "amazon", "channel-revenue"), "crawlId", "d0_sp5");
        crawlEntity(graph, "crawl:d0_sp6", "SOURCE_SYSTEM", "FX Rate Provider", 0.90,
                tags("crawler-expected", "source-system", "fx"), "crawlId", "d0_sp6");
        crawlEntity(graph, "crawl:d0_sp7", "SOURCE_SYSTEM", "Workday Roster", 0.90,
                tags("crawler-expected", "source-system", "workday", "headcount"), "crawlId", "d0_sp7");
        crawlEntity(graph, "crawl:d0_sp8", "SOURCE_SYSTEM", "Salesforce Pipeline", 0.90,
                tags("crawler-expected", "source-system", "salesforce", "pipeline"), "crawlId", "d0_sp8");

        crawlGoldControl(graph, "crawl:control_c01_fx_consistency", "C-01", "FX Consistency", "2.3", "J. Park", "HARD BLOCK CLOSE", "fx", "currency");
        crawlGoldControl(graph, "crawl:control_c02_regional_total", "C-02", "Regional Total", "3.3", "J. Park", "HARD BLOCK CLOSE", "regional", "total");
        crawlGoldControl(graph, "crawl:control_c03_pipeline_coverage", "C-03", "Pipeline Coverage", "2.2", "M. Chen", "SOFT COMMENTARY", "pipeline", "coverage");
        crawlGoldControl(graph, "crawl:control_c04_sku_mapping", "C-04", "SKU Mapping", "2.1", "J. Park", "HARD ALWAYS ESCALATE", "sku", "mapping");
        crawlGoldControl(graph, "crawl:control_c05_channel_gm_band", "C-05", "Channel GM Band", "2.1", "M. Chen", "SOFT COMMENTARY", "channel", "gm");
        crawlGoldControl(graph, "crawl:control_c06_sku_margin_discipline", "C-06", "SKU Margin Discipline", "3.1", "J. Park", "SOFT COMMENTARY", "sku", "margin");
        crawlGoldControl(graph, "crawl:control_c07_statement_reconciliation", "C-07", "Statement Reconciliation", "4.2", "M. Chen", "HARD BLOCK CLOSE", "statement", "reconciliation");

        crawlRelation(graph, "crawl:rel_d0_s1_feeds_d0_s7", "crawl:d0_s1", "crawl:d0_s7", "FEEDS_INTO", 0.96,
                "NetSuite trial balance and AR/AP feed forecast validation");
        crawlRelation(graph, "crawl:rel_d0_s2_feeds_d0_s7", "crawl:d0_s2", "crawl:d0_s7", "FEEDS_INTO", 0.96,
                "Shopify and Amazon revenue feed forecast validation");
        crawlRelation(graph, "crawl:rel_d0_s3_feeds_d0_s9", "crawl:d0_s3", "crawl:d0_s9", "FEEDS_INTO", 0.96,
                "FX rates feed USD translation");
        crawlRelation(graph, "crawl:rel_d0_s4_feeds_d0_s7", "crawl:d0_s4", "crawl:d0_s7", "FEEDS_INTO", 0.96,
                "Regional forecast workbooks feed variance triage");
        crawlRelation(graph, "crawl:rel_d0_s5_feeds_d0_s8", "crawl:d0_s5", "crawl:d0_s8", "FEEDS_INTO", 0.96,
                "Salesforce pipeline feed reconciles the forecast");
        crawlRelation(graph, "crawl:rel_d0_s5_feeds_d0_s10", "crawl:d0_s5", "crawl:d0_s10", "FEEDS_INTO", 0.95,
                "Workday roster feeds COGS and OpEx projection");
        crawlRelation(graph, "crawl:rel_d0_s6_feeds_d0_s10", "crawl:d0_s6", "crawl:d0_s10", "FEEDS_INTO", 0.95,
                "Marketing and OpEx plan feeds projection");
        crawlRelation(graph, "crawl:rel_d0_s7_feeds_d0_s9", "crawl:d0_s7", "crawl:d0_s9", "FEEDS_INTO", 0.96,
                "Validated forecast lines feed FX translation");
        crawlRelation(graph, "crawl:rel_d0_s8_feeds_d0_s10", "crawl:d0_s8", "crawl:d0_s10", "FEEDS_INTO", 0.95,
                "Pipeline reconciliation feeds projection");
        crawlRelation(graph, "crawl:rel_d0_s9_feeds_d0_s10", "crawl:d0_s9", "crawl:d0_s10", "FEEDS_INTO", 0.95,
                "USD-translated forecast feeds projection");
        crawlRelation(graph, "crawl:rel_d0_s10_feeds_d0_s11", "crawl:d0_s10", "crawl:d0_s11", "FEEDS_INTO", 0.96,
                "Projection feeds group P&L consolidation");
        crawlRelation(graph, "crawl:rel_d0_s11_feeds_d0_s12", "crawl:d0_s11", "crawl:d0_s12", "FEEDS_INTO", 0.96,
                "Consolidation feeds variance commentary");
        crawlRelation(graph, "crawl:rel_d0_s12_feeds_d0_s13", "crawl:d0_s12", "crawl:d0_s13", "FEEDS_INTO", 0.96,
                "Commentary feeds CFO sign-off");
        crawlRelation(graph, "crawl:rel_d0_s13_feeds_d0_s14", "crawl:d0_s13", "crawl:d0_s14", "FEEDS_INTO", 0.96,
                "CFO sign-off feeds dashboard publish and archive");

        crawlRelation(graph, "crawl:rel_d0_rf1_part_of_d0_s4", "crawl:d0_rf1", "crawl:d0_s4", "PART_OF", 0.92,
                "AMER forecast workbook belongs to regional workbook intake");
        crawlRelation(graph, "crawl:rel_d0_rf2_part_of_d0_s4", "crawl:d0_rf2", "crawl:d0_s4", "PART_OF", 0.92,
                "EMEA forecast workbook belongs to regional workbook intake");
        crawlRelation(graph, "crawl:rel_d0_rf3_part_of_d0_s4", "crawl:d0_rf3", "crawl:d0_s4", "PART_OF", 0.92,
                "APAC forecast workbook belongs to regional workbook intake");
        crawlRelation(graph, "crawl:rel_d0_rf1_submitted_by_d0_p2", "crawl:d0_rf1", "crawl:d0_p2", "SUBMITTED_BY", 0.90,
                "Crawl fixture links AMER forecast workbook to J. Park submitter/recipient evidence");
        crawlRelation(graph, "crawl:rel_d0_rf2_submitted_by_d0_p3", "crawl:d0_rf2", "crawl:d0_p3", "SUBMITTED_BY", 0.90,
                "Crawl fixture links EMEA forecast workbook to S. Reyes submitter/recipient evidence");
        crawlRelation(graph, "crawl:rel_d0_rf3_submitted_by_d0_p4", "crawl:d0_rf3", "crawl:d0_p4", "SUBMITTED_BY", 0.90,
                "Crawl fixture links APAC forecast workbook to A. Tanaka submitter/recipient evidence");
        crawlRelation(graph, "crawl:rel_d0_rf1_refs_ct1", "crawl:d0_rf1", "crawl:d0_ct1", "REFERENCES_TAXONOMY", 0.92,
                "AMER forecast references Northstar channel taxonomy");
        crawlRelation(graph, "crawl:rel_d0_rf2_refs_ct1", "crawl:d0_rf2", "crawl:d0_ct1", "REFERENCES_TAXONOMY", 0.92,
                "EMEA forecast references Northstar channel taxonomy");
        crawlRelation(graph, "crawl:rel_d0_rf3_refs_ct1", "crawl:d0_rf3", "crawl:d0_ct1", "REFERENCES_TAXONOMY", 0.92,
                "APAC forecast references Northstar channel taxonomy");

        crawlRelation(graph, "crawl:rel_d0_c1_validates_d0_s7", "crawl:d0_c1", "crawl:d0_s7", "VALIDATES", 0.92,
                "Regional total tie validates variance triage");
        crawlRelation(graph, "crawl:rel_d0_c2_validates_d0_s7", "crawl:d0_c2", "crawl:d0_s7", "VALIDATES", 0.92,
                "Channel total tie validates variance triage");
        crawlRelation(graph, "crawl:rel_d0_c3_validates_d0_s7", "crawl:d0_c3", "crawl:d0_s7", "VALIDATES", 0.92,
                "Currency tag consistency validates variance triage");
        crawlRelation(graph, "crawl:rel_d0_pc1_validates_d0_s8", "crawl:d0_pc1", "crawl:d0_s8", "VALIDATES", 0.90,
                "Pipeline coverage ratio validates Salesforce reconciliation");
        crawlRelation(graph, "crawl:rel_d0_s13_approved_by_ar1", "crawl:d0_s13", "crawl:d0_ar1", "APPROVED_BY", 0.90,
                "Sign-off approval is due within 2 hours before publish");

        crawlRelation(graph, "crawl:rel_d0_vt2_escalated_to_p1", "crawl:d0_vt2", "person:mei_chen", "ESCALATED_TO", 0.94,
                "Exception review is overdue after 2 hours; SLA breached and escalation is required");
        crawlRelation(graph, "crawl:rel_d0_vt1_escalated_to_p2", "crawl:d0_vt1", "person:j_park", "ESCALATED_TO", 0.88,
                "Exception owner must correct or rework the item within 1 hour before escalation");
        crawlRelation(graph, "crawl:rel_d0_vt3_escalated_to_p2", "crawl:d0_vt3", "person:j_park", "ESCALATED_TO", 0.88,
                "Exception review waits on owner response and blocks completion until corrected");
        crawlRelation(graph, "crawl:rel_gm_out_of_band_escalated_to_p1", "crawl:gold_vt_gm_out_of_band", "person:mei_chen", "ESCALATED_TO", 0.90,
                "Exception commentary is due within 1 day; overdue comments escalate to owner");
        crawlRelation(graph, "crawl:rel_stale_fx_escalated_to_p2", "crawl:gold_vt_stale_fx_row", "person:j_park", "ESCALATED_TO", 0.88,
                "Exception item missed deadline after 30 minutes; quarantine and rework before routing re-pull to owner");

        crawlRelation(graph, "crawl:rel_d0_s7_triggers_vt1", "crawl:d0_s7", "crawl:d0_vt1", "TRIGGERS", 0.92,
                "Variance triage can trigger miscoded SKU handling");
        crawlRelation(graph, "crawl:rel_d0_s7_triggers_vt2", "crawl:d0_s7", "crawl:d0_vt2", "TRIGGERS", 0.92,
                "Variance triage can trigger channel mismatch handling");
        crawlRelation(graph, "crawl:rel_d0_s7_triggers_vt3", "crawl:d0_s7", "crawl:d0_vt3", "TRIGGERS", 0.92,
                "Variance triage can trigger currency tag handling");
        crawlRelation(graph, "crawl:rel_d0_s7_triggers_vt4", "crawl:d0_s7", "crawl:d0_vt4", "TRIGGERS", 0.88,
                "Variance triage can trigger calendar shift handling");
        crawlRelation(graph, "crawl:rel_d0_s7_triggers_gm", "crawl:d0_s7", "crawl:gold_vt_gm_out_of_band", "TRIGGERS", 0.88,
                "Variance triage can trigger GM out-of-band handling");
        crawlRelation(graph, "crawl:rel_d0_s9_triggers_stale_fx", "crawl:d0_s9", "crawl:gold_vt_stale_fx_row", "TRIGGERS", 0.88,
                "FX translation can trigger stale FX row quarantine");
        crawlRelation(graph, "crawl:rel_d0_dq1_triggers_vt1", "crawl:d0_dq1", "crawl:d0_vt1", "TRIGGERS", 0.90,
                "SKU mapping miss flag triggers miscoded SKU triage");

        crawlRelation(graph, "crawl:rel_d0_s1_source_sp1", "crawl:d0_s1", "crawl:d0_sp1", "SOURCE_OF", 0.92,
                "Trial balance source is produced by step 1.1");
        crawlRelation(graph, "crawl:rel_d0_s1_source_sp2", "crawl:d0_s1", "crawl:d0_sp2", "SOURCE_OF", 0.90,
                "AR source is produced by step 1.1");
        crawlRelation(graph, "crawl:rel_d0_s1_source_sp3", "crawl:d0_s1", "crawl:d0_sp3", "SOURCE_OF", 0.90,
                "AP source is produced by step 1.1");
        crawlRelation(graph, "crawl:rel_d0_s2_source_sp4", "crawl:d0_s2", "crawl:d0_sp4", "SOURCE_OF", 0.90,
                "Shopify revenue source is produced by step 1.2");
        crawlRelation(graph, "crawl:rel_d0_s2_source_sp5", "crawl:d0_s2", "crawl:d0_sp5", "SOURCE_OF", 0.90,
                "Amazon revenue source is produced by step 1.2");
        crawlRelation(graph, "crawl:rel_d0_s3_source_sp6", "crawl:d0_s3", "crawl:d0_sp6", "SOURCE_OF", 0.90,
                "FX rate source is produced by step 1.3");
        crawlRelation(graph, "crawl:rel_d0_s5_source_sp7", "crawl:d0_s5", "crawl:d0_sp7", "SOURCE_OF", 0.90,
                "Workday roster source is produced by step 1.5");
        crawlRelation(graph, "crawl:rel_d0_s5_source_sp8", "crawl:d0_s5", "crawl:d0_sp8", "SOURCE_OF", 0.90,
                "Salesforce pipeline source is produced by step 1.5");
        crawlRelation(graph, "crawl:rel_d0_sp6_contains_fx1", "crawl:d0_sp6", "crawl:d0_fx1", "CONTAINS", 0.90,
                "FX provider source contains the Apr 2026 forward curve");
        crawlRelation(graph, "crawl:rel_d0_fa1_applies_adjustment_s7", "crawl:d0_fa1", "crawl:d0_s7", "APPLIES_ADJUSTMENT", 0.90,
                "Auto-fix batch applies correction evidence; remediation completes when the item is fixed");

        crawlRelation(graph, "crawl:rel_c01_validates_s9", "crawl:control_c01_fx_consistency", "crawl:d0_s9", "VALIDATES", 0.96,
                "Gold C-01 FX Consistency validates FX translation");
        crawlRelation(graph, "crawl:rel_c02_validates_s11", "crawl:control_c02_regional_total", "crawl:d0_s11", "VALIDATES", 0.96,
                "Gold C-02 Regional Total validates consolidation and elimination");
        crawlRelation(graph, "crawl:rel_c03_validates_s8", "crawl:control_c03_pipeline_coverage", "crawl:d0_s8", "VALIDATES", 0.94,
                "Gold C-03 Pipeline Coverage validates Salesforce reconciliation");
        crawlRelation(graph, "crawl:rel_c04_validates_s7", "crawl:control_c04_sku_mapping", "crawl:d0_s7", "VALIDATES", 0.94,
                "Gold C-04 SKU Mapping validates forecast workbook triage");
        crawlRelation(graph, "crawl:rel_c05_validates_s7", "crawl:control_c05_channel_gm_band", "crawl:d0_s7", "VALIDATES", 0.92,
                "Gold C-05 Channel GM Band validates variance triage");
        crawlRelation(graph, "crawl:rel_c06_validates_s10", "crawl:control_c06_sku_margin_discipline", "crawl:d0_s10", "VALIDATES", 0.92,
                "Gold C-06 SKU Margin Discipline validates projection");
        crawlRelation(graph, "crawl:rel_c07_validates_s13", "crawl:control_c07_statement_reconciliation", "crawl:d0_s13", "VALIDATES", 0.94,
                "Gold C-07 Statement Reconciliation validates controller and CFO sign-off");

        crawlRelation(graph, "crawl:rel_j_park_approves_s7", "person:j_park", "crawl:d0_s7", "APPROVED_BY", 0.90,
                "J. Park approved the Step 2.1 auto-correction batch");
        crawlRelation(graph, "crawl:rel_mei_approves_s10", "person:mei_chen", "crawl:d0_s10", "APPROVED_BY", 0.90,
                "M. Chen approved the Step 3.2 attrition assumption and projection inputs");
        crawlRelation(graph, "crawl:rel_l_okafor_approves_s13", "person:l_okafor", "crawl:d0_s13", "APPROVED_BY", 0.92,
                "L. Okafor approved close mechanics for Step 4.2");
        crawlRelation(graph, "crawl:rel_m_sato_approves_s13", "person:m_sato", "crawl:d0_s13", "APPROVED_BY", 0.92,
                "M. Sato approved projection reasonableness for Step 4.2");

        crawlRelation(graph, "crawl:rel_p1_same_as_mei", "crawl:d0_p1", "person:mei_chen", "SAME_AS", 0.94,
                "Crawler person node M. Chen resolves to canonical Mei Chen");
        crawlRelation(graph, "crawl:rel_p2_same_as_j_park", "crawl:d0_p2", "person:j_park", "SAME_AS", 0.94,
                "Crawler person node J. Park resolves to canonical J. Park");
        crawlRelation(graph, "crawl:rel_p3_same_as_s_reyes", "crawl:d0_p3", "person:s_reyes", "SAME_AS", 0.92,
                "Crawler person node S. Reyes resolves to canonical S. Reyes");
        crawlRelation(graph, "crawl:rel_p4_same_as_tanaka", "crawl:d0_p4", "person:ayako_tanaka", "SAME_AS", 0.92,
                "Crawler person node A. Tanaka resolves to canonical Ayako Tanaka");
    }

    private static void addRemainingCrawlerProcessGraph(UnifiedGraph graph) {
        String deliveryName = "KAA Methodology";
        String deliveryCase = "crawl:08-kaa-methodology";
        String deliverySource = "08_KAA_methodology.html";
        String[][] deliverySteps = {
                {"d1_l0", "L0 Engagement & Baseline",
                        "Score processes on automatability, value, and risk; sign scope and capture as-is metrics."},
                {"d1_l1", "L1 Discovery",
                        "Mine Word manuals, Excel macros, screen recordings, and interviews into an evidence graph."},
                {"d1_l2", "L2 Ontology",
                        "Build the shared vocabulary of entities, relationships, controls, and role mappings."},
                {"d1_l3", "L3 Process Model",
                        "Model versioned as-is and to-be states with named human approval gates."},
                {"d1_l4", "L4 Integrations",
                        "Connect source systems with scoped credentials and connector telemetry."},
                {"d1_l5", "L5 Agents",
                        "Deploy specialized agents with tools, evaluations, escalation policy, and SLA."}
        };
        for (int i = 0; i < deliverySteps.length; i++) {
            String[] step = deliverySteps[i];
            crawlProcessNode(graph, step[0], "CLOSE_STEP", step[1], 0.94,
                    deliveryName, deliveryCase, deliverySource, i + 1, step[2]);
        }
        String[][] deliveryEdges = {
                {"d1_l0", "d1_l1", "Engagement baseline scoping feeds into discovery."},
                {"d1_l1", "d1_l2", "Discovery evidence feeds ontology construction."},
                {"d1_l2", "d1_l3", "The shared vocabulary feeds the versioned process model."},
                {"d1_l3", "d1_l4", "The process model defines integration requirements."},
                {"d1_l4", "d1_l5", "Integrations enable agent execution."},
                {"d1_l5", "d1_l2", "Agent telemetry feeds back into ontology refinement."},
                {"d1_l5", "d1_l1", "Recurring exceptions trigger focused re-discovery."}
        };
        for (int i = 0; i < deliveryEdges.length; i++) {
            String[] edge = deliveryEdges[i];
            crawlProcessRelation(graph, "crawl:rel_delivery_" + (i + 1),
                    edge[0], edge[1], "FEEDS_INTO", 0.94,
                    deliveryName, deliveryCase, deliverySource, edge[2]);
        }

        String closeName = "Accounting Close Map";
        String closeCase = "crawl:08-accounting-close";
        String[][] closeSteps = {
                {"d1_cs1", "Procurement / PO Creation", "Capture purchase orders and vendor master data."},
                {"d1_cs2", "Goods Receipt (3PL + Warehouse)", "Record warehouse and 3PL goods receipts."},
                {"d1_cs3", "Sales Transactions (Shopify + Amazon + EDI)", "Capture channel sales transactions."},
                {"d1_cs4", "Payroll Cycle (Workday + Bank File)", "Run payroll and bank-file generation."},
                {"d1_cs5", "Three-Way Match (PO / GR / Invoice)", "Match purchase orders, receipts, and invoices."},
                {"d1_cs6", "Revenue Close (Cutoff + Deferred)", "Close revenue cutoff and deferred revenue."},
                {"d1_cs7", "AP Close (Accruals + Cutoff)", "Close payables, accruals, and cutoff."},
                {"d1_cs8", "Monthly Close + 3-Mo P&L Projection", "Consolidate close outputs and project P&L."},
                {"d1_cs9", "Inventory Close (Cycle Counts + WAC)", "Reconcile cycle counts and weighted average cost."},
                {"d1_cs10", "FX Revaluation (Treasury + OCI)", "Revalue treasury and OCI currency positions."},
                {"d1_cs11", "Variance Commentary (Auto-Draft + CFO Edit)", "Draft and review variance commentary."},
                {"d1_cs12", "Board Pack (Quarterly)", "Assemble the quarterly board pack."},
                {"d1_cs13", "Tax Provision (Quarterly True-Up)", "Calculate the quarterly tax true-up."},
                {"d1_cs14", "External Reporting (10-Q / IR)", "Prepare SEC and investor-relations reporting."}
        };
        for (int i = 0; i < closeSteps.length; i++) {
            String[] step = closeSteps[i];
            crawlProcessNode(graph, step[0], "CLOSE_STEP", step[1], 0.92,
                    closeName, closeCase, deliverySource, i + 1, step[2]);
        }
        String[][] closeEdges = {
                {"d1_cs1", "d1_cs5", "PO creation feeds the three-way match."},
                {"d1_cs2", "d1_cs5", "Goods receipt feeds the three-way match."},
                {"d1_cs3", "d1_cs6", "Sales transactions feed the revenue close."},
                {"d1_cs5", "d1_cs7", "Three-way match feeds the AP close."},
                {"d1_cs6", "d1_cs8", "Revenue close feeds the monthly close."},
                {"d1_cs7", "d1_cs8", "AP close feeds the monthly close."},
                {"d1_cs9", "d1_cs8", "Inventory close feeds the monthly close."},
                {"d1_cs10", "d1_cs8", "FX revaluation feeds the monthly close."},
                {"d1_cs4", "d1_cs8", "Payroll feeds compensation expense into the monthly close."},
                {"d1_cs8", "d1_cs11", "Monthly close feeds variance commentary."},
                {"d1_cs8", "d1_cs12", "Monthly close feeds the board pack."},
                {"d1_cs8", "d1_cs13", "Monthly close feeds the tax provision."},
                {"d1_cs11", "d1_cs14", "Variance commentary feeds external reporting."},
                {"d1_cs12", "d1_cs14", "The board pack feeds external reporting."}
        };
        for (int i = 0; i < closeEdges.length; i++) {
            String[] edge = closeEdges[i];
            crawlProcessRelation(graph, "crawl:rel_accounting_" + (i + 1),
                    edge[0], edge[1], "FEEDS_INTO", 0.92,
                    closeName, closeCase, deliverySource, edge[2]);
        }

        String[] deliveryRoles = {
                "Process Steward", "Ontology Custodian", "Customer Executive Sponsor",
                "Discovery Owner", "Customer IT Owner", "Reviewer Pool / Queue Owner"
        };
        for (int i = 0; i < deliveryRoles.length; i++) {
            crawlProcessNode(graph, "d1_ar" + (i + 1), "APPROVAL_ROLE", deliveryRoles[i], 0.90,
                    deliveryName, deliveryCase, deliverySource, i + 1,
                    "Named approval owner for a KAA methodology layer.");
        }
        String[] deliveryControls = {
                "SOX / J-SOX In-Scope Tagging", "Schema Versioning Governance",
                "Agent Liability Boundaries", "Recording Consent & PII Redaction",
                "Integration Access Control"
        };
        for (int i = 0; i < deliveryControls.length; i++) {
            crawlProcessNode(graph, "d1_ca" + (i + 1), "CONTROL_ASSERTION", deliveryControls[i], 0.90,
                    deliveryName, deliveryCase, deliverySource, i + 1,
                    "Control assertion attached to a KAA methodology layer.",
                    qualityEmbedding(0.18));
        }
        int[] approvedLayer = {3, 2, 0, 1, 4, 5};
        for (int i = 0; i < approvedLayer.length; i++) {
            crawlProcessRelation(graph, "crawl:rel_delivery_approval_" + (i + 1),
                    "d1_l" + approvedLayer[i], "d1_ar" + (i + 1), "APPROVED_BY", 0.90,
                    deliveryName, deliveryCase, deliverySource,
                    "Methodology layer has a named approval owner.");
        }
        int[] controlledLayer = {3, 2, 5, 1, 4};
        for (int i = 0; i < controlledLayer.length; i++) {
            crawlProcessRelation(graph, "crawl:rel_delivery_control_" + (i + 1),
                    "d1_ca" + (i + 1), "d1_l" + controlledLayer[i], "VALIDATES", 0.90,
                    deliveryName, deliveryCase, deliverySource,
                    "Methodology control validates its associated layer.");
        }

        String approvalName = "Monthly Projection Approvals";
        String approvalCase = "crawl:09-monthly-projection-approvals";
        String approvalSource = "09_process_map_KAA.html";
        String[][] approvalGates = {
                {"d2_ar1", "HITL: Approve Auto-Correction Batch", "J. Park approves the correction batch."},
                {"d2_ar2", "HITL: OpEx Attrition Assumption Review", "M. Chen reviews the attrition assumption."},
                {"d2_ar4", "HITL: Close Mechanics Sign-off", "The Controller signs off close mechanics."},
                {"d2_ar3", "HITL: Variance Commentary Edit", "M. Chen edits commentary before final review."},
                {"d2_ar5", "HITL: Projection Sign-off", "The CFO signs off the final projection."}
        };
        for (int i = 0; i < approvalGates.length; i++) {
            String[] gate = approvalGates[i];
            crawlProcessNode(graph, gate[0], "APPROVAL_ROLE", gate[1], 0.93,
                    approvalName, approvalCase, approvalSource, i + 1, gate[2]);
        }
        for (int i = 0; i < approvalGates.length - 1; i++) {
            crawlProcessRelation(graph, "crawl:rel_approval_precedes_" + (i + 1),
                    approvalGates[i][0], approvalGates[i + 1][0], "PRECEDES", 0.88,
                    approvalName, approvalCase, approvalSource,
                    "Narrative dependency orders one human approval gate before the next.");
        }
        crawlProcessRelation(graph, "crawl:rel_d2_ar1_owner", "d2_ar1", "person:j_park",
                "APPROVED_BY", 0.94, approvalName, approvalCase, approvalSource,
                "Auto-correction batch approval is assigned to J. Park.");
        crawlProcessRelation(graph, "crawl:rel_d2_ar2_owner", "d2_ar2", "person:mei_chen",
                "APPROVED_BY", 0.94, approvalName, approvalCase, approvalSource,
                "OpEx assumption review is assigned to M. Chen.");
        crawlProcessRelation(graph, "crawl:rel_d2_ar3_owner", "d2_ar3", "person:mei_chen",
                "APPROVED_BY", 0.94, approvalName, approvalCase, approvalSource,
                "Commentary edit is assigned to M. Chen.");
        crawlProcessRelation(graph, "crawl:rel_d2_ar4_owner", "d2_ar4", "person:l_okafor",
                "APPROVED_BY", 0.94, approvalName, approvalCase, approvalSource,
                "Close mechanics sign-off is assigned to L. Okafor.");
        crawlProcessRelation(graph, "crawl:rel_d2_ar5_owner", "d2_ar5", "person:m_sato",
                "APPROVED_BY", 0.94, approvalName, approvalCase, approvalSource,
                "Projection sign-off is assigned to M. Sato.");

        String qualityCase = "crawl:09-quality-and-feedback";
        crawlEmbeddedEntity(graph, "crawl:d2_dq1", "DATA_QUALITY_FLAG", "Triage Hit Rate Drift Monitor", 0.90,
                tags("crawler-expected", "monitor", "quality"), qualityEmbedding(0.12),
                "sourceDocument", approvalSource, "caseId", qualityCase,
                "description", "Triage accuracy improved from 79% to 92% over 12 cycles.");
        crawlEmbeddedEntity(graph, "crawl:d2_dq2", "DATA_QUALITY_FLAG", "Commentary Edit-Rate Drift Monitor", 0.90,
                tags("crawler-expected", "monitor", "quality"), qualityEmbedding(0.11),
                "sourceDocument", approvalSource, "caseId", qualityCase,
                "description", "Commentary evaluation set grew from 12 to 89 examples.");
        crawlEmbeddedEntity(graph, "crawl:d2_dq3", "DATA_QUALITY_FLAG", "ERP Migration Re-Discovery Trigger", 0.90,
                tags("crawler-expected", "monitor", "quality", "rediscovery"), qualityEmbedding(0.13),
                "sourceDocument", approvalSource, "caseId", qualityCase,
                "description", "Saved-search hash drift over 50% triggers focused process re-mining.");
        crawlEntity(graph, "crawl:d2_vt1", "VARIANCE_TRIAGE", "SKU Mapping Triage Pattern", 0.90,
                tags("crawler-expected", "triage"),
                "sourceDocument", approvalSource, "caseId", qualityCase);
        crawlEntity(graph, "crawl:d2_sp1", "SPREADSHEET", "FP&A Close SOP v3.2", 0.94,
                tags("crawler-expected", "source"),
                "sourceDocument", approvalSource, "caseId", qualityCase);
        crawlEmbeddedEntity(graph, "crawl:d2_ca3", "CONTROL_ASSERTION", "Audit Retention: 7-Year Object Lock", 0.92,
                tags("crawler-expected", "retention", "compliance"), qualityEmbedding(0.10),
                "sourceDocument", approvalSource, "caseId", qualityCase,
                "description", "Retain evidence graph artifacts and run logs in S3 with object lock.");
        crawlEntity(graph, "crawl:d2_ct1", "CHANNEL_TAXONOMY", "Channel Taxonomy Ontology", 0.92,
                tags("crawler-expected", "ontology"),
                "sourceDocument", approvalSource, "caseId", qualityCase);
        crawlEntity(graph, "crawl:d2_fa2", "FORECAST_ADJUSTMENT", "Recurring Channel Rule", 0.86,
                tags("crawler-expected", "feedback", "ontology-change"),
                "sourceDocument", approvalSource, "caseId", qualityCase,
                "description", "Recurring exception classes are codified into the ontology.");

        crawlRelation(graph, "crawl:rel_d2_dq1_validates", "crawl:d2_dq1", "crawl:d2_vt1",
                "VALIDATES", 0.90, "Triage drift monitoring validates pattern accuracy.",
                "sourceDocument", approvalSource, "caseId", qualityCase);
        crawlRelation(graph, "crawl:rel_d2_dq2_validates", "crawl:d2_dq2", "crawl:d2_ar3",
                "VALIDATES", 0.90, "Commentary edit-rate monitoring validates draft quality.",
                "sourceDocument", approvalSource, "caseId", qualityCase);
        crawlRelation(graph, "crawl:rel_d2_dq3_triggers", "crawl:d2_dq3", "crawl:d2_sp1",
                "TRIGGERS", 0.90, "ERP schema drift triggers focused process re-discovery.",
                "sourceDocument", approvalSource, "caseId", qualityCase);
        crawlRelation(graph, "crawl:rel_d2_fa2_adjusts", "crawl:d2_fa2", "crawl:d2_ct1",
                "APPLIES_ADJUSTMENT", 0.86, "Recurring channel exceptions patch the channel ontology.",
                "sourceDocument", approvalSource, "caseId", qualityCase);
        crawlRelation(graph, "crawl:rel_d2_ca3_contains", "crawl:d2_ca3", "crawl:d2_sp1",
                "CONTAINS", 0.92, "The seven-year archive retains process evidence and run logs.",
                "sourceDocument", approvalSource, "caseId", qualityCase);

        String ontologyCase = "crawl:ontology-evolution";
        crawlEntity(graph, "crawl:d3_vt1", "VARIANCE_TRIAGE", "Channel and SKU Triage Patterns", 0.90,
                tags("crawler-expected", "triage", "ontology"),
                "sourceDocument", "ontology.html", "caseId", ontologyCase);
        crawlEntity(graph, "crawl:d3_cr1", "CURRENCY_REGISTRY", "Currency Registry", 0.92,
                tags("crawler-expected", "ontology"),
                "sourceDocument", "ontology.html", "caseId", ontologyCase);
        crawlEntity(graph, "crawl:d3_sk1", "SKU_MASTER", "SKU Master", 0.92,
                tags("crawler-expected", "ontology"),
                "sourceDocument", "ontology.html", "caseId", ontologyCase);
        crawlEntity(graph, "crawl:d3_ct1", "CHANNEL_TAXONOMY", "Canonical Channel Taxonomy", 0.92,
                tags("crawler-expected", "ontology"),
                "sourceDocument", "ontology.html", "caseId", ontologyCase);

        String[][] qualityNodes = {
                {"d3_dq1", "DATA_QUALITY_FLAG", "Auto-Fix Budget <=4 per Workbook"},
                {"d3_dq2", "DATA_QUALITY_FLAG", "Currency Symbol Budget <=6 per Workbook"},
                {"d3_dq3", "DATA_QUALITY_FLAG", "Cross-Channel Price Spread >12% Flag"},
                {"d3_fa1", "FORECAST_ADJUSTMENT", "Auto-Promote Confirmed Channel Aliases"},
                {"d3_fa2", "FORECAST_ADJUSTMENT", "Codify Recurring Marketplace Exception"},
                {"d3_fa3", "FORECAST_ADJUSTMENT", "Tighten Lifecycle Status to Enum"},
                {"d3_fa4", "FORECAST_ADJUSTMENT", "Promote Parent SKU to Formal Field"}
        };
        for (String[] node : qualityNodes) {
            if ("DATA_QUALITY_FLAG".equals(node[1])) {
                crawlEmbeddedEntity(graph, "crawl:" + node[0], node[1], node[2], 0.88,
                        tags("crawler-expected", "quality", "feedback"), qualityEmbedding(0.08),
                        "sourceDocument", "ontology.html", "caseId", ontologyCase);
            } else {
                crawlEntity(graph, "crawl:" + node[0], node[1], node[2], 0.88,
                        tags("crawler-expected", "quality", "feedback"),
                        "sourceDocument", "ontology.html", "caseId", ontologyCase);
            }
        }
        crawlRelation(graph, "crawl:rel_d3_dq1_validates", "crawl:d3_dq1", "crawl:d3_vt1",
                "VALIDATES", 0.90, "Auto-fix budget validates triage correction volume.",
                "sourceDocument", "ontology.html", "caseId", ontologyCase);
        crawlRelation(graph, "crawl:rel_d3_dq2_validates", "crawl:d3_dq2", "crawl:d3_cr1",
                "VALIDATES", 0.90, "Currency budget validates normalization volume.",
                "sourceDocument", "ontology.html", "caseId", ontologyCase);
        crawlRelation(graph, "crawl:rel_d3_dq3_validates", "crawl:d3_dq3", "crawl:d3_sk1",
                "VALIDATES", 0.90, "Price-spread monitoring validates SKU price discipline.",
                "sourceDocument", "ontology.html", "caseId", ontologyCase);
        crawlRelation(graph, "crawl:rel_d3_fa1_adjusts", "crawl:d3_fa1", "crawl:d3_ct1",
                "APPLIES_ADJUSTMENT", 0.88, "Confirmed aliases are promoted into channel taxonomy.",
                "sourceDocument", "ontology.html", "caseId", ontologyCase);
        crawlRelation(graph, "crawl:rel_d3_fa2_adjusts", "crawl:d3_fa2", "crawl:d3_ct1",
                "APPLIES_ADJUSTMENT", 0.88, "Recurring marketplace exceptions patch channel taxonomy.",
                "sourceDocument", "ontology.html", "caseId", ontologyCase);
        crawlRelation(graph, "crawl:rel_d3_fa3_adjusts", "crawl:d3_fa3", "crawl:d3_sk1",
                "APPLIES_ADJUSTMENT", 0.88, "Repeated inconsistency tightens lifecycle status to an enum.",
                "sourceDocument", "ontology.html", "caseId", ontologyCase);
        crawlRelation(graph, "crawl:rel_d3_fa4_adjusts", "crawl:d3_fa4", "crawl:d3_sk1",
                "APPLIES_ADJUSTMENT", 0.88, "Repeated variant grouping promotes parent SKU to a formal field.",
                "sourceDocument", "ontology.html", "caseId", ontologyCase);
    }

    private static void crawlProcessNode(UnifiedGraph graph,
                                         String crawlId,
                                         String type,
                                         String label,
                                         double confidence,
                                         String processName,
                                         String caseId,
                                         String sourceDocument,
                                         int order,
                                         String description) {
        crawlProcessNode(graph, crawlId, type, label, confidence, processName, caseId,
                sourceDocument, order, description, null);
    }

    private static void crawlProcessNode(UnifiedGraph graph,
                                         String crawlId,
                                         String type,
                                         String label,
                                         double confidence,
                                         String processName,
                                         String caseId,
                                         String sourceDocument,
                                         int order,
                                         String description,
                                         double[] embedding) {
        Collection<String> nodeTags =
                tags("crawler-expected", "process-evidence", relationTag(type));
        Object[] attributes = {
                "sourceDocument", sourceDocument,
                "caseId", caseId,
                "processName", processName,
                "stepOrder", order,
                "description", description
        };
        if (embedding == null) {
            crawlEntity(graph, "crawl:" + crawlId, type, label, confidence, nodeTags, attributes);
        } else {
            crawlEmbeddedEntity(
                    graph, "crawl:" + crawlId, type, label, confidence, nodeTags, embedding, attributes);
        }
    }

    private static void crawlProcessRelation(UnifiedGraph graph,
                                             String id,
                                             String sourceId,
                                             String targetId,
                                             String type,
                                             double confidence,
                                             String processName,
                                             String caseId,
                                             String sourceDocument,
                                             String description) {
        crawlRelation(graph, id, crawlProcessNodeId(sourceId), crawlProcessNodeId(targetId),
                type, confidence, description,
                "sourceDocument", sourceDocument,
                "caseId", caseId,
                "processName", processName);
    }

    private static String crawlProcessNodeId(String id) {
        return id.contains(":") ? id : "crawl:" + id;
    }

    private static void crawlStep(UnifiedGraph graph, String crawlId, String stepId, int order, String label,
                                  String phase, String automationStatus, String... extraTags) {
        List<String> stepTags = tags("crawler-expected", "close-step", "monthly-close", phaseTag(phase));
        stepTags.addAll(List.of(extraTags));
        Map<String, Object> stepAttrs = crawlAttrs(
                "crawlId", crawlId,
                "stepId", stepId,
                "goldStepId", stepId,
                "stepOrder", order,
                "processName", "Monthly FP&A Close",
                "phase", phase,
                "automationStatus", automationStatus);
        if ("d0_s10".equals(crawlId)) {
            stepAttrs.put("goldStepAliases", List.of("3.2"));
            stepAttrs.put("fixtureNote", "Crawl output collapses expected graph steps 3.1 and 3.2 into one projection node.");
        }
        entity(graph, "crawl:" + crawlId, "CLOSE_STEP", label, 0.94, stepTags, stepAttrs);
    }

    private static void crawlGoldControl(UnifiedGraph graph, String id, String controlId, String name,
                                         String expectedStepId, String owner, String gate, String... extraTags) {
        List<String> controlTags = tags("crawler-expected", "gold-control", "control");
        controlTags.addAll(List.of(extraTags));
        crawlEntity(graph, id, "CONTROL_ASSERTION", controlId + " " + name, 0.95, controlTags,
                "sourceDocument", "fpna-expected-graph.html",
                "controlId", controlId,
                "goldControlId", controlId,
                "expectedStepId", expectedStepId,
                "owner", owner,
                "gate", gate,
                "controlName", name);
    }

    private static void crawlEntity(UnifiedGraph graph, String id, String type, String label, double confidence,
                                    Collection<String> entityTags, Object... extraAttrs) {
        entity(graph, id, type, label, confidence, entityTags, crawlAttrs(extraAttrs));
    }

    private static void crawlEmbeddedEntity(
            UnifiedGraph graph,
            String id,
            String type,
            String label,
            double confidence,
            Collection<String> entityTags,
            double[] embedding,
            Object... extraAttrs) {
        Map<String, Object> attributes = crawlAttrs(extraAttrs);
        GraphEntity entity = GraphEntity.builder(id)
                .type(type)
                .label(label)
                .weight(confidence)
                .confidence(confidence)
                .tags(entityTags)
                .embedding(embedding)
                .timestamp(timestampFrom(attributes))
                .attributes(attributes)
                .build();
        graph.addEntity(entity);
        graph.putEntityOpinion(id, Opinion.fromSoftTruth(confidence, 1));
    }

    private static double[] qualityEmbedding(double secondaryAxis) {
        return new double[] {1.0, secondaryAxis, 0.04};
    }

    private static void crawlRelation(UnifiedGraph graph, String id, String sourceId, String targetId,
                                      String type, double confidence, String description, Object... extraAttrs) {
        List<String> relationTags = tags("crawler-expected", relationTag(type));
        if ("FEEDS_INTO".equals(type) || "VALIDATES".equals(type) || "TRIGGERS".equals(type)) {
            relationTags.add("process");
        }
        Map<String, Object> relationAttrs = crawlAttrs(extraAttrs);
        augmentGenericCrawlHints(relationAttrs, type, sourceId, targetId, description);
        relation(graph, id, sourceId, targetId, type, confidence, description, relationTags, relationAttrs);
    }

    private static Map<String, Object> crawlAttrs(Object... extraPairs) {
        Map<String, Object> out = attrs("source", SRC_CRAWL_RESULTS,
                "sourceDocument", "02_process_map.html",
                "crawlExpected", true,
                "caseId", "monthly-close:crawl-expected");
        out.putAll(attrs(extraPairs));
        return out;
    }

    private static void augmentGenericCrawlHints(Map<String, Object> attrs, String relationType,
                                                 String sourceId, String targetId, String description) {
        String text = normalizeHintText(String.join(" ", nullToEmpty(relationType), nullToEmpty(description)));
        if (text.isBlank()) {
            return;
        }
        if (containsAny(text, "CONTROL", "VALIDAT", "VERIFY", "CHECK", "RECONCIL")) {
            attrs.putIfAbsent("actionType", "VALIDATE");
            attrs.putIfAbsent("actionCategory", "VALIDATION");
            if (containsAny(text, "CONTROL")) {
                attrs.putIfAbsent("controlId", firstNonBlank(sourceId, targetId));
            }
        }
        if (containsAny(text, "ESCALAT")) {
            attrs.putIfAbsent("actionType", "ESCALATE");
            attrs.putIfAbsent("actionCategory", "ESCALATION");
            attrs.putIfAbsent("escalationTarget", firstNonBlank(targetId, sourceId));
        }
        if (containsAny(text, "APPROV", "SIGN OFF", "SIGNOFF")) {
            attrs.putIfAbsent("actionType", "APPROVE");
            attrs.putIfAbsent("actionCategory", "APPROVAL");
            attrs.putIfAbsent("approvalPolicy", firstNonBlank(relationType, "approval"));
            attrs.putIfAbsent("approver", firstNonBlank(targetId, sourceId));
        }
        if (containsAny(text, "ROUT", "ASSIGN", "DISPATCH")) {
            attrs.putIfAbsent("actionType", "ROUTE");
            attrs.putIfAbsent("actionCategory", "ROUTING");
            attrs.putIfAbsent("routingPolicy", firstNonBlank(relationType, "routing"));
        }
        if (containsAnyWord(text, "SLA", "DEADLINE", "DUE", "WITHIN") || containsAny(text, "SERVICE LEVEL")) {
            attrs.putIfAbsent("sla", firstNonBlank(relationType, "deadline"));
            Long seconds = inferDurationSeconds(description);
            if (seconds != null) {
                attrs.putIfAbsent("slaSeconds", seconds);
            }
        }
        if (containsAny(text, "BREACH", "OVERDUE", "MISSED DEADLINE", "PAST DUE")
                || containsAnyWord(text, "LATE", "DELAYED")) {
            attrs.putIfAbsent("slaBreached", true);
            attrs.putIfAbsent("status", "OVERDUE");
        } else if (containsAny(text, "BLOCK", "ON HOLD", "WAIT", "FAIL", "REJECT")) {
            attrs.putIfAbsent("status", "BLOCKED");
        } else if (containsAnyWord(text, "READY", "PASSED", "SUCCESS", "COMPLETE", "COMPLETED", "DONE")
                || containsAny(text, "APPROVED")) {
            attrs.putIfAbsent("status", "READY");
        }
        String remediation = remediationAction(text);
        if (remediation != null) {
            attrs.putIfAbsent("remediationAction", remediation);
            attrs.putIfAbsent("actionCategory", "REMEDIATION");
        }
    }

    private static String normalizeHintText(String raw) {
        return raw == null ? "" : raw.toUpperCase().replaceAll("[^A-Z0-9]+", " ").trim().replaceAll(" +", " ");
    }

    private static boolean containsAny(String text, String... fragments) {
        for (String fragment : fragments) {
            if (fragment != null && !fragment.isBlank() && text.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAnyWord(String text, String... words) {
        String padded = " " + text + " ";
        for (String word : words) {
            if (word != null && !word.isBlank() && padded.contains(" " + word + " ")) {
                return true;
            }
        }
        return false;
    }

    private static String remediationAction(String text) {
        if (containsAny(text, "QUARANTINE")) {
            return "QUARANTINE";
        }
        if (containsAny(text, "ROLLBACK", "ROLL BACK")) {
            return "ROLLBACK";
        }
        if (containsAny(text, "REJECT")) {
            return "REJECT";
        }
        if (containsAny(text, "REWORK", "RESUBMIT")) {
            return "REWORK";
        }
        if (containsAny(text, "REMEDIAT", "CORRECT", "FIX")) {
            return "REMEDIATE";
        }
        return null;
    }

    private static Long inferDurationSeconds(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Matcher matcher = DURATION_PATTERN.matcher(raw);
        if (!matcher.find()) {
            return null;
        }
        double amount;
        try {
            amount = Double.parseDouble(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
        return Math.max(1L, Math.round(amount * durationMultiplier(matcher.group(2))));
    }

    private static long durationMultiplier(String unit) {
        String normalized = unit == null ? "" : unit.toLowerCase();
        if (normalized.startsWith("sec")) {
            return 1L;
        }
        if (normalized.startsWith("min")) {
            return 60L;
        }
        if (normalized.startsWith("hour") || normalized.startsWith("hr")) {
            return 60L * 60L;
        }
        if (normalized.startsWith("day")) {
            return 24L * 60L * 60L;
        }
        return 1L;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String phaseTag(String phase) {
        return phase.toLowerCase().replace(" and ", "-").replace(' ', '-');
    }

    private static String relationTag(String type) {
        return type.toLowerCase().replace('_', '-');
    }

    private static void addObservedRelations(UnifiedGraph graph) {
        relation(graph, "rel:amer_email_sent_by_sarah", "email:amer_q3_forecast", "person:sarah_chen", "SENT_BY", 0.95,
                "Sarah sent the AMER forecast email", tags("observed", "email"),
                attrs("source", SRC_CRAWL_BUILDERS));
        relation(graph, "rel:amer_email_to_mei", "email:amer_q3_forecast", "person:mei_chen", "SENT_TO", 0.95,
                "AMER email sent to canonical Mei Chen", tags("observed", "email"),
                attrs("source", SRC_CRAWL_BUILDERS));
        relation(graph, "rel:amer_email_to_j_park", "email:amer_q3_forecast", "person:j_park", "SENT_TO", 0.90,
                "AMER email sent to J. Park", tags("observed", "email"),
                attrs("source", SRC_CRAWL_BUILDERS));
        relation(graph, "rel:amer_email_has_amer_workbook", "email:amer_q3_forecast", "spreadsheet:amer_forecast_q3_final_v2", "HAS_ATTACHMENT", 0.95,
                "AMER forecast workbook attached", tags("observed", "attachment"),
                attrs("source", SRC_CRAWL_BUILDERS));
        relation(graph, "rel:sarah_submitted_amer_forecast", "person:sarah_chen", "forecast:amer_q3_2026", "SUBMITTED_BY", 0.90,
                "Sarah submitted the AMER forecast", tags("observed", "submission"),
                attrs("source", SRC_CRAWL_BUILDERS));
        relation(graph, "rel:amer_forecast_contains_hyd_110", "forecast:amer_q3_2026", "sku:hyd_110", "CONTAINS", 0.85,
                "AMER forecast includes HYD-110 launch", tags("observed", "forecast"),
                attrs("source", SRC_CRAWL_BUILDERS));
        relation(graph, "rel:amer_dq_triggers_workbook", "dq:amer_stale_subtotals", "spreadsheet:amer_forecast_q3_final_v2", "TRIGGERS", 0.80,
                "Stale subtotal warning applies to AMER workbook", tags("observed", "data-quality"),
                attrs("source", SRC_CRAWL_BUILDERS));

        relation(graph, "rel:emea_email_sent_by_vasseur", "email:emea_q3_forecast", "person:francois_vasseur", "SENT_BY", 0.95,
                "Vasseur sent the EMEA forecast email", tags("observed", "email"), attrs("source", SRC_CRAWL_BUILDERS));
        relation(graph, "rel:emea_email_to_mei", "email:emea_q3_forecast", "person:mei_chen", "SENT_TO", 0.95,
                "EMEA email sent to canonical Mei Chen", tags("observed", "email"), attrs("source", SRC_CRAWL_BUILDERS));
        relation(graph, "rel:emea_email_has_emea_workbook", "email:emea_q3_forecast", "spreadsheet:emea_forecast_jun_aug_2026", "HAS_ATTACHMENT", 0.95,
                "EMEA forecast workbook attached", tags("observed", "attachment"), attrs("source", SRC_CRAWL_BUILDERS));
        relation(graph, "rel:vasseur_submitted_emea_forecast", "person:francois_vasseur", "forecast:emea_q3_2026", "SUBMITTED_BY", 0.90,
                "Vasseur submitted the EMEA forecast", tags("observed", "submission"), attrs("source", SRC_CRAWL_BUILDERS));

        relation(graph, "rel:apac_email_sent_by_tanaka", "email:apac_forecast", "person:ayako_tanaka", "SENT_BY", 0.88,
                "Tanaka sent the APAC forecast email", tags("expected", "email"), attrs("source", SRC_EXPECTED_GRAPH));
        relation(graph, "rel:apac_email_to_mei", "email:apac_forecast", "person:mei_chen", "SENT_TO", 0.88,
                "APAC email sent to canonical Mei Chen", tags("expected", "email"), attrs("source", SRC_EXPECTED_GRAPH));
        relation(graph, "rel:apac_email_has_apac_workbook", "email:apac_forecast", "spreadsheet:apac_fcst_fy27q1", "HAS_ATTACHMENT", 0.88,
                "APAC forecast workbook attached", tags("expected", "attachment"), attrs("source", SRC_EXPECTED_GRAPH));
        relation(graph, "rel:tanaka_submitted_apac_forecast", "person:ayako_tanaka", "forecast:apac_fy27q1", "SUBMITTED_BY", 0.86,
                "Tanaka submitted the APAC forecast", tags("expected", "submission"), attrs("source", SRC_EXPECTED_GRAPH));

        relation(graph, "rel:amer_workbook_contains_wholesale", "spreadsheet:amer_forecast_q3_final_v2", "table:amer_wholesale", "CONTAINS", 0.90,
                "AMER workbook contains Wholesale tab", tags("observed", "containment"), attrs("source", SRC_CRAWL_BUILDERS));
        relation(graph, "rel:amer_workbook_contains_dtc", "spreadsheet:amer_forecast_q3_final_v2", "table:amer_dtc", "CONTAINS", 0.90,
                "AMER workbook contains DTC tab", tags("observed", "containment"), attrs("source", SRC_CRAWL_BUILDERS));
        relation(graph, "rel:emea_workbook_contains_uk", "spreadsheet:emea_forecast_jun_aug_2026", "table:emea_uk", "CONTAINS", 0.88,
                "EMEA workbook contains UK tab", tags("expected", "containment"), attrs("source", SRC_EXPECTED_GRAPH));
        relation(graph, "rel:emea_workbook_contains_de", "spreadsheet:emea_forecast_jun_aug_2026", "table:emea_de", "CONTAINS", 0.88,
                "EMEA workbook contains DE tab", tags("expected", "containment"), attrs("source", SRC_EXPECTED_GRAPH));
        relation(graph, "rel:apac_workbook_contains_jp", "spreadsheet:apac_fcst_fy27q1", "table:apac_jp", "CONTAINS", 0.86,
                "APAC workbook contains JP tab", tags("expected", "containment"), attrs("source", SRC_EXPECTED_GRAPH));
        relation(graph, "rel:apac_workbook_contains_au", "spreadsheet:apac_fcst_fy27q1", "table:apac_au", "CONTAINS", 0.86,
                "APAC workbook contains AU tab", tags("expected", "containment"), attrs("source", SRC_EXPECTED_GRAPH));
        relation(graph, "rel:apac_workbook_contains_sg", "spreadsheet:apac_fcst_fy27q1", "table:apac_sg", "CONTAINS", 0.86,
                "APAC workbook contains SG tab", tags("expected", "containment"), attrs("source", SRC_EXPECTED_GRAPH));

        relation(graph, "rel:amer_forecast_refs_dtc", "forecast:amer_q3_2026", "channel:dtc", "REFERENCES_TAXONOMY", 0.85,
                "AMER forecast references DTC/ecom", tags("observed", "taxonomy"), attrs("source", SRC_EXPECTED_GRAPH));
        relation(graph, "rel:amer_forecast_refs_wholesale", "forecast:amer_q3_2026", "channel:wholesale", "REFERENCES_TAXONOMY", 0.85,
                "AMER forecast references Wholesale", tags("observed", "taxonomy"), attrs("source", SRC_EXPECTED_GRAPH));
        relation(graph, "rel:amer_forecast_refs_amazon", "forecast:amer_q3_2026", "channel:amazon", "REFERENCES_TAXONOMY", 0.82,
                "AMER forecast references Amazon", tags("expected", "taxonomy"), attrs("source", SRC_EXPECTED_GRAPH));
        relation(graph, "rel:emea_forecast_refs_dtc", "forecast:emea_q3_2026", "channel:dtc", "REFERENCES_TAXONOMY", 0.85,
                "EMEA forecast references DTC", tags("observed", "taxonomy"), attrs("source", SRC_CRAWL_BUILDERS));
        relation(graph, "rel:emea_forecast_refs_marketplace", "forecast:emea_q3_2026", "channel:marketplace", "REFERENCES_TAXONOMY", 0.85,
                "EMEA forecast references Marketplace", tags("observed", "taxonomy"), attrs("source", SRC_CRAWL_BUILDERS));
        relation(graph, "rel:apac_forecast_refs_dtc", "forecast:apac_fy27q1", "channel:dtc", "REFERENCES_TAXONOMY", 0.82,
                "APAC forecast references EC/DTC", tags("expected", "taxonomy"), attrs("source", SRC_EXPECTED_GRAPH));
        relation(graph, "rel:apac_forecast_refs_wholesale", "forecast:apac_fy27q1", "channel:wholesale", "REFERENCES_TAXONOMY", 0.82,
                "APAC forecast references B2B/Wholesale", tags("expected", "taxonomy"), attrs("source", SRC_EXPECTED_GRAPH));

        relation(graph, "rel:control_c01_validates_tb_tie", "control:c01_tb_tie", "close:tb_tie_control", "VALIDATES", 0.95,
                "C-01 validates the trial balance tie step", tags("observed", "control"), attrs("source", SRC_CRAWL_BUILDERS));
        relation(graph, "rel:j_park_approves_version_gate", "person:j_park", "close:version_assertion_gate", "APPROVED_BY", 0.90,
                "J. Park approves the version assertion gate", tags("observed", "approval"), attrs("source", SRC_CRAWL_BUILDERS));
        relation(graph, "rel:s_reyes_approves_version_gate", "person:s_reyes", "close:version_assertion_gate", "APPROVED_BY", 0.90,
                "S. Reyes approves the version assertion gate", tags("observed", "approval"), attrs("source", SRC_CRAWL_BUILDERS));
        relation(graph, "rel:intake_feeds_version_gate", "close:regional_workbook_intake", "close:version_assertion_gate", "FEEDS_INTO", 0.85,
                "Workbook intake feeds the version assertion gate", tags("observed", "process"), attrs("source", SRC_CRAWL_BUILDERS));
        relation(graph, "rel:version_gate_feeds_triage", "close:version_assertion_gate", "close:validate_triage_variances", "FEEDS_INTO", 0.84,
                "Version gate feeds validation and variance triage", tags("expected", "process"), attrs("source", SRC_EXPECTED_GRAPH));
        relation(graph, "rel:triage_feeds_fx", "close:validate_triage_variances", "close:fx_translate_forecast", "FEEDS_INTO", 0.82,
                "Variance triage feeds FX translation", tags("expected", "process"), attrs("source", SRC_EXPECTED_GRAPH));
        relation(graph, "rel:fx_feeds_tb_tie", "close:fx_translate_forecast", "close:tb_tie_control", "FEEDS_INTO", 0.82,
                "FX translation feeds TB tie control", tags("expected", "process"), attrs("source", SRC_EXPECTED_GRAPH));
    }

    private static void addDerivedRelations(UnifiedGraph graph) {
        derivedPersonEmail(graph, "amer", "person:sarah_chen", "email:amer_q3_forecast", "spreadsheet:amer_forecast_q3_final_v2", "forecast:amer_q3_2026",
                "rel:amer_email_sent_by_sarah", "rel:amer_email_has_amer_workbook", "rel:sarah_submitted_amer_forecast");
        derivedPersonEmail(graph, "emea", "person:francois_vasseur", "email:emea_q3_forecast", "spreadsheet:emea_forecast_jun_aug_2026", "forecast:emea_q3_2026",
                "rel:emea_email_sent_by_vasseur", "rel:emea_email_has_emea_workbook", "rel:vasseur_submitted_emea_forecast");
        derivedPersonEmail(graph, "apac", "person:ayako_tanaka", "email:apac_forecast", "spreadsheet:apac_fcst_fy27q1", "forecast:apac_fy27q1",
                "rel:apac_email_sent_by_tanaka", "rel:apac_email_has_apac_workbook", "rel:tanaka_submitted_apac_forecast");

        relation(graph, "derived:mei_shared_consolidation_recipient", "person:mei_chen", "email:amer_q3_forecast", "SHARED_CONSOLIDATION_RECIPIENT_OF", 0.88,
                "Mei is the canonical recipient for all regional forecast emails", tags("derived", "recipient"),
                attrs("source", "manual-rule",
                        "derived", true,
                        "rule", "same PERSON target of >=3 SENT_TO regional forecast emails",
                        "supportingRelations", List.of("rel:amer_email_to_mei", "rel:emea_email_to_mei", "rel:apac_email_to_mei")),
                "rel:amer_email_to_mei", "rel:emea_email_to_mei", "rel:apac_email_to_mei");
        relation(graph, "derived:mei_shared_consolidation_recipient_emea", "person:mei_chen", "email:emea_q3_forecast", "SHARED_CONSOLIDATION_RECIPIENT_OF", 0.88,
                "Mei is the canonical recipient for all regional forecast emails", tags("derived", "recipient"),
                attrs("source", "manual-rule", "derived", true, "rule", "same PERSON target of >=3 SENT_TO regional forecast emails"),
                "rel:amer_email_to_mei", "rel:emea_email_to_mei", "rel:apac_email_to_mei");
        relation(graph, "derived:mei_shared_consolidation_recipient_apac", "person:mei_chen", "email:apac_forecast", "SHARED_CONSOLIDATION_RECIPIENT_OF", 0.84,
                "Mei is the canonical recipient for all regional forecast emails", tags("derived", "recipient"),
                attrs("source", "manual-rule", "derived", true, "rule", "same PERSON target of >=3 SENT_TO regional forecast emails"),
                "rel:amer_email_to_mei", "rel:emea_email_to_mei", "rel:apac_email_to_mei");

        relation(graph, "derived:regional_submission_batch", "close:regional_workbook_intake", "person:mei_chen", "ROLLS_UP_TO", 0.82,
                "Regional workbook intake rolls up to Mei Chen's consolidation queue", tags("derived", "process"),
                attrs("source", "manual-rule",
                        "derived", true,
                        "rule", "all regional forecast emails SENT_TO canonical consolidation lead"),
                "rel:amer_email_to_mei", "rel:emea_email_to_mei", "rel:apac_email_to_mei");
    }

    private static void derivedPersonEmail(UnifiedGraph graph, String region, String personId, String emailId,
                                           String spreadsheetId, String forecastId, String sentByRel,
                                           String attachmentRel, String submittedRel) {
        relation(graph, "derived:" + region + "_person_sent_email", personId, emailId, "PERSON_SENT_EMAIL", 0.92,
                "Sender inferred from EMAIL_MESSAGE SENT_BY relation", tags("derived", "email"),
                attrs("source", "manual-rule", "derived", true, "rule", "SENT_BY(email, person) -> PERSON_SENT_EMAIL(person, email)"),
                sentByRel);
        relation(graph, "derived:" + region + "_person_sent_attachment", personId, spreadsheetId, "SENT_EMAIL_WITH_ATTACHMENT", 0.90,
                "Sender sent an email carrying this workbook attachment", tags("derived", "email", "attachment"),
                attrs("source", "manual-rule",
                        "derived", true,
                        "viaEmail", emailId,
                        "rule", "SENT_BY(email, person) and HAS_ATTACHMENT(email, file) -> SENT_EMAIL_WITH_ATTACHMENT(person, file)"),
                sentByRel, attachmentRel);
        relation(graph, "derived:" + region + "_email_submits_forecast", emailId, forecastId, "SUBMITS_FORECAST", 0.86,
                "Email submits the regional forecast through its sender", tags("derived", "forecast"),
                attrs("source", "manual-rule",
                        "derived", true,
                        "rule", "SENT_BY(email, person) and SUBMITTED_BY(person, forecast) -> SUBMITS_FORECAST(email, forecast)"),
                sentByRel, submittedRel);
    }

    private static List<ReasoningTrace> buildReasoningTraces() {
        ReasoningTrace.Step amerSentBy = fact("SENT_BY(email:amer_q3_forecast, person:sarah_chen)", 0.95, SRC_CRAWL_BUILDERS);
        ReasoningTrace.Step amerAttachment = fact("HAS_ATTACHMENT(email:amer_q3_forecast, spreadsheet:amer_forecast_q3_final_v2)", 0.95, SRC_CRAWL_BUILDERS);
        ReasoningTrace.Step amerAttachmentRule = ReasoningTrace.Step.derived(
                ReasoningTrace.StepKind.RULE,
                "SENT_EMAIL_WITH_ATTACHMENT(person:sarah_chen, spreadsheet:amer_forecast_q3_final_v2)",
                "SENT_BY(email, person) and HAS_ATTACHMENT(email, file)",
                0.90,
                Opinion.fromSoftTruth(0.90, 2),
                Map.of("ruleId", "person_sent_email_with_attachment"),
                List.of(amerSentBy, amerAttachment));

        ReasoningTrace.Step amerToMei = fact("SENT_TO(email:amer_q3_forecast, person:mei_chen)", 0.95, SRC_CRAWL_BUILDERS);
        ReasoningTrace.Step emeaToMei = fact("SENT_TO(email:emea_q3_forecast, person:mei_chen)", 0.95, SRC_CRAWL_BUILDERS);
        ReasoningTrace.Step apacToMei = fact("SENT_TO(email:apac_forecast, person:mei_chen)", 0.88, SRC_EXPECTED_GRAPH);
        ReasoningTrace.Step sharedRecipientRule = ReasoningTrace.Step.derived(
                ReasoningTrace.StepKind.FUSION,
                "SHARED_CONSOLIDATION_RECIPIENT(person:mei_chen)",
                "same canonical PERSON is target of regional SENT_TO relations",
                0.88,
                Opinion.fromSoftTruth(0.88, 3),
                Map.of("canonicalEntity", "person:mei_chen"),
                List.of(amerToMei, emeaToMei, apacToMei));

        ReasoningTrace.Step c01 = fact("CONTROL_ASSERTION(control:c01_tb_tie)", 0.95, SRC_CRAWL_BUILDERS);
        ReasoningTrace.Step validates = fact("VALIDATES(control:c01_tb_tie, close:tb_tie_control)", 0.95, SRC_CRAWL_BUILDERS);
        ReasoningTrace.Step tbTieGate = ReasoningTrace.Step.derived(
                ReasoningTrace.StepKind.RULE,
                "CONTROLLED_CLOSE_STEP(close:tb_tie_control)",
                "CONTROL_ASSERTION(control) and VALIDATES(control, closeStep)",
                0.92,
                Opinion.fromSoftTruth(0.92, 2),
                Map.of("severity", "CRITICAL"),
                List.of(c01, validates));

        return List.of(
                ReasoningTrace.of(amerAttachmentRule),
                ReasoningTrace.of(sharedRecipientRule),
                ReasoningTrace.of(tbTieGate));
    }

    private static ReasoningTrace.Step fact(String conclusion, double confidence, String source) {
        return ReasoningTrace.Step.fact(conclusion, confidence, source, Opinion.fromSoftTruth(confidence, 1));
    }

    private static void entity(UnifiedGraph graph, String id, String type, String label, double confidence,
                               Collection<String> tags, Map<String, Object> attributes) {
        GraphEntity entity = GraphEntity.builder(id)
                .type(type)
                .label(label)
                .weight(confidence)
                .confidence(confidence)
                .tags(tags)
                .timestamp(timestampFrom(attributes))
                .attributes(attributes)
                .build();
        graph.addEntity(entity);
        graph.putEntityOpinion(id, Opinion.fromSoftTruth(confidence, 1));
    }

    private static void relation(UnifiedGraph graph, String id, String sourceId, String targetId, String type,
                                 double confidence, String description, Collection<String> tags,
                                 Map<String, Object> attributes, String... supportRelationIds) {
        Map<String, Object> relAttrs = new LinkedHashMap<>(attributes);
        relAttrs.put("description", description);
        if (supportRelationIds != null && supportRelationIds.length > 0) {
            relAttrs.putIfAbsent("supportingRelations", List.of(supportRelationIds));
        }
        Instant timestamp = timestampFrom(relAttrs);
        if (timestamp == null) {
            timestamp = graph.entity(sourceId).map(GraphEntity::timestamp).orElse(null);
        }
        if (timestamp == null) {
            timestamp = graph.entity(targetId).map(GraphEntity::timestamp).orElse(null);
        }
        GraphRelation relation = GraphRelation.builder(id, sourceId, targetId)
                .type(type)
                .weight(confidence)
                .confidence(confidence)
                .directed(true)
                .tags(tags)
                .timestamp(timestamp)
                .attributes(relAttrs)
                .build();
        graph.addRelation(relation);
        graph.putRelationOpinion(id, Opinion.fromSoftTruth(confidence,
                supportRelationIds == null ? 1 : Math.max(1, supportRelationIds.length)));
    }

    private static Instant timestampFrom(Map<String, Object> attributes) {
        for (String key : List.of("timestamp", "eventTime", "occurredAt", "sentAt", "date")) {
            Object raw = attributes.get(key);
            if (raw == null) {
                continue;
            }
            if (raw instanceof Instant instant) {
                return instant;
            }
            String value = String.valueOf(raw).trim();
            try {
                return Instant.parse(value);
            } catch (java.time.format.DateTimeParseException ignored) {
                try {
                    return java.time.OffsetDateTime.parse(value).toInstant();
                } catch (java.time.format.DateTimeParseException ignoredOffset) {
                    try {
                        return java.time.LocalDate.parse(value)
                                .atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
                    } catch (java.time.format.DateTimeParseException ignoredDate) {
                        // Continue through the standard crawl timestamp keys.
                    }
                }
            }
        }
        return null;
    }

    private static Map<String, Double> ruleWeights() {
        Map<String, Double> weights = new LinkedHashMap<>();
        weights.put("person_sent_email", 0.92);
        weights.put("person_sent_email_with_attachment", 0.90);
        weights.put("email_submits_forecast", 0.86);
        weights.put("shared_consolidation_recipient", 0.88);
        weights.put("regional_submission_batch_rollup", 0.82);
        return weights;
    }

    private static List<String> tags(String... tags) {
        return Arrays.stream(tags).collect(Collectors.toCollection(ArrayList::new));
    }

    private static Map<String, Object> attrs(Object... pairs) {
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("Attribute pairs must be key/value pairs");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return out;
    }

    private static String renderTraceJsonl(List<ReasoningTrace> traces) {
        return traces.stream().map(ReasoningTrace::toJson).collect(Collectors.joining("\n", "", "\n"));
    }

    private static String sourceNotes() {
        return "# FPNA pristine graph source notes\n\n"
                + "This .kgraph was manually built from local FPNA fixtures. Facts tagged `crawler-expected` "
                + "represent what the audited FPNA crawl fixture says the crawl should generate; they are not live crawler output.\n\n"
                + "Primary fixtures:\n"
                + "- " + SRC_EXPECTED_GRAPH + "\n"
                + "- " + SRC_CRAWL_RESULTS + "\n"
                + "- " + SRC_EXTRACTION_TEXT + "\n"
                + "- " + SRC_CRAWL_BUILDERS + "\n"
                + "- " + SRC_V8_CHECKPOINTS + "\n\n"
                + "Observed and crawler-expected edge families include SENT_BY, SENT_TO, HAS_ATTACHMENT, CONTAINS, "
                + "SUBMITTED_BY, REFERENCES_TAXONOMY, VALIDATES, APPROVED_BY, FEEDS_INTO, TRIGGERS, "
                + "PART_OF, SOURCE_OF, ESCALATED_TO, and APPLIES_ADJUSTMENT.\n"
                + "Derived edge families include PERSON_SENT_EMAIL, SENT_EMAIL_WITH_ATTACHMENT, "
                + "SUBMITS_FORECAST, SHARED_CONSOLIDATION_RECIPIENT_OF, and ROLLS_UP_TO.\n";
    }

    private static void printSummary(UnifiedGraph graph, Path output, List<ReasoningTrace> traces) throws IOException {
        System.out.println("Wrote " + output.toAbsolutePath());
        System.out.println("Entities: " + graph.entityCount());
        System.out.println("Relations: " + graph.relationCount());
        System.out.println("Artifacts: " + graph.artifacts().keySet());
        System.out.println("File bytes: " + Files.size(output));
        System.out.println();

        System.out.println("Entity types:");
        groupedCounts(graph.entities().stream().map(GraphEntity::type).toList())
                .forEach((type, count) -> System.out.println("  " + type + ": " + count));
        System.out.println();

        System.out.println("Relation types:");
        groupedCounts(graph.relations().stream().map(GraphRelation::type).toList())
                .forEach((type, count) -> System.out.println("  " + type + ": " + count));
        System.out.println();

        System.out.println("Derived relationships:");
        graph.relations().stream()
                .filter(r -> Boolean.TRUE.equals(r.attributes().get("derived")) || r.hasTag("derived"))
                .sorted(Comparator.comparing(GraphRelation::id))
                .forEach(r -> System.out.println("  " + r.id() + " " + r.sourceId() + " -[" + r.type() + "]-> " + r.targetId()));
        System.out.println();

        System.out.println("Reasoning traces:");
        for (ReasoningTrace trace : traces) {
            System.out.println("  " + trace.conclusion().conclusion()
                    + " confidence=" + trace.conclusion().confidence()
                    + " depth=" + trace.depth()
                    + " steps=" + trace.size());
        }
    }

    private static Map<String, Long> groupedCounts(List<String> values) {
        return values.stream()
                .collect(Collectors.groupingBy(v -> v, TreeMap::new, Collectors.counting()));
    }
}
