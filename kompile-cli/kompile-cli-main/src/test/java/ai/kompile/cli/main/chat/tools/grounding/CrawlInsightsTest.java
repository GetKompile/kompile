/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.cli.insights.Format;
import ai.kompile.cli.insights.InsightReport;
import ai.kompile.cli.insights.InsightsConfig;
import ai.kompile.cli.insights.InsightsQuery;
import ai.kompile.cli.insights.Panel;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The {@code insights} crawl report reads the project's job store and knowledge bases as it
 * finds them, and a crawl manager's job list when the session has one; its row in the chat's
 * session panel shows the crawls in progress.
 */
@TemporaryUserHome
class CrawlInsightsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");
    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final InsightsConfig CONFIG = InsightsConfig.defaults();
    private static final String SERVER_URL = "http://crawl.test";

    private static final String RUNNING = "local-11111111-1111-4111-8111-111111111111";
    private static final String ORPHANED = "local-22222222-2222-4222-8222-222222222222";
    private static final String FAILED = "local-33333333-3333-4333-8333-333333333333";
    private static final String OLD = "local-44444444-4444-4444-8444-444444444444";
    private static final String QUEUED = "local-55555555-5555-4555-8555-555555555555";
    private static final Instant OLD_FINISHED = NOW.minus(Duration.ofDays(6)).plus(Duration.ofMinutes(5));

    @TempDir
    Path project;

    @Test
    void overviewCountsTheWindowsJobsAndLeavesTheStoreAsItWas() throws IOException {
        writeProject();
        Map<String, String> before = tree(project);

        InsightReport report = local().report(query("how are the crawls going"));

        assertEquals(before, tree(project), "the report must not prune, reconcile or rewrite a job");
        assertEquals("Crawls, last 7 days: 4 jobs (1 in progress, 2 failed); 1 knowledge base, 3 documents",
                report.getHeadline());
        String text = report.getText();
        assertTrue(text.contains("running 45%"), text);
        assertTrue(text.contains("\nFailed jobs, newest first:\n"), text);
        assertTrue(text.contains("The process that ran this crawl exited before the crawl finished."), text);
        assertTrue(text.contains("loader crashed: disk full"), text);
        assertTrue(text.contains("\nProject knowledge bases, latest crawl first:\n"), text);
        assertTrue(text.contains("Project-local crawl jobs are kept for 24 hours after they finish; an older crawl "
                + "shows only as its knowledge base's last crawl."), text);
        assertTrue(text.indexOf("running 45%") < text.indexOf("interrupted"), "newest job first: " + text);
        assertEquals("bar", report.getChart().path("kind").asText());
        assertEquals("Documents per knowledge base", report.getChart().path("title").asText());
        assertEquals("docs", report.getChart().path("labels").path(0).asText());
        assertEquals(3, report.getChart().path("series").path(0).path("values").path(0).asLong());
    }

    @Test
    void aJobNamedByItsIdIsShownInDetail() throws IOException {
        writeProject();

        InsightReport orphaned = local().report(query("what happened to " + ORPHANED));

        assertEquals("Crawl job " + ORPHANED + " (docs), " + Format.dateTime(NOW.minus(Duration.ofHours(2)), UTC)
                + ": interrupted", orphaned.getHeadline());
        String text = orphaned.getText();
        assertTrue(text.contains("where     this project\n"), text);
        assertTrue(text.contains("finished  " + Format.dateTime(NOW.minus(Duration.ofMinutes(110)), UTC) + "\n"), text);
        assertTrue(text.contains("took      10m00s\n"), text);
        assertTrue(text.contains("error     The process that ran this crawl exited before the crawl finished.\n"),
                text);
        assertNull(orphaned.getChart());

        // A job named by its id is shown whenever it ran, even outside the window the question asks about.
        InsightReport old = local().report(query("crawl job " + OLD + " in the last 24 hours"));
        assertEquals("Crawl job " + OLD + " (docs), " + Format.dateTime(NOW.minus(Duration.ofDays(6)), UTC)
                + ": completed", old.getHeadline());
        assertTrue(old.getText().contains("documents 3\n"), old.getText());
        assertTrue(old.getText().contains("took      5m00s\n"), old.getText());
    }

    @Test
    void aKnowledgeBaseNamedInQuotesShowsItsCrawls() throws IOException {
        writeProject();

        InsightReport report = local().report(query("crawls of \"docs\""));

        assertEquals("Knowledge base docs: completed, 3 documents, 10 chunks, 4 entities, 5 relations; last crawl "
                + Format.dateTime(OLD_FINISHED, UTC) + "; 3 crawl jobs, last 7 days", report.getHeadline());
        String text = report.getText();
        assertTrue(text.contains("sources   1\n"), text);
        assertTrue(text.contains("\nCrawl jobs, newest first:\n"), text);
        assertFalse(text.contains("loader crashed"), "the failed job belongs to no knowledge base: " + text);
        assertEquals("Documents per crawl of docs, last 7 days", report.getChart().path("title").asText());
    }

    @Test
    void aQuestionAboutThisSessionCoversEveryCrawl() throws IOException {
        writeProject();

        InsightReport report = local().report(
                InsightsQuery.parse(null, "crawls in this session", List.of("session-1"), NOW, UTC, 7));

        assertTrue(report.getHeadline().startsWith("Crawls, all time: 4 jobs"), report.getHeadline());
        assertTrue(report.getText().contains(
                "Crawls belong to the project, not to a chat session, so this covers all of them."), report.getText());
    }

    @Test
    void crawlManagerJobsAreListedOnce() {
        RestTemplate rt = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(rt);
        server.expect(requestTo(SERVER_URL + CrawlInsights.JOBS_PATH))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [{"jobId":"job-a","status":"RUNNING","progressPercent":30.4,"factSheetId":7,
                          "createdAt":"2026-10-03T11:00:00Z","startedAt":"2026-10-03T11:01:00Z",
                          "documentsLoaded":2,"currentPhase":"EXTRACTION"},
                         {"jobId":"job-b","status":"FAILED","name":"finance","createdAt":"2026-10-03T08:00:00Z",
                          "completedAt":"2026-10-03T08:30:00Z","errorMessage":"boom","errorCount":1},
                         {"jobId":"job-a","status":"RUNNING","progressPercent":99}]
                        """, MediaType.APPLICATION_JSON));

        InsightReport report = withServer(rt).report(query("crawl status"));

        server.verify();
        assertEquals("Crawls, last 7 days: 2 jobs (1 in progress, 1 failed); no project knowledge bases",
                report.getHeadline());
        String text = report.getText();
        assertTrue(text.contains("running 30%"), text);
        assertFalse(text.contains("99%"), "a repeated job id is the same job: " + text);
        assertTrue(text.contains("fact sheet 7"), text);
        assertTrue(text.contains("finance"), text);
        assertTrue(text.contains("boom"), text);
        assertEquals("Documents per crawl job, last 7 days", report.getChart().path("title").asText());
    }

    @Test
    void aCrawlManagerJobNamedByItsIdSaysWhereItRan() {
        RestTemplate rt = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(rt);
        server.expect(requestTo(SERVER_URL + CrawlInsights.JOBS_PATH))
                .andRespond(withSuccess("""
                        [{"jobId":"job-a","status":"RUNNING","progressPercent":30,"factSheetId":7,
                          "createdAt":"2026-10-03T11:00:00Z","startedAt":"2026-10-03T11:01:00Z","queuePosition":0,
                          "currentPhase":"EXTRACTION","currentFile":"report.pdf"}]
                        """, MediaType.APPLICATION_JSON));

        InsightReport report = withServer(rt).report(query("how is job-a doing"));

        assertEquals("Crawl job job-a (fact sheet 7), " + Format.dateTime(Instant.parse("2026-10-03T11:00:00Z"), UTC)
                + ": running 30%", report.getHeadline());
        assertTrue(report.getText().contains("where     crawl manager " + SERVER_URL + "\n"), report.getText());
        assertTrue(report.getText().contains("stage     EXTRACTION - report.pdf\n"), report.getText());
        assertTrue(report.getText().contains("took      59m00s so far\n"), report.getText());
    }

    @Test
    void aCrawlManagerErrorIsANote() {
        RestTemplate rt = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(rt);
        server.expect(requestTo(SERVER_URL + CrawlInsights.JOBS_PATH))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .body("{\"error\":\"down\"}")
                        .contentType(MediaType.APPLICATION_JSON));
        server.expect(requestTo(SERVER_URL + CrawlInsights.JOBS_PATH))
                .andRespond(withException(new IOException("connection refused")));
        server.expect(requestTo(SERVER_URL + CrawlInsights.JOBS_PATH))
                .andRespond(withSuccess("{\"jobs\":[]}", MediaType.APPLICATION_JSON));
        CrawlInsights insights = withServer(rt);

        InsightReport failing = insights.report(query("crawls"));
        InsightReport unreachable = insights.report(query("crawls"));
        InsightReport unreadable = insights.report(query("crawls"));

        server.verify();
        assertEquals("Crawls, last 7 days: no crawl jobs; no project knowledge bases", failing.getHeadline());
        assertTrue(failing.getText().contains("The crawl manager at " + SERVER_URL
                + " answered HTTP 503 (down), so its crawls are missing."), failing.getText());
        assertTrue(unreachable.getText().contains("The crawl manager at " + SERVER_URL + " could not be reached ("),
                unreachable.getText());
        assertTrue(unreachable.getText().contains("connection refused"), unreachable.getText());
        assertTrue(unreadable.getText().contains("The crawl manager at " + SERVER_URL
                + " sent a job list that could not be read, so its crawls are missing."), unreadable.getText());
    }

    @Test
    void panelShowsTheActiveCrawlsWithTheNewestOnesStageBeneath() throws IOException {
        writeProject();
        save(stored(RUNNING).put("stageDetail", "chunk 40 of 90"));
        ObjectNode queued = job(QUEUED, "QUEUED", false, "QUEUED", NOW.minus(Duration.ofMinutes(30)));
        queued.put("ownerPid", ProcessHandle.current().pid());
        queued.put("knowledgeBaseId", "api");
        save(queued);

        Panel.Line line = local().panelLine(panelQuery(NOW));

        // The interrupted and finished jobs are not active; the newest active one comes first.
        assertEquals("Crawl: 2 active: docs 45% (extraction), api (queued)", line.text());
        assertEquals("↳ docs: chunk 40 of 90 · 9m00s so far", line.detail());
        assertTrue(line.live());
    }

    @Test
    void panelShowsTheLastCrawlWhenNoneIsActive() throws IOException {
        writeProject();
        save(stored(RUNNING).put("status", "COMPLETED").put("terminal", true).put("stage", "COMPLETED")
                .put("finishedAt", NOW.minus(Duration.ofMinutes(3)).toString()));

        Panel.Line line = local().panelLine(panelQuery(NOW));

        assertEquals("Crawl: none active · last: docs completed, 3m ago", line.text());
        assertNull(line.detail());
        assertFalse(line.live());
    }

    @Test
    void panelShowsAFailedLastCrawlInCapitalsWithItsError() throws IOException {
        writeProject();
        Files.delete(stateFile(RUNNING));
        Files.delete(stateFile(RUNNING).getParent());

        Panel.Line line = local().panelLine(panelQuery(NOW));

        // The newest job left is the one whose process exited; its last update was 110 minutes ago.
        assertEquals("Crawl: none active · last: docs INTERRUPTED, 1h ago", line.text());
        assertEquals("↳ The process that ran this crawl exited before the crawl finished.", line.detail());
        assertFalse(line.live());
    }

    @Test
    void panelReadsAStoredJobAgainOnlyWhenItsFileChanges() throws IOException {
        writeProject();
        CrawlInsights insights = local();
        assertEquals("Crawl: 1 active: docs 45% (extraction)", insights.panelLine(panelQuery(NOW)).text());

        Path file = stateFile(RUNNING);
        FileTime written = Files.getLastModifiedTime(file);
        long size = Files.size(file);
        save(stored(RUNNING).put("progressPercent", 46));
        assertEquals(size, Files.size(file), "the same size, so only the write time tells of the change");
        Files.setLastModifiedTime(file, written);
        assertEquals("Crawl: 1 active: docs 45% (extraction)", insights.panelLine(panelQuery(NOW)).text(),
                "a state file of unchanged size and write time is not read again");

        Files.setLastModifiedTime(file, FileTime.from(written.toInstant().plusSeconds(1)));
        assertEquals("Crawl: 1 active: docs 46% (extraction)", insights.panelLine(panelQuery(NOW)).text());

        Files.delete(file);
        Files.delete(file.getParent());
        assertEquals("Crawl: none active · last: docs INTERRUPTED, 1h ago", insights.panelLine(panelQuery(NOW)).text(),
                "a removed job leaves the panel");
    }

    @Test
    void panelWatchesTheJobStoreAndEachUnfinishedJob() throws IOException {
        Path root = project.toRealPath();
        CrawlInsights insights = local();
        assertEquals(Panel.Line.of("Crawl: no recent crawl jobs"), insights.panelLine(panelQuery(NOW)));
        assertEquals(List.of(), insights.panelWatches(panelQuery(NOW)),
                "a project without state is seen again on the panel's timer");

        Files.createDirectories(root.resolve(".kompile/state"));
        insights.panelLine(panelQuery(NOW));
        assertEquals(List.of(new Panel.Watch(root.resolve(".kompile/state"), "crawl-jobs")),
                insights.panelWatches(panelQuery(NOW)), "the store's parent, for the store's first job");

        writeProject();
        insights.panelLine(panelQuery(NOW));
        Path jobs = root.resolve(CrawlInsights.JOBS_DIRECTORY);
        assertEquals(List.of(new Panel.Watch(jobs, null),
                        new Panel.Watch(jobs.resolve(RUNNING), CrawlInsights.STATE_FILE),
                        new Panel.Watch(jobs.resolve(ORPHANED), CrawlInsights.STATE_FILE)),
                insights.panelWatches(panelQuery(NOW)), "the store, and the state of each job not finished");
    }

    @Test
    void panelAsksTheCrawlManagerAtMostEveryFewSecondsAndWaitsLongerAfterAFailure() throws IOException {
        RestTemplate rt = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(rt);
        server.expect(requestTo(SERVER_URL + CrawlInsights.JOBS_PATH))
                .andRespond(withSuccess("""
                        [{"jobId":"job-a","status":"RUNNING","progressPercent":30,"name":"finance",
                          "createdAt":"2026-10-03T11:00:00Z","startedAt":"2026-10-03T11:01:00Z",
                          "currentPhase":"EXTRACTION","currentFile":"report.pdf"}]
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(SERVER_URL + CrawlInsights.JOBS_PATH))
                .andRespond(withException(new IOException("connection refused")));
        server.expect(requestTo(SERVER_URL + CrawlInsights.JOBS_PATH))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        CrawlInsights insights = withServer(rt);

        Panel.Line first = insights.panelLine(panelQuery(NOW));
        assertEquals("Crawl: 1 active: finance 30% (extraction)", first.text());
        assertEquals("↳ finance: report.pdf · 59m00s so far", first.detail());
        assertTrue(first.live());

        assertEquals("↳ finance: report.pdf · 59m04s so far", insights.panelLine(panelQuery(NOW.plusSeconds(4))).detail(),
                "the list read four seconds before, timed to now");

        Instant failedAt = NOW.plus(CrawlInsights.PANEL_REUSE);
        Panel.Line failed = insights.panelLine(panelQuery(failedAt));
        assertEquals(new Panel.Line("Crawl: no recent crawl jobs · crawl manager unreachable",
                "↳ crawl manager at " + SERVER_URL + " could not be reached (connection refused)", false), failed);
        assertEquals(failed, insights.panelLine(panelQuery(failedAt.plus(CrawlInsights.PANEL_BACKOFF).minusSeconds(1))),
                "no new request until the wait after a failure is over");

        assertEquals(Panel.Line.of("Crawl: no recent crawl jobs"),
                insights.panelLine(panelQuery(failedAt.plus(CrawlInsights.PANEL_BACKOFF))));
        server.verify();
    }

    @Test
    void panelNamesACrawlManagerProblemBesideTheProjectsCrawls() throws IOException {
        writeProject();
        RestTemplate rt = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(rt);
        server.expect(requestTo(SERVER_URL + CrawlInsights.JOBS_PATH))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .body("{\"error\":\"down\"}")
                        .contentType(MediaType.APPLICATION_JSON));
        CrawlInsights insights =
                new CrawlInsights(project, SERVER_URL, new GroundingBackendClient(SERVER_URL, rt), CONFIG);

        Panel.Line running = insights.panelLine(panelQuery(NOW));
        assertEquals("Crawl: 1 active: docs 45% (extraction) · crawl manager HTTP 503", running.text());
        assertEquals("↳ docs: 9m00s so far", running.detail(), "a running crawl's progress comes first");
        assertTrue(running.live());

        save(stored(RUNNING).put("status", "COMPLETED").put("terminal", true).put("stage", "COMPLETED")
                .put("finishedAt", NOW.minus(Duration.ofMinutes(3)).toString()));
        Panel.Line idle = insights.panelLine(panelQuery(NOW.plusSeconds(1)));
        assertEquals(new Panel.Line("Crawl: none active · last: docs completed, 3m ago · crawl manager HTTP 503",
                "↳ crawl manager at " + SERVER_URL + " answered HTTP 503 (down)", false), idle);
        server.verify();
    }

    @Test
    void timestampsInEveryFormTheStoresWrite() {
        JsonNodeFactory json = JsonNodeFactory.instance;
        assertEquals(NOW, CrawlInsights.instant(json.textNode("2026-10-03T12:00:00Z"), UTC));
        assertEquals(NOW, CrawlInsights.instant(json.textNode("2026-10-03T14:00:00+02:00"), UTC));
        assertEquals(NOW, CrawlInsights.instant(json.textNode("2026-10-03T12:00:00"), UTC));
        assertEquals(NOW, CrawlInsights.instant(json.textNode("2026-10-03T14:00:00"), ZoneOffset.ofHours(2)));
        assertEquals(NOW, CrawlInsights.instant(json.numberNode(NOW.getEpochSecond()), UTC));
        assertEquals(NOW, CrawlInsights.instant(json.numberNode(NOW.toEpochMilli()), UTC));
        assertEquals(NOW, CrawlInsights.instant(json.textNode(Long.toString(NOW.toEpochMilli())), UTC));
        assertEquals(NOW.plusMillis(500),
                CrawlInsights.instant(json.numberNode(new BigDecimal(NOW.getEpochSecond() + ".5")), UTC));
        assertNull(CrawlInsights.instant(null, UTC));
        assertNull(CrawlInsights.instant(json.nullNode(), UTC));
        assertNull(CrawlInsights.instant(json.textNode("  "), UTC));
        assertNull(CrawlInsights.instant(json.textNode("yesterday"), UTC));
        assertNull(CrawlInsights.instant(json.objectNode(), UTC));
    }

    private CrawlInsights local() {
        return new CrawlInsights(project, null, CONFIG);
    }

    private static CrawlInsights withServer(RestTemplate rt) {
        return new CrawlInsights(null, SERVER_URL, new GroundingBackendClient(SERVER_URL, rt), CONFIG);
    }

    private static InsightsQuery query(String question) {
        return InsightsQuery.parse(null, question, null, NOW, UTC, 7);
    }

    private static InsightsQuery panelQuery(Instant now) {
        return InsightsQuery.session("session-1", now, UTC);
    }

    private Path stateFile(String id) {
        return project.resolve(CrawlInsights.JOBS_DIRECTORY).resolve(id).resolve(CrawlInsights.STATE_FILE);
    }

    private ObjectNode stored(String id) throws IOException {
        return (ObjectNode) MAPPER.readTree(stateFile(id).toFile());
    }

    /**
     * Four jobs: one running in this process, one whose process exited mid-crawl, one a lookup
     * already marked failed, and one finished six days ago, past the store's retention; and the
     * knowledge base the last of them wrote.
     */
    private void writeProject() throws IOException {
        ObjectNode running = job(RUNNING, "RUNNING", false, "EXTRACTION", NOW.minus(Duration.ofMinutes(10)));
        running.put("ownerPid", ProcessHandle.current().pid());
        running.put("knowledgeBaseId", "docs");
        running.put("progressPercent", 45);
        running.put("startedAt", NOW.minus(Duration.ofMinutes(9)).toString());
        save(running);

        ObjectNode orphaned = job(ORPHANED, "RUNNING", false, "LOADING", NOW.minus(Duration.ofHours(2)));
        orphaned.put("ownerPid", -1);
        orphaned.put("knowledgeBaseId", "docs");
        orphaned.put("progressPercent", 20);
        orphaned.put("stageUpdatedAt", NOW.minus(Duration.ofMinutes(110)).toString());
        save(orphaned);

        ObjectNode failed = job(FAILED, "FAILED", true, "INTERRUPTED", NOW.minus(Duration.ofHours(4)));
        failed.put("finishedAt", NOW.minus(Duration.ofHours(3)).toString());
        failed.putObject("result").put("error", true).put("output", "loader crashed: disk full");
        save(failed);

        ObjectNode old = job(OLD, "COMPLETED", true, "COMPLETED", NOW.minus(Duration.ofDays(6)));
        old.put("knowledgeBaseId", "docs");
        old.put("finishedAt", OLD_FINISHED.toString());
        old.putObject("result").putObject("metadata").put("documentCount", 3).put("chunkCount", 10)
                .put("graphEntityCount", 4).put("graphRelationCount", 5);
        save(old);

        ObjectNode summary = MAPPER.createObjectNode().put("name", "docs").put("status", "COMPLETED")
                .put("finishedAt", OLD_FINISHED.toString()).put("documentCount", 3).put("chunkCount", 10)
                .put("graphEntityCount", 4).put("graphRelationCount", 5);
        summary.putArray("sources").add("notes/");
        Path knowledgeBase = Files.createDirectories(project.resolve("data/crawls/docs"));
        MAPPER.writeValue(knowledgeBase.resolve("crawl-result.json").toFile(), summary);
    }

    private static ObjectNode job(String id, String status, boolean terminal, String stage, Instant created) {
        return MAPPER.createObjectNode().put("schema", LocalCrawlJobStore.SCHEMA).put("jobId", id)
                .put("status", status).put("terminal", terminal).put("stage", stage)
                .put("createdAt", created.toString());
    }

    private void save(ObjectNode state) throws IOException {
        Path directory = Files.createDirectories(
                project.resolve(".kompile/state/crawl-jobs").resolve(state.get("jobId").asText()));
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("state.json").toFile(), state);
    }

    /** Every file and directory under {@code root} with its size and modification time. */
    static Map<String, String> tree(Path root) throws IOException {
        Map<String, String> files = new TreeMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.toList()) {
                BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
                files.put(root.relativize(path).toString(), attributes.isDirectory() ? "directory"
                        : attributes.size() + " bytes, modified " + attributes.lastModifiedTime().toMillis());
            }
        }
        return files;
    }
}
