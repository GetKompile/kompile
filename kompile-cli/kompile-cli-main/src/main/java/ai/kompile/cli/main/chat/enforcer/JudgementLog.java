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

package ai.kompile.cli.main.chat.enforcer;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Append-only, thread-safe JSONL writer for {@link JudgementRecord}s, so that every enforcer
 * judgement made during a session is durably tracked and can be observed live or replayed
 * afterwards.
 *
 * <p>Records live at {@code ~/.kompile/sessions/<sessionId>/judgements.jsonl} (co-located with
 * the existing per-session enforcer artifacts). Writing is best-effort: a logging failure must
 * never break enforcement, so I/O errors are swallowed.</p>
 *
 * <p>An optional {@link JudgementSink} can be attached to mirror each record elsewhere (used by
 * the web surface to push records to the kompile server's enforcer event stream).</p>
 */
public class JudgementLog {

    private static final int MAX_EXCERPT_CHARS = 1_200;
    private static final int MAX_RAW_CHARS = 8_000;

    /** Optional secondary sink for each record (e.g. server push). */
    @FunctionalInterface
    public interface JudgementSink {
        void accept(JudgementRecord record) throws Exception;
    }

    private final String sessionId;
    private final Path file;
    private final ObjectMapper mapper;
    private final Object writeLock = new Object();
    private volatile JudgementSink sink;

    public JudgementLog(String sessionId, Path file) {
        this.sessionId = sessionId;
        this.file = file;
        // Compact (single-line) mapper — pretty-printing would break the JSONL framing.
        this.mapper = JsonUtils.newStandardMapper();
    }

    /** Create a log for the given session under {@code ~/.kompile/sessions/<id>/judgements.jsonl}. */
    public static JudgementLog forSession(String sessionId) {
        return new JudgementLog(sessionId, fileFor(sessionId));
    }

    public static Path sessionsRoot() {
        return Path.of(System.getProperty("user.home"), ".kompile", "sessions");
    }

    public static Path sessionDir(String sessionId) {
        return sessionsRoot().resolve(sessionId);
    }

    public static Path fileFor(String sessionId) {
        return sessionDir(sessionId).resolve("judgements.jsonl");
    }

    public String getSessionId() {
        return sessionId;
    }

    public Path getFile() {
        return file;
    }

    public void setSink(JudgementSink sink) {
        this.sink = sink;
    }

    /**
     * Append one record. Best-effort: never throws. Stamps timestamp/sessionId and clamps
     * over-long excerpts before writing.
     */
    public void record(JudgementRecord record) {
        if (record == null) {
            return;
        }
        if (record.getTimestamp() == null) {
            record.setTimestamp(Instant.now().toString());
        }
        if (record.getSessionId() == null) {
            record.setSessionId(sessionId);
        }
        record.setUserPromptExcerpt(clamp(record.getUserPromptExcerpt(), MAX_EXCERPT_CHARS));
        record.setAgentOutputExcerpt(clamp(record.getAgentOutputExcerpt(), MAX_EXCERPT_CHARS));
        record.setJudgeRawResponse(clamp(record.getJudgeRawResponse(), MAX_RAW_CHARS));

        try {
            String line = mapper.writeValueAsString(record);
            synchronized (writeLock) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (Exception e) {
            // Logging must never break enforcement.
        }

        JudgementSink s = this.sink;
        if (s != null) {
            try {
                s.accept(record);
            } catch (Exception ignored) {
                // best-effort mirror
            }
        }
    }

    // ── Read helpers (CLI / tooling) ────────────────────────────────────────

    /** Read all judgement records for a session, oldest first. Returns empty if none. */
    public static List<JudgementRecord> readAll(String sessionId) {
        return readFile(fileFor(sessionId));
    }

    public static List<JudgementRecord> readFile(Path file) {
        List<JudgementRecord> out = new ArrayList<>();
        if (file == null || !Files.exists(file)) {
            return out;
        }
        ObjectMapper mapper = JsonUtils.newStandardMapper();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    out.add(mapper.readValue(line, JudgementRecord.class));
                } catch (Exception ignored) {
                    // skip a malformed line rather than abort the whole read
                }
            }
        } catch (IOException ignored) {
            // return what we have
        }
        return out;
    }

    /** List session ids that have a judgements log, most-recently-modified first. */
    public static List<String> listSessionsWithJudgements() {
        Path root = sessionsRoot();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> dirs = Files.list(root)) {
            return dirs
                    .filter(Files::isDirectory)
                    .filter(d -> Files.exists(d.resolve("judgements.jsonl")))
                    .sorted(Comparator.comparingLong(JudgementLog::lastModified).reversed())
                    .map(d -> d.getFileName().toString())
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static long lastModified(Path dir) {
        try {
            return Files.getLastModifiedTime(dir.resolve("judgements.jsonl")).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static String clamp(String s, int max) {
        if (s == null) {
            return null;
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "…(" + (s.length() - max) + " more)";
    }
}
