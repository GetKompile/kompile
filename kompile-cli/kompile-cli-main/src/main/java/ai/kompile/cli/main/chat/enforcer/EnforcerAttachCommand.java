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

import picocli.CommandLine;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * "Go into" a managed judge/enforcer session: {@code kompile enforcer attach [sessionId]}.
 *
 * <p>Today this is a <em>read-only</em> live observation of the session's judgements — including,
 * for an LLM-based judge, the raw prompt → response → decision stream. True interactive attach to
 * the persistent judge subprocess is intentionally deferred: that process runs in a headless JSON
 * turn loop, so injecting interactive input would corrupt it. The seam for it lives in
 * {@code CliJudgeBackend} → {@code PersistentAgentProcess}.</p>
 */
@CommandLine.Command(
        name = "attach",
        description = "Observe a managed judge/enforcer session live (read-only); LLM judge transcript included",
        mixinStandardHelpOptions = true
)
public class EnforcerAttachCommand implements Callable<Integer> {

    @CommandLine.Parameters(index = "0", arity = "0..1",
            description = "Session id to attach to (default: most recent)")
    String sessionId;

    @CommandLine.Option(names = {"--raw"}, description = "Show full raw judge responses",
            defaultValue = "false")
    boolean raw;

    @CommandLine.Option(names = {"--no-follow"}, description = "Print current judgements and exit (do not follow)",
            defaultValue = "false")
    boolean noFollow;

    @Override
    public Integer call() {
        String sid = sessionId;
        if (sid == null || sid.isBlank()) {
            List<String> sessions = JudgementLog.listSessionsWithJudgements();
            if (sessions.isEmpty()) {
                System.out.println("No enforced sessions found to attach to. Start one with "
                        + "kompile chat (passthrough → enable enforcement) or kompile enforcer.");
                return 0;
            }
            sid = sessions.get(0);
        }

        List<JudgementRecord> records = JudgementLog.readAll(sid);
        boolean llm = records.stream().anyMatch(r -> "llm".equalsIgnoreCase(r.getJudgeMode()));
        String backend = lastNonBlank(records, JudgementRecord::getBackend, "unknown");
        String lastWhen = records.isEmpty() ? "—"
                : EnforcerJudgementsCommand.shortTime(records.get(records.size() - 1).getTimestamp());

        System.out.println();
        System.out.println("\033[1m\033[36m  🔌 Attaching to enforced session " + sid + "\033[0m");
        System.out.println("     judge:    " + (llm ? "LLM-based" : "keyword (no live LLM judge)"));
        System.out.println("     backend:  " + backend);
        System.out.println("     records:  " + records.size() + "   last activity: " + lastWhen);
        System.out.println("     log:      " + JudgementLog.fileFor(sid));
        if (llm) {
            System.out.println("\033[2m     Observing the judge's prompt → response → decision stream (read-only).\033[0m");
        } else {
            System.out.println("\033[2m     Showing the keyword decision stream (read-only).\033[0m");
        }
        System.out.println("\033[2m     Interactive attach to the judge subprocess is not yet available.\033[0m");
        System.out.println();

        return EnforcerJudgementsCommand.show(sid, false, raw, 0, !noFollow);
    }

    private interface Field {
        String get(JudgementRecord r);
    }

    private static String lastNonBlank(List<JudgementRecord> records, Field field, String fallback) {
        for (int i = records.size() - 1; i >= 0; i--) {
            String v = field.get(records.get(i));
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return fallback;
    }
}
