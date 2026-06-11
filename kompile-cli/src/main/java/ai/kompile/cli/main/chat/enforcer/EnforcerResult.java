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

import java.util.ArrayList;
import java.util.List;

/**
 * Final outcome from an enforcer-controlled subordinate agent turn.
 */
public class EnforcerResult {

    public enum Status {
        ACCEPTED,
        BLOCKED,
        UNAVAILABLE,
        ERROR
    }

    public record Attempt(int number, String output, EnforcerDecision decision) {}

    private final Status status;
    private final String finalOutput;
    private final List<Attempt> attempts;
    private final String message;
    private final String judgeBackend;

    public EnforcerResult(Status status, String finalOutput,
                          List<Attempt> attempts, String message,
                          String judgeBackend) {
        this.status = status;
        this.finalOutput = finalOutput != null ? finalOutput : "";
        this.attempts = attempts != null ? List.copyOf(attempts) : List.of();
        this.message = message != null ? message : "";
        this.judgeBackend = judgeBackend != null ? judgeBackend : "";
    }

    public Status getStatus() {
        return status;
    }

    public String getFinalOutput() {
        return finalOutput;
    }

    public List<Attempt> getAttempts() {
        return attempts;
    }

    public String getMessage() {
        return message;
    }

    public String getJudgeBackend() {
        return judgeBackend;
    }

    public boolean isAccepted() {
        return status == Status.ACCEPTED;
    }

    public static EnforcerResult accepted(String output, List<Attempt> attempts, String backend) {
        return new EnforcerResult(Status.ACCEPTED, output, attempts, "accepted", backend);
    }

    public static EnforcerResult blocked(String output, List<Attempt> attempts, String message, String backend) {
        return new EnforcerResult(Status.BLOCKED, output, attempts, message, backend);
    }

    public static EnforcerResult unavailable(String message, String backend) {
        return new EnforcerResult(Status.UNAVAILABLE, "", List.of(), message, backend);
    }

    public static EnforcerResult error(String message, List<Attempt> attempts, String backend) {
        return new EnforcerResult(Status.ERROR, "", attempts, message, backend);
    }

    public String toMarkdown(boolean includeAttempts) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Enforcer ").append(status.name().toLowerCase()).append("\n\n");
        if (!judgeBackend.isBlank()) {
            sb.append("Judge backend: `").append(judgeBackend).append("`\n");
        }
        sb.append("Attempts: ").append(attempts.size()).append("\n");
        if (!message.isBlank()) {
            sb.append("Message: ").append(message).append("\n");
        }

        Attempt last = attempts.isEmpty() ? null : attempts.get(attempts.size() - 1);
        if (last != null && last.decision() != null) {
            EnforcerDecision decision = last.decision();
            if (!decision.getReasoning().isBlank()) {
                sb.append("Reasoning: ").append(decision.getReasoning()).append("\n");
            }
            if (!decision.getViolations().isEmpty()) {
                sb.append("\nViolations:\n");
                for (String violation : decision.getViolations()) {
                    sb.append("- ").append(violation).append("\n");
                }
            }
        }

        if (!finalOutput.isBlank()) {
            sb.append("\n## Final Output\n\n").append(finalOutput.trim()).append("\n");
        }

        if (includeAttempts && !attempts.isEmpty()) {
            sb.append("\n## Enforcement Attempts\n");
            for (Attempt attempt : attempts) {
                sb.append("\n### Attempt ").append(attempt.number()).append("\n");
                EnforcerDecision decision = attempt.decision();
                if (decision != null) {
                    sb.append("Compliant: ").append(decision.isCompliant()).append("\n");
                    if (!decision.getViolations().isEmpty()) {
                        sb.append("Violations: ").append(String.join("; ", decision.getViolations())).append("\n");
                    }
                    if (!decision.getCorrectionPrompt().isBlank()) {
                        sb.append("Correction: ").append(decision.getCorrectionPrompt()).append("\n");
                    }
                }
            }
        }
        return sb.toString();
    }

    static List<Attempt> copyAppend(List<Attempt> attempts, Attempt attempt) {
        List<Attempt> copy = new ArrayList<>(attempts);
        copy.add(attempt);
        return copy;
    }
}
