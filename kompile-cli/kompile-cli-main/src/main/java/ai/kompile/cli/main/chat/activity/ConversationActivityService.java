package ai.kompile.cli.main.chat.activity;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.app.services.diffindex.DiffIndexService;
import ai.kompile.cli.main.chat.harness.ModelPerformanceStore;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Shared read/model layer for current, selected, project, global, and transcript activity.
 * It composes existing stores and retains only small sidecar lifecycle evidence.
 */
public final class ConversationActivityService {
    private static final long SUMMARY_LOCK_TIMEOUT_MILLIS = 500L;
    /** Smaller bounds used by list views; details are loaded only after selection. */
    public static final ActivityReadBudget LIST_BUDGET = new ActivityReadBudget(
            16 * 1024, 64, 16, 32);
    private final ActivityStorage storage;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final CopyOnWriteArrayList<Function<ActivityIdentity, ActivityEvidenceReader>> readerFactories;
    private final ActivityRecorder recorder;
    private volatile ActivityIdentity currentIdentity;

    public ConversationActivityService() {
        this(new ActivityStorage(), Clock.systemUTC(), null);
    }

    public ConversationActivityService(ActivityStorage storage) {
        this(storage, Clock.systemUTC(), null);
    }

    public ConversationActivityService(ActivityStorage storage,
                                       Clock clock,
                                       List<Function<ActivityIdentity, ActivityEvidenceReader>> readers) {
        this.storage = storage;
        this.mapper = JsonUtils.standardMapper();
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.readerFactories = new CopyOnWriteArrayList<>(
                readers == null ? defaultReaders() : List.copyOf(readers));
        this.recorder = new ActivityRecorder(storage, mapper, this.clock);
    }

    public ConversationActivityService(ActivityStorage storage,
                                       List<Function<ActivityIdentity, ActivityEvidenceReader>> readers) {
        this(storage, Clock.systemUTC(), readers);
    }

    public void setCurrentSession(ActivityIdentity identity) {
        currentIdentity = identity;
    }

    public Optional<ActivityIdentity> currentIdentity() {
        return Optional.ofNullable(currentIdentity);
    }

    public Optional<ConversationActivitySummary> currentSession() {
        return currentIdentity == null ? Optional.empty() : session(currentIdentity);
    }

    public Optional<ConversationActivitySummary> selectedSession(ActivityIdentity identity) {
        return session(identity);
    }

    /** Add a read-only producer adapter without replacing the shared foundation. */
    public void addReader(Function<ActivityIdentity, ActivityEvidenceReader> readerFactory) {
        if (readerFactory != null) readerFactories.addIfAbsent(readerFactory);
    }

    public Optional<ConversationActivitySummary> session(ActivityIdentity identity) {
        if (identity == null) return Optional.empty();
        ConversationActivitySummary summary = readStored(identity)
                .orElse(ConversationActivitySummary.empty(identity));
        for (Function<ActivityIdentity, ActivityEvidenceReader> factory : readerFactories) {
            if (factory == null) continue;
            ActivityEvidenceReader reader;
            try {
                reader = factory.apply(identity);
            } catch (RuntimeException failure) {
                summary = addWarning(summary, "Activity reader unavailable: " + failure.getMessage());
                continue;
            }
            if (reader == null) continue;
            ActivityEvidence evidence;
            try {
                evidence = reader.read(identity, ActivityReadBudget.DEFAULT);
            } catch (RuntimeException failure) {
                evidence = ActivityEvidence.unavailable(reader.id(),
                        "Activity reader failed: " + oneLine(failure.getMessage()));
            }
            summary = apply(summary, evidence);
        }
        if (summary.goal().isBlank()) {
            summary = new ConversationActivitySummary(summary.schemaVersion(), summary.identity(),
                    summary.title(), summary.goal(), "UNVERIFIED", summary.executionState(),
                    summary.evidenceBasis(), summary.startedAt(), summary.endedAt(), summary.elapsed(),
                    summary.blocking(), summary.tokens(), summary.edits(), summary.issues(),
                    summary.coverage(), summary.annotations(), summary.evidence(), summary.warnings());
        }
        return Optional.of(summary);
    }

    public List<ConversationActivitySummary> projectSummaries(Path projectRoot, int limit) {
        if (limit <= 0) return List.of();
        String project = projectRoot == null ? "" : projectRoot.toAbsolutePath().normalize().toString();
        return summaries(0, limit, summary -> project.isBlank()
                || project.equals(summary.identity().projectDirectory()));
    }

    public List<ConversationActivitySummary> projectSummaries(Path projectRoot, int offset, int limit) {
        if (limit <= 0) return List.of();
        String project = projectRoot == null ? "" : projectRoot.toAbsolutePath().normalize().toString();
        return summaries(offset, limit, summary -> project.isBlank()
                || project.equals(summary.identity().projectDirectory()));
    }

    public List<ConversationActivitySummary> globalSummaries(int limit) {
        return summaries(0, limit, summary -> true);
    }

    public List<ConversationActivitySummary> globalSummaries(int offset, int limit) {
        return summaries(offset, limit, summary -> true);
    }

    public ConversationActivityDetail detail(ActivityIdentity identity, ActivityReadBudget budget) {
        ConversationActivitySummary summary = session(identity).orElse(
                ConversationActivitySummary.empty(identity));
        ActivityJournalReader.ReadResult read;
        try {
            read = new ActivityJournalReader(storage.eventsPath(identity)).read(budget);
        } catch (IOException failure) {
            return new ConversationActivityDetail(summary, List.of(), 0, false,
                    List.of("Activity journal unavailable: " + oneLine(failure.getMessage())));
        }
        List<String> details = new ArrayList<>();
        for (Function<ActivityIdentity, ActivityEvidenceReader> factory : readerFactories) {
            try {
                ActivityEvidenceReader reader = factory.apply(identity);
                if (reader != null) details.addAll(reader.details(identity,
                        budget == null ? ActivityReadBudget.DEFAULT : budget));
            } catch (RuntimeException ignored) {
                // The summary already carries the producer coverage warning.
            }
            if (details.size() >= 64) break;
        }
        return new ConversationActivityDetail(summary, read.events(), read.malformedRecords(),
                read.truncated(), details.stream().limit(64).toList(), read.warnings());
    }

    public TranscriptActivityReader.TranscriptInspection inspectTranscript(
            Path requestedPath, Path permittedRoot, ActivityReadBudget budget) throws IOException {
        if (requestedPath == null || permittedRoot == null) {
            throw new IOException("Transcript path and permitted root are required");
        }
        Path safe = ActivitySourceRef.resolve(requestedPath.toString(), permittedRoot);
        if (!Files.isRegularFile(safe, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Transcript is not a regular file: " + safe);
        }
        return new TranscriptActivityReader(safe, permittedRoot).inspect(budget);
    }

    public ActivityAnnotation annotateGoal(ActivityIdentity identity, String goal, String actorId) {
        ActivityAnnotation annotation = ActivityAnnotation.goal(UUID.randomUUID().toString(), goal,
                actorId, clock.instant());
        appendAnnotation(identity, annotation);
        return annotation;
    }

    public ActivityAnnotation annotateOutcome(ActivityIdentity identity, String status, String text,
                                              String actorId, List<ActivitySourceRef> evidence) {
        ActivityAnnotation annotation = ActivityAnnotation.outcome(UUID.randomUUID().toString(),
                status, text, actorId, clock.instant(), evidence);
        appendAnnotation(identity, annotation);
        return annotation;
    }

    /** Record an operator confirmation; agent/producer claims cannot establish ACHIEVED. */
    public ActivityAnnotation confirmOutcome(ActivityIdentity identity, String status, String text,
                                             String actorId, List<ActivitySourceRef> evidence) {
        ActivityAnnotation annotation = ActivityAnnotation.confirmedOutcome(UUID.randomUUID().toString(),
                status, text, actorId, clock.instant(), evidence);
        appendAnnotation(identity, annotation);
        return annotation;
    }

    /** Record an explicit commit association; no Git inspection or mutation is performed. */
    public ActivityAnnotation annotateCommit(ActivityIdentity identity, String repository,
                                             String commitHash, String basis, String actorId,
                                             ActivitySourceRef evidence) {
        ActivityAnnotation annotation = ActivityAnnotation.commit(UUID.randomUUID().toString(),
                repository, commitHash, basis, actorId, clock.instant(), evidence);
        appendAnnotation(identity, annotation);
        return annotation;
    }

    public ActivityEvent record(ActivityIdentity identity, String eventType, String actorId,
                                String operationId, ActivitySourceRef source,
                                java.util.Map<String, String> attributes) {
        return recorder.record(identity, eventType, actorId, operationId, source, attributes);
    }

    public List<String> recordingWarnings() {
        return recorder.warnings();
    }

    public ActivityStorage storage() {
        return storage;
    }

    private void appendAnnotation(ActivityIdentity identity, ActivityAnnotation annotation) {
        if (identity == null || annotation == null) return;
        Path lockPath = storage.summaryLockPath(identity);
        try {
            storage.ensureSafe(lockPath);
            Files.createDirectories(lockPath.getParent());
            storage.ensureSafe(lockPath);
            if (Files.isSymbolicLink(lockPath)) throw new IOException("Activity summary lock is a symbolic link");
            try (FileChannel channel = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                FileLock lock = acquireSummaryLock(channel);
                if (lock == null) return;
                try (lock) {
                    ConversationActivitySummary summary = readStored(identity).orElseGet(
                            () -> session(identity).orElse(ConversationActivitySummary.empty(identity)));
                    List<ActivityAnnotation> annotations = new ArrayList<>(summary.annotations());
                    annotations.add(annotation);
                    summary = summary.withAnnotations(deduplicateAnnotations(annotations));
                    if ("goal".equals(annotation.kind())) summary = summary.withGoal(annotation.text());
                    if ("outcome".equals(annotation.kind()) || "user_confirmation".equals(annotation.kind())) {
                        boolean confirmed = "user_confirmation".equals(annotation.kind())
                                && !annotation.actorId().isBlank();
                        boolean alreadyConfirmed = "USER_CONFIRMATION".equals(summary.evidenceBasis());
                        String nextOutcome = confirmed ? normalizeOutcome(annotation.status())
                                : alreadyConfirmed ? summary.outcome() : normalizeClaimedOutcome(annotation.status());
                        String nextBasis = confirmed ? "USER_CONFIRMATION"
                                : (alreadyConfirmed ? summary.evidenceBasis() : "AGENT_CLAIM");
                        summary = new ConversationActivitySummary(summary.schemaVersion(), summary.identity(),
                                summary.title(), summary.goal(), nextOutcome, summary.executionState(), nextBasis,
                                summary.startedAt(), summary.endedAt(), summary.elapsed(), summary.blocking(),
                                summary.tokens(), summary.edits(), summary.issues(), summary.coverage(),
                                summary.annotations(), summary.evidence(), summary.warnings());
                    }
                    writeSummary(summary);
                }
            }
        } catch (Exception failure) {
            recorder.warn("Activity summary update unavailable: " + oneLine(failure.getMessage()));
        }
        recorder.record(identity, "annotation." + annotation.kind(), annotation.actorId(),
                annotation.annotationId(), annotation.evidence().isEmpty() ? null : annotation.evidence().get(0),
                java.util.Map.of("status", annotation.status()));
    }

    private FileLock acquireSummaryLock(FileChannel channel) throws IOException {
        long deadline = System.nanoTime() + SUMMARY_LOCK_TIMEOUT_MILLIS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            try {
                FileLock lock = channel.tryLock();
                if (lock != null) return lock;
            } catch (OverlappingFileLockException ignored) {
                // Another service instance in this JVM owns the short summary update lock.
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                recorder.warn("Activity summary update interrupted while waiting for lock");
                return null;
            }
        }
        recorder.warn("Activity summary update skipped after bounded lock wait");
        return null;
    }

    private List<ConversationActivitySummary> summaries(int offset, int limit,
                                                          Predicate<ConversationActivitySummary> filter) {
        if (limit <= 0) return List.of();
        return storage.knownIdentities(Integer.MAX_VALUE).stream()
                .map(identity -> session(identity, LIST_BUDGET))
                .flatMap(Optional::stream)
                .collect(Collectors.toMap(summary -> summary.identity().key(), Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new))
                .values().stream()
                .filter(filter == null ? summary -> true : filter)
                .sorted(Comparator.comparing(ConversationActivitySummary::startedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .skip(Math.max(0, offset))
                .limit(limit)
                .toList();
    }

    private Optional<ConversationActivitySummary> session(ActivityIdentity identity,
                                                           ActivityReadBudget budget) {
        if (identity == null) return Optional.empty();
        ConversationActivitySummary summary = readStored(identity)
                .orElse(ConversationActivitySummary.empty(identity));
        ActivityReadBudget effectiveBudget = budget == null ? ActivityReadBudget.DEFAULT : budget;
        for (Function<ActivityIdentity, ActivityEvidenceReader> factory : readerFactories) {
            if (factory == null) continue;
            ActivityEvidenceReader reader;
            try {
                reader = factory.apply(identity);
            } catch (RuntimeException failure) {
                summary = addWarning(summary, "Activity reader unavailable: " + failure.getMessage());
                continue;
            }
            if (reader == null) continue;
            ActivityEvidence evidence;
            try {
                evidence = reader.read(identity, effectiveBudget);
            } catch (RuntimeException failure) {
                evidence = ActivityEvidence.unavailable(reader.id(),
                        "Activity reader failed: " + oneLine(failure.getMessage()));
            }
            summary = apply(summary, evidence);
        }
        if (summary.goal().isBlank()) {
            summary = new ConversationActivitySummary(summary.schemaVersion(), summary.identity(),
                    summary.title(), summary.goal(), "UNVERIFIED", summary.executionState(),
                    summary.evidenceBasis(), summary.startedAt(), summary.endedAt(), summary.elapsed(),
                    summary.blocking(), summary.tokens(), summary.edits(), summary.issues(),
                    summary.coverage(), summary.annotations(), summary.evidence(), summary.warnings());
        }
        return Optional.of(summary);
    }

    private ConversationActivitySummary apply(ConversationActivitySummary summary,
                                              ActivityEvidence evidence) {
        if (evidence == null) return summary;
        ConversationActivitySummary merged = summary.merge(evidence);
        ActivityIdentity effectiveIdentity = merged.identity();
        String recordedProject = evidence.attributes().get("workingDirectory");
        if (effectiveIdentity.projectDirectory().isBlank() && recordedProject != null
                && !recordedProject.isBlank()) {
            try {
                effectiveIdentity = new ActivityIdentity(effectiveIdentity.source(),
                        effectiveIdentity.conversationId(), effectiveIdentity.runId(),
                        effectiveIdentity.actorId(), effectiveIdentity.parentActorId(),
                        Path.of(recordedProject).toAbsolutePath().normalize().toString());
            } catch (RuntimeException ignored) {
                // Keep the original identity when legacy CWD metadata is malformed.
            }
        }
        String title = merged.title();
        String goal = merged.goal();
        String outcome = merged.outcome();
        String basis = merged.evidenceBasis();
        for (ActivityAnnotation annotation : evidence.annotations()) {
            if ("goal".equals(annotation.kind()) && goal.isBlank()) goal = annotation.text();
            if ("user_confirmation".equals(annotation.kind()) && !annotation.actorId().isBlank()) {
                outcome = normalizeOutcome(annotation.status());
                basis = "USER_CONFIRMATION";
            } else if ("outcome".equals(annotation.kind()) && !"USER_CONFIRMATION".equals(basis)) {
                outcome = normalizeClaimedOutcome(annotation.status());
                basis = "AGENT_CLAIM";
            }
            if (title.equals("(untitled)") && !annotation.text().isBlank()) title = annotation.text();
        }
        String execution = merged.executionState();
        String reportedExecution = evidence.attributes().get("executionState");
        if (reportedExecution != null && !reportedExecution.isBlank()) {
            execution = normalizeExecutionState(reportedExecution);
        }
        return new ConversationActivitySummary(merged.schemaVersion(), effectiveIdentity, title, goal,
                outcome, execution, basis,
                merged.startedAt() == null ? evidence.startedAt() : merged.startedAt(),
                merged.endedAt() == null ? evidence.endedAt() : merged.endedAt(),
                merged.elapsed(), merged.blocking(), merged.tokens(), merged.edits(), merged.issues(),
                merged.coverage(), merged.annotations(), merged.evidence(), merged.warnings());
    }

    private ConversationActivitySummary addWarning(ConversationActivitySummary summary, String warning) {
        List<String> warnings = new ArrayList<>(summary.warnings());
        if (warning != null && !warning.isBlank()) warnings.add(warning);
        return new ConversationActivitySummary(summary.schemaVersion(), summary.identity(), summary.title(),
                summary.goal(), summary.outcome(), summary.executionState(), summary.evidenceBasis(),
                summary.startedAt(), summary.endedAt(), summary.elapsed(), summary.blocking(),
                summary.tokens(), summary.edits(), summary.issues(), summary.coverage(),
                summary.annotations(), summary.evidence(), warnings);
    }

    private Optional<ConversationActivitySummary> readStored(ActivityIdentity identity) {
        Path file = storage.summaryPath(identity);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        try {
            return Optional.of(mapper.readValue(file.toFile(), ConversationActivitySummary.class));
        } catch (Exception failure) {
            return Optional.of(addWarning(ConversationActivitySummary.empty(identity),
                    "Stored activity summary is unreadable: " + oneLine(failure.getMessage())));
        }
    }

    private void writeSummary(ConversationActivitySummary summary) {
        Path target = storage.summaryPath(summary.identity());
        try {
            storage.ensureSafe(target);
            Files.createDirectories(target.getParent());
            storage.ensureSafe(target);
            if (Files.isSymbolicLink(target)) throw new IOException("Activity summary is a symbolic link");
            Path temporary = Files.createTempFile(target.getParent(), "activity-summary-", ".tmp");
            try {
                mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), summary);
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (Exception failure) {
            recorder.warn("Activity summary persistence unavailable: " + oneLine(failure.getMessage()));
        }
    }

    private static List<ActivityAnnotation> deduplicateAnnotations(List<ActivityAnnotation> values) {
        return values.stream().filter(annotation -> annotation != null)
                .collect(Collectors.toMap(ActivityAnnotation::annotationId, Function.identity(),
                        (left, right) -> right, LinkedHashMap::new)).values().stream()
                .limit(256).toList();
    }

    private static String normalizeOutcome(String status) {
        if (status == null || status.isBlank()) return "UNVERIFIED";
        String upper = status.strip().toUpperCase();
        return Set.of("ACHIEVED", "PARTIAL", "BLOCKED", "ABANDONED", "UNVERIFIED").contains(upper)
                ? upper : "UNVERIFIED";
    }

    private static String normalizeClaimedOutcome(String status) {
        return "ACHIEVED".equals(normalizeOutcome(status)) ? "UNVERIFIED" : normalizeOutcome(status);
    }

    private static String normalizeExecutionState(String state) {
        if (state == null || state.isBlank()) return "UNKNOWN";
        String upper = state.strip().toUpperCase();
        return Set.of("RUNNING", "WAITING", "CLEANLY_ENDED", "INTERRUPTED", "UNKNOWN").contains(upper)
                ? upper : "UNKNOWN";
    }

    private List<Function<ActivityIdentity, ActivityEvidenceReader>> defaultReaders() {
        Path root = storage.conversationsRoot();
        Path home = KompileHome.homeDirectory().toPath().toAbsolutePath().normalize();
        boolean productionStorage = root.equals(home.resolve("conversations").normalize());
        ModelPerformanceStore performanceStore = productionStorage ? new ModelPerformanceStore() : null;
        DiffIndexService diffIndex = productionStorage && usableDiffIndex(home)
                ? new DiffIndexService() : null;
        if (diffIndex != null) {
            try {
                // Loading the persisted index is intentionally the only work here. Browsing never
                // calls reindexAll(); a refresh belongs to an explicit producer operation.
                diffIndex.init();
            } catch (RuntimeException ignored) {
                diffIndex = null;
            }
        }
        final DiffIndexService loadedDiffIndex = diffIndex;
        return List.of(
                identity -> new TranscriptActivityReader(storage.transcriptPath(identity), root),
                identity -> new MetricsActivityReader(storage.metricsPath(identity), root),
                identity -> new JudgementActivityReader(home.resolve("sessions")
                        .resolve(safe(identity.conversationId())).resolve("judgements.jsonl"), home),
                identity -> performanceStore == null
                        ? new ActivityEvidenceReader() {
                            @Override
                            public String id() { return "performance"; }

                            @Override
                            public ActivityEvidence read(ActivityIdentity ignored, ActivityReadBudget budget) {
                                return ActivityEvidence.unavailable("performance",
                                        "Performance store is unavailable for this storage scope");
                            }
                        }
                        : new PerformanceActivityReader(performanceStore),
                identity -> loadedDiffIndex == null
                        ? new ActivityEvidenceReader() {
                            @Override
                            public String id() { return "diff-index"; }

                            @Override
                            public ActivityEvidence read(ActivityIdentity ignored, ActivityReadBudget budget) {
                                return ActivityEvidence.unavailable("diff-index",
                                        "Diff index is unavailable or contains no readable persisted entries");
                            }
                        }
                        : DiffIndexActivityAdapter.fromEntries(
                                () -> loadedDiffIndex.sessionEntries(identity.conversationId())));
    }

    private static boolean usableDiffIndex(Path home) {
        Path index = home.resolve("agent-state").resolve("diff-index");
        if (!Files.isDirectory(index, LinkOption.NOFOLLOW_LINKS)) return false;
        try (java.util.stream.Stream<Path> files = Files.list(index)) {
            return files.limit(256).anyMatch(path -> {
                try {
                    return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) > 3L;
                } catch (IOException ignored) {
                    return false;
                }
            });
        } catch (IOException ignored) {
            return false;
        }
    }

    private static String safe(String value) {
        return value == null ? "_unknown" : value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String oneLine(String value) {
        if (value == null || value.isBlank()) return "unknown error";
        String normalized = value.replace('\n', ' ').replace('\r', ' ').strip();
        return normalized.length() <= 240 ? normalized : normalized.substring(0, 239) + "…";
    }
}
