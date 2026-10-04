/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.insights.Charts;
import ai.kompile.cli.insights.Format;
import ai.kompile.cli.insights.InsightReport;
import ai.kompile.cli.insights.InsightSource;
import ai.kompile.cli.insights.InsightsConfig;
import ai.kompile.cli.insights.InsightsQuery;
import ai.kompile.cli.insights.InsightsWindow;
import ai.kompile.cli.insights.Panel;
import ai.kompile.cli.insights.Sparkline;
import ai.kompile.cli.insights.TextTable;
import ai.kompile.cli.insights.TimeBuckets;
import ai.kompile.cli.main.chat.tools.grounding.GroundingBackendClient.GroundingResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.ToLongFunction;

/**
 * Crawls for the {@code insights} tool: the project's crawl jobs, the crawl manager's when the
 * session has one, and the project's knowledge bases as their last crawl left them.
 *
 * <p>Project-local jobs come from the store that {@code crawl_source} keeps under
 * {@code .kompile/state/crawl-jobs}, read as found: {@code crawl_control list} deletes expired
 * records and looking up one job marks it failed once the process that ran it has exited, but
 * this report changes neither and shows such a job as interrupted. Crawl-manager jobs, live and
 * from its history, come from {@code GET /api/unified-crawl/jobs}.</p>
 *
 * <p>Its row in the chat's session panel shows the active crawls and their progress, or the
 * newest job's outcome when none is active.</p>
 */
public final class CrawlInsights implements InsightSource {

    public static final String TOPIC = "crawl";

    static final String LOCAL = "local";
    static final String SERVER = "server";
    static final String JOBS_PATH = "/api/unified-crawl/jobs";
    /** Where {@link LocalCrawlJobStore} keeps a project's jobs: one directory per job, holding its state file. */
    static final String JOBS_DIRECTORY = ".kompile/state/crawl-jobs";
    static final String STATE_FILE = "state.json";
    /** How long the report waits for the crawl manager's job list. */
    static final Duration REPORT_READ_TIMEOUT = Duration.ofSeconds(10);
    /** The panel's wait for the crawl manager, short so that a slow server never holds up the panel. */
    static final Duration PANEL_READ_TIMEOUT = Duration.ofSeconds(2);
    /** How long the panel reuses the crawl manager's job list. */
    static final Duration PANEL_REUSE = Duration.ofSeconds(5);
    /** How long the panel waits after a failed read before asking the crawl manager again. */
    static final Duration PANEL_BACKOFF = Duration.ofSeconds(30);
    /** The stage the job store records for a job whose process exited before it finished. */
    private static final String INTERRUPTED = "INTERRUPTED";

    /** Crawl-manager statuses of a job that has not finished. */
    private static final Set<String> SERVER_ACTIVE = Set.of("PENDING", "RUNNING", "ACTIVATING", "PAUSED", "CANCELLING");
    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();
    private static final int MAX_NAME = 40;
    private static final int MAX_ERROR = 100;
    private static final int MAX_NOTE = 200;
    private static final int MAX_DETAIL = 600;
    private static final int PANEL_NAME = 32;
    private static final int PANEL_DETAIL = 160;
    /** Epoch numbers below this are seconds, as Jackson writes an Instant; larger ones are milliseconds. */
    private static final BigDecimal SECONDS_LIMIT = BigDecimal.valueOf(100_000_000_000L);
    private static final Comparator<Job> NEWEST_FIRST =
            Comparator.comparing(Job::when, Comparator.nullsFirst(Comparator.<Instant>naturalOrder())).reversed()
                    .thenComparing(Job::id);

    private final Path workDir;
    private final String serverUrl;
    private final GroundingBackendClient server;
    private final InsightsConfig config;

    // The panel's state, kept between refreshes; guarded by this.
    private LocalProjectCrawlBackend panelProjects;
    private Path panelRoot;
    private Map<String, StoredJob> panelStored = Map.of();
    private JsonNode panelServerJobs;
    private Instant panelServerReadAt;
    private String panelServerProblem;
    private String panelServerReason;

    /**
     * @param workDir      a directory of the project whose crawls to read
     * @param crawlBaseUrl the crawl manager, or null when the session crawls project-locally
     */
    public CrawlInsights(Path workDir, String crawlBaseUrl, InsightsConfig config) {
        this(workDir, crawlBaseUrl,
                crawlBaseUrl == null || crawlBaseUrl.isBlank() ? null : new GroundingBackendClient(crawlBaseUrl),
                config);
    }

    CrawlInsights(Path workDir, String serverUrl, GroundingBackendClient server, InsightsConfig config) {
        this.workDir = workDir;
        this.serverUrl = serverUrl;
        this.server = server;
        this.config = config;
    }

    @Override
    public String topic() {
        return TOPIC;
    }

    @Override
    public String description() {
        return "crawls: crawl jobs with their progress, counts and errors, and the project's knowledge bases";
    }

    @Override
    public List<String> keywords() {
        return List.of("crawl", "ingest", "knowledge", "kb", "document", "chunk", "embed", "job");
    }

    /** One crawl job; a count is -1 when it was not recorded. */
    record Job(String id, String where, String knowledgeBase, String status, boolean active, boolean interrupted,
               int progress, String stage, String detail, int queuePosition, Instant created, Instant started,
               Instant finished, long tookMs, long documents, long chunks, long entities, long relations,
               long errors, String error) {

        boolean failed() {
            return interrupted || status.contains("FAIL");
        }

        /** When the job was submitted, or when it started if that is unknown. */
        Instant when() {
            return created != null ? created : started;
        }

        /** "running 45%", "completed with errors", "interrupted". */
        String state() {
            if (interrupted) {
                return "interrupted";
            }
            String words = Format.words(status);
            return active && progress > 0 ? words + " " + progress + "%" : words;
        }
    }

    /** A project knowledge base as its last crawl left it; a count is -1 when it was not recorded. */
    record KnowledgeBase(String id, String name, String status, Instant lastCrawl, long sources, long documents,
                         long chunks, long entities, long relations, long codeEntities, long vectors,
                         String embedding, String factSheetId, String graphPath, String path) {

        boolean owns(Job job) {
            return id.equals(job.knowledgeBase()) || name.equals(job.knowledgeBase());
        }
    }

    private static final class Scan {
        final List<Job> jobs = new ArrayList<>();
        final List<KnowledgeBase> knowledgeBases = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
    }

    /**
     * One read of the crawl manager's job list: the list, or else why there is none, as the end of
     * a sentence ("could not be reached (...)") and in brief ("unreachable").
     */
    private record ServerRead(JsonNode jobs, String problem, String brief) {
    }

    /** A stored job as the panel last read it, reused while its file's size and write time are unchanged. */
    private record StoredJob(long size, FileTime modified, ObjectNode state) {

        boolean current(BasicFileAttributes attributes) {
            return size == attributes.size() && modified.equals(attributes.lastModifiedTime());
        }
    }

    @Override
    public InsightReport report(InsightsQuery query) {
        // Crawls belong to the project, so a question about this session still covers all of them.
        InsightsWindow window = query.sessionScoped() && !query.getWindow().explicit()
                ? new InsightsWindow(Instant.EPOCH, query.getNow(), "all time", false) : query.getWindow();
        Scan scan = scan(query);
        Map<String, Job> jobsById = new LinkedHashMap<>();
        scan.jobs.forEach(job -> jobsById.putIfAbsent(job.id(), job));
        Map<String, KnowledgeBase> knowledgeBases = new LinkedHashMap<>();
        scan.knowledgeBases.forEach(kb -> {
            knowledgeBases.putIfAbsent(kb.id(), kb);
            knowledgeBases.putIfAbsent(kb.name(), kb);
        });
        Set<String> names = new LinkedHashSet<>(jobsById.keySet());
        names.addAll(knowledgeBases.keySet());
        scan.jobs.stream().map(Job::knowledgeBase).filter(Objects::nonNull).forEach(names::add);
        String subject = query.matchSubject(names);

        List<Job> inWindow = scan.jobs.stream().filter(job -> overlaps(job, window)).toList();
        if (subject == null) {
            return overview(query, window, scan, inWindow);
        }
        if (jobsById.containsKey(subject)) {
            // A job named by its id is shown whenever it ran.
            return job(query, scan, jobsById.get(subject));
        }
        KnowledgeBase kb = knowledgeBases.get(subject);
        List<Job> jobs = inWindow.stream()
                .filter(job -> kb != null ? kb.owns(job) : subject.equals(job.knowledgeBase())).toList();
        return knowledgeBase(query, window, scan, kb, kb != null ? kb.name() : subject, jobs);
    }

    private InsightReport overview(InsightsQuery query, InsightsWindow window, Scan scan, List<Job> jobs) {
        ZoneId zone = query.getZone();
        List<KnowledgeBase> knowledgeBases = scan.knowledgeBases;
        List<String> parts = new ArrayList<>();
        if (jobs.isEmpty()) {
            parts.add("no crawl jobs");
        } else {
            long active = jobs.stream().filter(Job::active).count();
            long failed = jobs.stream().filter(Job::failed).count();
            List<String> states = new ArrayList<>();
            if (active > 0) {
                states.add(Format.count(active) + " in progress");
            }
            if (failed > 0) {
                states.add(Format.count(failed) + " failed");
            }
            parts.add(Format.count(jobs.size(), "job") + (states.isEmpty() ? "" : " (" + String.join(", ", states) + ")"));
        }
        parts.add(knowledgeBases.isEmpty() ? "no project knowledge bases"
                : Format.count(knowledgeBases.size(), "knowledge base") + ", "
                + Format.count(sum(knowledgeBases, KnowledgeBase::documents), "document"));
        String headline = "Crawls, " + window.label() + ": " + String.join("; ", parts);

        StringBuilder text = new StringBuilder(headline).append('\n');
        if (!jobs.isEmpty()) {
            Instant earliest = jobs.stream().map(Job::when).filter(Objects::nonNull)
                    .min(Comparator.naturalOrder()).orElse(null);
            TimeBuckets buckets = TimeBuckets.of(window, earliest, config.getSparklineBuckets(), zone);
            long[] started = new long[buckets.size()];
            for (Job job : jobs) {
                int bucket = buckets.indexOf(job.when());
                if (bucket >= 0) {
                    started[bucket]++;
                }
            }
            text.append('\n').append("jobs  ").append(Sparkline.of(started)).append("  ").append(buckets.unit())
                    .append(" from ").append(buckets.labels().get(0))
                    .append(", peak ").append(Format.count(max(started))).append('\n');
            appendJobs(text, "Jobs, newest first:", jobs, zone, true);
            appendFailures(text, jobs, zone);
        }
        appendKnowledgeBases(text, knowledgeBases, zone);
        ObjectNode chart = !knowledgeBases.isEmpty() ? knowledgeBaseChart(knowledgeBases)
                : !jobs.isEmpty() ? jobChart("Documents per crawl job, " + window.label(), jobs, zone, true) : null;
        return finish(headline, text, chart, query, window, scan);
    }

    private InsightReport knowledgeBase(InsightsQuery query, InsightsWindow window, Scan scan, KnowledgeBase kb,
                                        String name, List<Job> jobs) {
        ZoneId zone = query.getZone();
        List<String> parts = new ArrayList<>();
        if (kb != null) {
            List<String> counts = new ArrayList<>();
            addCount(counts, kb.documents(), "document");
            addCount(counts, kb.chunks(), "chunk");
            addCount(counts, kb.entities(), "entity", "entities");
            addCount(counts, kb.relations(), "relation");
            parts.add(Format.words(kb.status()) + (counts.isEmpty() ? "" : ", " + String.join(", ", counts)));
            parts.add("last crawl " + Format.dateTime(kb.lastCrawl(), zone));
        }
        parts.add(Format.count(jobs.size(), "crawl job") + ", " + window.label());
        String headline = "Knowledge base " + name + ": " + String.join("; ", parts);

        StringBuilder text = new StringBuilder(headline).append('\n');
        if (kb != null) {
            text.append('\n');
            field(text, "id", kb.id().equals(kb.name()) ? null : kb.id());
            field(text, "sources", kb.sources() < 0 ? null : Format.count(kb.sources()));
            field(text, "code", kb.codeEntities() > 0 ? Format.count(kb.codeEntities(), "code entity", "code entities")
                    : null);
            field(text, "vectors", kb.vectors() > 0 ? Format.count(kb.vectors())
                    + (kb.embedding() == null ? "" : " (" + kb.embedding() + ")") : null);
            field(text, "fact sheet", kb.factSheetId());
            field(text, "graph", kb.graphPath());
            field(text, "path", kb.path());
        }
        ObjectNode chart = null;
        if (jobs.isEmpty()) {
            text.append("\nNo crawl jobs for it, ").append(window.label()).append(".\n");
        } else {
            appendJobs(text, "Crawl jobs, newest first:", jobs, zone, false);
            appendFailures(text, jobs, zone);
            chart = jobChart("Documents per crawl of " + name + ", " + window.label(), jobs, zone, false);
        }
        return finish(headline, text, chart, query, window, scan);
    }

    private InsightReport job(InsightsQuery query, Scan scan, Job job) {
        ZoneId zone = query.getZone();
        String headline = "Crawl job " + job.id()
                + (job.knowledgeBase() == null ? "" : " (" + job.knowledgeBase() + ")")
                + ", " + Format.dateTime(job.when(), zone) + ": " + job.state();
        StringBuilder text = new StringBuilder(headline).append("\n\n");
        field(text, "where", LOCAL.equals(job.where()) ? "this project" : "crawl manager " + serverUrl);
        field(text, "stage", job.stage() == null ? job.detail()
                : job.detail() == null ? job.stage() : job.stage() + " - " + job.detail());
        field(text, "progress", job.progress() >= 0 ? job.progress() + "%" : null);
        field(text, "queue", job.queuePosition() > 0 ? "#" + job.queuePosition() : null);
        field(text, "started", job.started() == null ? null : Format.dateTime(job.started(), zone));
        field(text, "finished", job.finished() == null ? null : Format.dateTime(job.finished(), zone));
        field(text, "took", job.tookMs() < 0 ? null
                : Format.duration(job.tookMs()) + (job.active() ? " so far" : ""));
        field(text, "documents", known(job.documents()));
        field(text, "chunks", known(job.chunks()));
        field(text, "entities", known(job.entities()));
        field(text, "relations", known(job.relations()));
        field(text, "errors", known(job.errors()));
        field(text, "error", job.error());
        return finish(headline, text, null, query, null, scan);
    }

    private void appendJobs(StringBuilder text, String title, List<Job> jobs, ZoneId zone, boolean showKnowledgeBase) {
        // The "where" column only tells jobs apart when both the project and a crawl manager ran some.
        boolean showWhere = jobs.stream().map(Job::where).distinct().count() > 1;
        List<String> headers = new ArrayList<>(List.of("when", "state"));
        List<Integer> left = new ArrayList<>(List.of(1));
        if (showKnowledgeBase) {
            left.add(headers.size());
            headers.add("knowledge base");
        }
        headers.addAll(List.of("docs", "chunks", "entities", "errors", "took"));
        if (showWhere) {
            left.add(headers.size());
            headers.add("where");
        }
        TextTable table = new TextTable(headers.toArray(String[]::new))
                .left(left.stream().mapToInt(Integer::intValue).toArray()).maxCell(MAX_NAME);
        int shown = Math.min(jobs.size(), config.getMaxRows());
        for (Job job : jobs.subList(0, shown)) {
            List<Object> row = new ArrayList<>(List.of(Format.dateTime(job.when(), zone), job.state()));
            if (showKnowledgeBase) {
                row.add(job.knowledgeBase() == null ? "-" : job.knowledgeBase());
            }
            row.addAll(List.of(shown(job.documents()), shown(job.chunks()), shown(job.entities()),
                    shown(job.errors()), Format.duration(job.tookMs())));
            if (showWhere) {
                row.add(job.where());
            }
            table.row(row.toArray());
        }
        text.append('\n').append(title).append('\n').append(table.render());
        more(text, jobs.size(), shown, "jobs");
    }

    private void appendFailures(StringBuilder text, List<Job> jobs, ZoneId zone) {
        List<Job> failed = jobs.stream().filter(Job::failed).toList();
        if (failed.isEmpty()) {
            return;
        }
        TextTable table = new TextTable("job", "when", "error").left(2).maxCell(MAX_ERROR);
        int shown = Math.min(failed.size(), config.getMaxExamples());
        failed.stream().limit(shown).forEach(job -> table.row(job.id(), Format.dateTime(job.when(), zone),
                job.error() == null ? "-" : job.error()));
        text.append("\nFailed jobs, newest first:\n").append(table.render());
        more(text, failed.size(), shown, "failed jobs");
    }

    private void appendKnowledgeBases(StringBuilder text, List<KnowledgeBase> knowledgeBases, ZoneId zone) {
        if (knowledgeBases.isEmpty()) {
            return;
        }
        TextTable table = new TextTable("knowledge base", "status", "docs", "chunks", "entities", "relations",
                "last crawl").left(1).maxCell(MAX_NAME);
        int shown = Math.min(knowledgeBases.size(), config.getMaxRows());
        knowledgeBases.stream().limit(shown).forEach(kb -> table.row(kb.name(), Format.words(kb.status()),
                shown(kb.documents()), shown(kb.chunks()), shown(kb.entities()), shown(kb.relations()),
                Format.dateTime(kb.lastCrawl(), zone)));
        text.append("\nProject knowledge bases, latest crawl first:\n").append(table.render());
        more(text, knowledgeBases.size(), shown, "knowledge bases");
    }

    private ObjectNode knowledgeBaseChart(List<KnowledgeBase> knowledgeBases) {
        List<KnowledgeBase> shown = knowledgeBases.stream().limit(config.getMaxRows()).toList();
        double[] documents = shown.stream()
                .mapToDouble(kb -> kb.documents() < 0 ? Double.NaN : kb.documents()).toArray();
        return Charts.bar("Documents per knowledge base", "documents",
                shown.stream().map(KnowledgeBase::name).toList(), Map.of("documents", documents));
    }

    /** Documents per job for the newest jobs, oldest first. */
    private ObjectNode jobChart(String title, List<Job> jobsNewestFirst, ZoneId zone, boolean nameKnowledgeBase) {
        List<Job> shown = new ArrayList<>(jobsNewestFirst.subList(0, Math.min(jobsNewestFirst.size(),
                config.getMaxRows())));
        Collections.reverse(shown);
        List<String> labels = shown.stream().map(job -> Format.dateTime(job.when(), zone)
                + (nameKnowledgeBase && job.knowledgeBase() != null ? " " + job.knowledgeBase() : "")).toList();
        double[] documents = shown.stream()
                .mapToDouble(job -> job.documents() < 0 ? Double.NaN : job.documents()).toArray();
        return Charts.bar(title, "documents", labels, Map.of("documents", documents));
    }

    private InsightReport finish(String headline, StringBuilder text, ObjectNode chart, InsightsQuery query,
                                 InsightsWindow window, Scan scan) {
        List<String> notes = new ArrayList<>(scan.notes);
        String retention = window == null ? null : retentionNote(window, query.getNow(), scan);
        if (retention != null) {
            notes.add(retention);
        }
        if (query.sessionScoped()) {
            notes.add("Crawls belong to the project, not to a chat session, so this covers all of them.");
        }
        if (config.getWarning() != null) {
            notes.add(config.getWarning());
        }
        if (!notes.isEmpty()) {
            text.append('\n');
            notes.forEach(note -> text.append(note).append('\n'));
        }
        return InsightReport.builder().topic(TOPIC).headline(headline).text(text.toString()).chart(chart).build();
    }

    /**
     * Explains missing jobs when a knowledge base was crawled inside the window but before the
     * project-local store's retention, so its job record is gone; null when nothing is missing.
     */
    private static String retentionNote(InsightsWindow window, Instant now, Scan scan) {
        long retention = LocalCrawlJobStore.retentionMs();
        Instant cutoff = now.minusMillis(retention);
        boolean missing = scan.knowledgeBases.stream().anyMatch(kb -> kb.lastCrawl() != null
                && kb.lastCrawl().isBefore(cutoff) && window.contains(kb.lastCrawl()));
        return missing ? "Project-local crawl jobs are kept for " + span(retention)
                + " after they finish; an older crawl shows only as its knowledge base's last crawl." : null;
    }

    private Scan scan(InsightsQuery query) {
        Scan scan = new Scan();
        scanProject(scan, query);
        scanServer(scan, query);
        scan.jobs.sort(NEWEST_FIRST);
        scan.knowledgeBases.sort(Comparator.comparing(KnowledgeBase::lastCrawl,
                        Comparator.nullsFirst(Comparator.<Instant>naturalOrder())).reversed()
                .thenComparing(KnowledgeBase::name));
        return scan;
    }

    private void scanProject(Scan scan, InsightsQuery query) {
        if (workDir == null) {
            return;
        }
        LocalProjectCrawlBackend backend = new LocalProjectCrawlBackend(MAPPER);
        Path root;
        try {
            root = backend.projectRoot(workDir);
        } catch (RuntimeException e) {
            scan.notes.add("The project of " + workDir + " could not be found (" + message(e)
                    + "), so its crawls are missing.");
            return;
        }
        for (JsonNode state : LocalCrawlJobStore.snapshot(root)) {
            if (state instanceof ObjectNode object) {
                scan.jobs.add(localJob(object, query));
            }
        }
        try {
            for (JsonNode item : backend.knowledgeBaseInventory(workDir)) {
                scan.knowledgeBases.add(knowledgeBase(item, query.getZone()));
            }
        } catch (IOException | RuntimeException e) {
            scan.notes.add("The project's knowledge bases could not be read (" + message(e) + ").");
        }
    }

    private void scanServer(Scan scan, InsightsQuery query) {
        if (server == null || !server.isAvailable()) {
            return;
        }
        ServerRead read = readServer(REPORT_READ_TIMEOUT);
        if (read.jobs() == null) {
            scan.notes.add("The crawl manager at " + serverUrl + " " + read.problem() + ", so its crawls are missing.");
            return;
        }
        scan.jobs.addAll(serverJobs(read.jobs(), query));
    }

    private ServerRead readServer(Duration readTimeout) {
        GroundingResponse response;
        try {
            response = server.get(JOBS_PATH, readTimeout);
        } catch (RuntimeException e) {
            return new ServerRead(null, "could not be reached ("
                    + Format.clamp(GroundingBackendClient.failureReason(e), MAX_NOTE) + ")", "unreachable");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String reason = GroundingBackendClient.errorMessage(response.body());
            return new ServerRead(null, "answered HTTP " + response.statusCode()
                    + (reason.isBlank() ? "" : " (" + Format.clamp(reason, MAX_NOTE) + ")"),
                    "HTTP " + response.statusCode());
        }
        JsonNode jobs;
        try {
            jobs = MAPPER.readTree(response.body());
        } catch (IOException e) {
            jobs = null;
        }
        if (jobs == null || !jobs.isArray()) {
            return new ServerRead(null, "sent a job list that could not be read", "job list unreadable");
        }
        return new ServerRead(jobs, null, null);
    }

    /** The crawl manager's jobs, each id once. */
    private static List<Job> serverJobs(JsonNode jobs, InsightsQuery query) {
        List<Job> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode node : jobs) {
            if (node.isObject()) {
                Job job = serverJob(node, query);
                if (seen.add(job.id())) {
                    result.add(job);
                }
            }
        }
        return result;
    }

    /**
     * The active crawls and their progress, "Crawl: 2 active: docs 45% (enrichment), api (queued)",
     * live while any is active and with the newest one's stage beneath; else the newest job's outcome.
     * A stored job is read again only when its file has changed, and the crawl manager is asked at
     * most every {@link #PANEL_REUSE}, or every {@link #PANEL_BACKOFF} after a failed read.
     */
    @Override
    public synchronized Panel.Line panelLine(InsightsQuery query) throws IOException {
        List<Job> jobs = new ArrayList<>(panelLocalJobs(query));
        jobs.addAll(panelServerJobs(query));
        jobs.sort(NEWEST_FIRST);
        String problem = panelServerProblem == null ? "" : " · " + panelServerProblem;
        String problemDetail = panelServerReason == null ? null : "↳ " + panelServerReason;
        List<Job> active = jobs.stream().filter(Job::active).toList();
        if (!active.isEmpty()) {
            String progress = String.join(", ", active.stream().map(CrawlInsights::panelProgress).toList());
            String detail = panelDetail(active.get(0));
            return new Panel.Line("Crawl: " + Format.count(active.size()) + " active: " + progress + problem,
                    detail != null ? detail : problemDetail, true);
        }
        if (jobs.isEmpty()) {
            return new Panel.Line("Crawl: no recent crawl jobs" + problem, problemDetail, false);
        }
        Job last = jobs.get(0);
        Instant ended = last.finished() != null ? last.finished() : last.when();
        String text = "Crawl: none active · last: " + panelName(last) + " "
                + (last.failed() ? last.state().toUpperCase(Locale.ROOT) : last.state())
                + (ended == null ? "" : ", " + Format.ago(ended, query.getNow())) + problem;
        String detail = last.failed() && last.error() != null
                ? "↳ " + Format.clamp(last.error(), PANEL_DETAIL) : problemDetail;
        return new Panel.Line(text, detail, false);
    }

    /**
     * The job store's directory, for jobs that start or are removed, and the state file of each job
     * that has not finished. A crawl manager's jobs have no file here; the panel's live timer
     * refreshes them.
     */
    @Override
    public synchronized List<Panel.Watch> panelWatches(InsightsQuery query) {
        if (panelRoot == null) {
            return List.of();
        }
        Path jobs = panelRoot.resolve(JOBS_DIRECTORY);
        if (!Files.isDirectory(jobs)) {
            Path parent = jobs.getParent();
            return Files.isDirectory(parent) ? List.of(new Panel.Watch(parent, jobs.getFileName().toString()))
                    : List.of();
        }
        List<Panel.Watch> watches = new ArrayList<>();
        watches.add(new Panel.Watch(jobs, null));
        panelStored.forEach((id, stored) -> {
            if (stored.state() != null && !stored.state().path("terminal").asBoolean(false)) {
                watches.add(new Panel.Watch(jobs.resolve(id), STATE_FILE));
            }
        });
        return watches;
    }

    private List<Job> panelLocalJobs(InsightsQuery query) throws IOException {
        if (workDir == null) {
            return List.of();
        }
        if (panelProjects == null) {
            panelProjects = new LocalProjectCrawlBackend(MAPPER);
        }
        // Found again on each refresh, so that a project initialised during the session is followed.
        panelRoot = LocalCrawlJobStore.normalizeRoot(panelProjects.projectRoot(workDir));
        Map<String, StoredJob> stored = new TreeMap<>();
        List<Job> jobs = new ArrayList<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(panelRoot.resolve(JOBS_DIRECTORY))) {
            for (Path child : children) {
                String id = child.getFileName().toString();
                BasicFileAttributes attributes;
                try {
                    attributes = Files.readAttributes(child.resolve(STATE_FILE), BasicFileAttributes.class);
                } catch (IOException e) {
                    continue; // Not a job directory, or one whose first state is not written yet.
                }
                StoredJob job = panelStored.get(id);
                if (job == null || !job.current(attributes)) {
                    job = new StoredJob(attributes.size(), attributes.lastModifiedTime(),
                            LocalCrawlJobStore.load(panelRoot, id).orElse(null));
                }
                stored.put(id, job);
                if (job.state() != null) {
                    jobs.add(localJob(job.state(), query));
                }
            }
        } catch (NoSuchFileException | NotDirectoryException e) {
            // No crawl has run in this project yet.
        }
        panelStored = stored;
        return jobs;
    }

    private List<Job> panelServerJobs(InsightsQuery query) {
        if (server == null || !server.isAvailable()) {
            return List.of();
        }
        Instant now = query.getNow();
        Duration since = panelServerReadAt == null ? null : Duration.between(panelServerReadAt, now);
        Duration wait = panelServerJobs == null ? PANEL_BACKOFF : PANEL_REUSE;
        if (since == null || since.isNegative() || since.compareTo(wait) >= 0) {
            ServerRead read = readServer(PANEL_READ_TIMEOUT);
            panelServerJobs = read.jobs();
            panelServerReadAt = now;
            panelServerProblem = read.jobs() == null ? "crawl manager " + read.brief() : null;
            panelServerReason = read.jobs() == null ? "crawl manager at " + serverUrl + " " + read.problem() : null;
        }
        return panelServerJobs == null ? List.of() : serverJobs(panelServerJobs, query);
    }

    /** "docs 45% (enrichment)", "api (queued)". */
    private static String panelProgress(Job job) {
        return panelName(job) + (job.progress() > 0 ? " " + job.progress() + "%" : "")
                + " (" + Format.words(job.stage() != null ? job.stage() : job.status()) + ")";
    }

    /** "↳ docs: Embedding chunks · 12m05s so far"; null when neither is known. */
    private static String panelDetail(Job job) {
        List<String> parts = new ArrayList<>();
        if (job.detail() != null) {
            parts.add(Format.clamp(job.detail(), PANEL_DETAIL));
        }
        if (job.tookMs() >= 0) {
            parts.add(Format.duration(job.tookMs()) + " so far");
        }
        return parts.isEmpty() ? null : "↳ " + panelName(job) + ": " + String.join(" · ", parts);
    }

    private static String panelName(Job job) {
        return Format.clamp(job.knowledgeBase() != null ? job.knowledgeBase() : job.id(), PANEL_NAME);
    }

    /**
     * A stored project-local job. One whose process exited before it finished is interrupted,
     * ending at its last update, whether or not a lookup has since recorded it as failed.
     */
    static Job localJob(ObjectNode state, InsightsQuery query) {
        ZoneId zone = query.getZone();
        boolean ownerExited = LocalCrawlJobStore.ownerExited(state);
        boolean interrupted = ownerExited || INTERRUPTED.equals(text(state, "stage"));
        boolean terminal = state.path("terminal").asBoolean(false);
        Instant created = instant(state.get("createdAt"), zone);
        Instant started = instant(state.get("startedAt"), zone);
        Instant finished = instant(state.get(ownerExited ? "stageUpdatedAt" : "finishedAt"), zone);
        JsonNode result = state.path("result");
        JsonNode metadata = result.path("metadata");
        String error = ownerExited ? "The process that ran this crawl exited before the crawl finished."
                : result.path("error").asBoolean(false) ? text(result, "output") : text(state, "finalizationError");
        return new Job(first(text(state, "jobId"), "(no id)"), LOCAL,
                first(text(state, "knowledgeBaseId"), text(state, "knowledgeBase"), text(metadata, "knowledgeBase")),
                status(text(state, "status")), !terminal && !interrupted, interrupted,
                state.path("progressPercent").asInt(-1), text(state, "stage"), text(state, "stageDetail"), -1,
                created, started, finished,
                elapsed(started != null ? started : created, finished != null ? finished
                        : terminal || interrupted ? null : query.getNow()),
                count(metadata.get("documentCount")), count(metadata.get("chunkCount")),
                count(metadata.get("graphEntityCount")), count(metadata.get("graphRelationCount")),
                errors(count(metadata.get("failedDocumentCount")), metadata.get("semanticExtractionErrors")), error);
    }

    /** A crawl-manager job, live or from its history. */
    static Job serverJob(JsonNode node, InsightsQuery query) {
        ZoneId zone = query.getZone();
        String status = status(text(node, "status"));
        Instant created = instant(node.get("createdAt"), zone);
        Instant started = instant(node.get("startedAt"), zone);
        Instant finished = instant(node.get("completedAt"), zone);
        boolean active = SERVER_ACTIVE.contains(status) && finished == null;
        long took = count(node.get("elapsedMs"));
        if (took < 0) {
            took = elapsed(started != null ? started : created, finished != null ? finished
                    : active ? query.getNow() : null);
        }
        String factSheetId = text(node, "factSheetId");
        return new Job(first(text(node, "jobId"), text(node, "internalJobId"), text(node, "schedulerJobId"), "(no id)"),
                SERVER, first(text(node, "name"), factSheetId == null ? null : "fact sheet " + factSheetId),
                status, active, false, (int) Math.round(node.path("progressPercent").asDouble(-1)),
                text(node, "currentPhase"), text(node, "currentFile"), node.path("queuePosition").asInt(-1),
                created, started, finished, took,
                firstCount(node, "documentsLoaded", "documentsIndexed"), firstCount(node, "chunksCreated",
                "chunksProcessed"), count(node.get("entitiesExtracted")), count(node.get("relationshipsExtracted")),
                count(node.get("errorCount")), serverError(node));
    }

    private static KnowledgeBase knowledgeBase(JsonNode item, ZoneId zone) {
        String id = first(text(item, "id"), "(no id)");
        return new KnowledgeBase(id, first(text(item, "name"), id), status(text(item, "status")),
                instant(item.get("finishedAt"), zone), count(item.get("sourceCount")),
                count(item.get("documentCount")), count(item.get("chunkCount")), count(item.get("graphEntityCount")),
                count(item.get("graphRelationCount")), count(item.get("codeEntityCount")),
                count(item.get("embeddingVectorCount")), text(item, "embeddingAlgorithm"), text(item, "factSheetId"),
                text(item, "graphPath"), text(item, "path"));
    }

    private static boolean overlaps(Job job, InsightsWindow window) {
        Instant from = job.when();
        if (from == null) {
            return window.allTime();
        }
        return !from.isAfter(window.end()) && (job.finished() == null || !job.finished().isBefore(window.start()));
    }

    /**
     * A timestamp as a server or the job store wrote it: ISO with or without an offset (without
     * one it is local to {@code zone}), or epoch seconds or milliseconds as a number or a string.
     * Null when absent or unreadable.
     */
    static Instant instant(JsonNode value, ZoneId zone) {
        if (value == null || value.isNull() || value.isMissingNode() || value.isContainerNode()) {
            return null;
        }
        if (value.isNumber()) {
            return epoch(value.decimalValue());
        }
        String text = value.asText().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeException ignored) {
            // Not UTC ISO; try the other forms.
        }
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeException ignored) {
            // No offset.
        }
        try {
            return LocalDateTime.parse(text).atZone(zone).toInstant();
        } catch (DateTimeException ignored) {
            // Not a date-time at all.
        }
        try {
            return epoch(new BigDecimal(text));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Instant epoch(BigDecimal number) {
        try {
            BigDecimal seconds = number.abs().compareTo(SECONDS_LIMIT) < 0 ? number : number.movePointLeft(3);
            BigDecimal whole = seconds.setScale(0, RoundingMode.FLOOR);
            return Instant.ofEpochSecond(whole.longValueExact(), seconds.subtract(whole).movePointRight(9).longValue());
        } catch (ArithmeticException | DateTimeException e) {
            return null;
        }
    }

    /** A count from a number or a numeric string; -1 when none was recorded. */
    static long count(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return -1;
        }
        if (value.isNumber()) {
            return value.asLong();
        }
        if (value.isTextual()) {
            try {
                return Long.parseLong(value.asText().trim());
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    private static long firstCount(JsonNode node, String... fields) {
        for (String field : fields) {
            long n = count(node.get(field));
            if (n >= 0) {
                return n;
            }
        }
        return -1;
    }

    /** Failed documents plus semantic-extraction errors; -1 when neither was recorded. */
    private static long errors(long failedDocuments, JsonNode extractionErrors) {
        long extraction = extractionErrors != null && extractionErrors.isArray()
                ? extractionErrors.size() : count(extractionErrors);
        if (failedDocuments < 0 && extraction < 0) {
            return -1;
        }
        return Math.max(0, failedDocuments) + Math.max(0, extraction);
    }

    private static String serverError(JsonNode node) {
        String message = text(node, "errorMessage");
        if (message != null) {
            return message;
        }
        JsonNode errors = node.path("errors");
        if (errors.isArray() && !errors.isEmpty()) {
            JsonNode error = errors.get(0);
            return error.isObject() ? first(text(error, "message"), text(error, "error")) : text(errors, 0);
        }
        return null;
    }

    private static long elapsed(Instant from, Instant to) {
        return from == null || to == null || to.isBefore(from) ? -1 : Duration.between(from, to).toMillis();
    }

    private static String status(String status) {
        return status == null ? "UNKNOWN" : status.toUpperCase(Locale.ROOT);
    }

    /** A field's trimmed text; null when absent, blank or not a scalar. */
    static String text(JsonNode node, String field) {
        return value(node == null ? null : node.get(field));
    }

    private static String text(JsonNode array, int index) {
        return value(array.get(index));
    }

    private static String value(JsonNode value) {
        if (value == null || value.isNull() || value.isContainerNode()) {
            return null;
        }
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }

    static String first(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static <T> long sum(List<T> items, ToLongFunction<T> count) {
        return items.stream().mapToLong(count).filter(n -> n > 0).sum();
    }

    private static long max(long[] values) {
        long max = 0;
        for (long value : values) {
            max = Math.max(max, value);
        }
        return max;
    }

    private static void addCount(List<String> parts, long n, String noun) {
        addCount(parts, n, noun, noun + "s");
    }

    private static void addCount(List<String> parts, long n, String singular, String plural) {
        if (n >= 0) {
            parts.add(Format.count(n, singular, plural));
        }
    }

    private static String shown(long n) {
        return n < 0 ? "-" : Format.count(n);
    }

    private static String known(long n) {
        return n < 0 ? null : Format.count(n);
    }

    /** "24 hours", "3 days", "90 minutes". */
    private static String span(long ms) {
        long hours = ms / 3_600_000L;
        if (hours >= 48 && hours % 24 == 0 && ms % 3_600_000L == 0) {
            return Format.count(hours / 24, "day");
        }
        if (hours >= 1 && ms % 3_600_000L == 0) {
            return Format.count(hours, "hour");
        }
        return Format.count(Math.max(0, ms / 60_000L), "minute");
    }

    static String message(Exception e) {
        return Format.clamp(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), MAX_NOTE);
    }

    private static void field(StringBuilder text, String label, String value) {
        if (value != null && !value.isBlank()) {
            text.append(label).append(" ".repeat(Math.max(1, 10 - label.length())))
                    .append(Format.clamp(value, MAX_DETAIL)).append('\n');
        }
    }

    private static void more(StringBuilder text, int total, int shown, String noun) {
        if (total > shown) {
            text.append("(+").append(Format.count(total - shown)).append(" more ").append(noun).append(")\n");
        }
    }
}
