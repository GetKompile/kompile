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
import picocli.CommandLine;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * View the durable judgement log recorded by enforced sessions:
 * {@code kompile enforcer judgements [sessionId] [--follow] [--list] [--json]}.
 *
 * <p>This is how an operator "goes into" a managed judge/enforcer session — including the raw
 * LLM judge transcript captured per call. {@code --follow} tails the log live as new judgements
 * are made.</p>
 */
@CommandLine.Command(
        name = "judgements",
        aliases = {"judgments", "judge-log"},
        description = "List, show, or follow the judgements recorded by enforced sessions",
        mixinStandardHelpOptions = true
)
public class EnforcerJudgementsCommand implements Callable<Integer> {

    @CommandLine.Parameters(index = "0", arity = "0..1",
            description = "Session id (default: most recent session with judgements)")
    String sessionArg;

    @CommandLine.Option(names = {"--session", "-s"}, description = "Session id (overrides positional)")
    String session;

    @CommandLine.Option(names = {"--list", "-l"}, description = "List all sessions that have judgements",
            defaultValue = "false")
    boolean list;

    @CommandLine.Option(names = {"--follow", "-f"}, description = "Follow the log live (Ctrl+C to stop)",
            defaultValue = "false")
    boolean follow;

    @CommandLine.Option(names = {"--json"}, description = "Print raw JSON lines instead of formatted output",
            defaultValue = "false")
    boolean json;

    @CommandLine.Option(names = {"--limit"}, description = "Only show the last N records (0 = all)",
            defaultValue = "0")
    int limit;

    @CommandLine.Option(names = {"--raw"}, description = "Include the full raw judge response per record",
            defaultValue = "false")
    boolean raw;

    @Override
    public Integer call() {
        if (list) {
            return listSessions();
        }
        String sid = resolveSession();
        if (sid == null) {
            System.out.println("No enforcer judgements found yet. Run an enforced session "
                    + "(kompile chat → passthrough → enable enforcement, or kompile enforcer).");
            return 0;
        }
        return show(sid, json, raw, limit, follow);
    }

    /** Resolve which session to view: --session > positional > most-recent. */
    private String resolveSession() {
        if (session != null && !session.isBlank()) {
            return session.trim();
        }
        if (sessionArg != null && !sessionArg.isBlank()) {
            return sessionArg.trim();
        }
        List<String> sessions = JudgementLog.listSessionsWithJudgements();
        return sessions.isEmpty() ? null : sessions.get(0);
    }

    // ── Shared rendering (reused by EnforcerAttachCommand) ──────────────────

    static int listSessions() {
        List<String> sessions = JudgementLog.listSessionsWithJudgements();
        if (sessions.isEmpty()) {
            System.out.println("No enforcer judgements found yet.");
            return 0;
        }
        System.out.println("Enforced sessions with recorded judgements (most recent first):");
        System.out.println();
        System.out.printf("  %-26s %7s  %-10s  %-22s  %s%n",
                "SESSION", "RECORDS", "LAST", "BACKEND", "WHEN");
        for (String sid : sessions) {
            List<JudgementRecord> recs = JudgementLog.readAll(sid);
            JudgementRecord last = recs.isEmpty() ? null : recs.get(recs.size() - 1);
            String lastStatus = last == null ? "-"
                    : (last.getStatus() != null ? last.getStatus() : last.getPhase());
            String backend = last == null || last.getBackend() == null ? "-" : last.getBackend();
            String when = last == null ? "-" : shortTime(last.getTimestamp());
            System.out.printf("  %-26s %7d  %-10s  %-22s  %s%n",
                    sid, recs.size(), trim(lastStatus, 10), trim(backend, 22), when);
        }
        System.out.println();
        System.out.println("View one with: kompile enforcer judgements <session> [--follow] [--raw]");
        return 0;
    }

    static int show(String sessionId, boolean json, boolean raw, int limit, boolean follow) {
        List<JudgementRecord> records = JudgementLog.readAll(sessionId);
        int start = (limit > 0 && records.size() > limit) ? records.size() - limit : 0;
        if (!json) {
            System.out.println("Judgements for session " + sessionId
                    + "  (" + records.size() + " records)  →  " + JudgementLog.fileFor(sessionId));
            System.out.println();
        }
        for (int i = start; i < records.size(); i++) {
            printRecord(records.get(i), json, raw);
        }
        if (follow) {
            return followLoop(sessionId, records.size(), json, raw);
        }
        return 0;
    }

    /** Poll the log for new records and print them as they arrive. Returns on interrupt. */
    static int followLoop(String sessionId, int alreadyPrinted, boolean json, boolean raw) {
        if (!json) {
            System.out.println();
            System.out.println("— following live (Ctrl+C to stop) —");
        }
        int printed = alreadyPrinted;
        try {
            while (!Thread.currentThread().isInterrupted()) {
                List<JudgementRecord> records = JudgementLog.readAll(sessionId);
                for (int i = printed; i < records.size(); i++) {
                    printRecord(records.get(i), json, raw);
                }
                printed = Math.max(printed, records.size());
                Thread.sleep(500);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return 0;
    }

    private static final ObjectMapper JSON = JsonUtils.newStandardMapper();

    static void printRecord(JudgementRecord r, boolean json, boolean raw) {
        if (json) {
            try {
                System.out.println(JSON.writeValueAsString(r));
            } catch (Exception ignored) {
            }
            return;
        }
        String symbol = r.isCompliant() ? "✓" : (r.isStop() ? "■" : "✗");
        String headline = r.getStatus() != null ? r.getStatus()
                : (r.getSeverity() != null ? r.getSeverity() : "");
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[%s] %-13s", shortTime(r.getTimestamp()), r.getPhase()));
        if (r.getAttempt() > 0) {
            sb.append(" a").append(r.getAttempt());
        }
        sb.append("  ").append(r.getJudgeMode() == null ? "" : r.getJudgeMode());
        if (r.getLatencyMs() > 0) {
            sb.append("  ").append(r.getLatencyMs()).append("ms");
        }
        sb.append("  ").append(symbol);
        if (!headline.isBlank()) {
            sb.append(' ').append(headline);
        }
        if (r.getBackend() != null && !r.getBackend().isBlank()) {
            sb.append("  [").append(r.getBackend()).append(']');
        }
        if (r.getToolName() != null && !r.getToolName().isBlank()) {
            sb.append("  tool=").append(r.getToolName());
        }
        System.out.println(sb);

        if (r.getViolations() != null && !r.getViolations().isEmpty()) {
            System.out.println("     violations: " + String.join("; ", r.getViolations()));
        }
        if (r.getReasoning() != null && !r.getReasoning().isBlank()) {
            System.out.println("     reasoning:  " + trim(r.getReasoning(), 200));
        }
        if (r.getCorrectionPrompt() != null && !r.getCorrectionPrompt().isBlank()) {
            System.out.println("     correction: " + trim(r.getCorrectionPrompt(), 200));
        }
        if (r.getJudgeRawResponse() != null && !r.getJudgeRawResponse().isBlank()) {
            String judgeSaid = raw ? r.getJudgeRawResponse() : trim(r.getJudgeRawResponse(), 200);
            System.out.println("     judge said: " + judgeSaid);
        }
    }

    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    static String shortTime(String iso) {
        if (iso == null || iso.isBlank()) {
            return "--:--:--";
        }
        try {
            return HMS.format(Instant.parse(iso));
        } catch (Exception e) {
            return iso.length() > 8 ? iso.substring(0, 8) : iso;
        }
    }

    static String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        String oneLine = s.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "…";
    }
}
