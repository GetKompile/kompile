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

import ai.kompile.utils.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Structured post-run feedback verdict from the judge.
 */
public class PostFeedbackDecision {

    public enum Status {
        PASS,
        WARN,
        FAIL
    }

    private final Status status;
    private final double score;
    private final List<String> findings;
    private final List<String> evidence;
    private final List<String> nextActions;
    private final String correctionPrompt;
    private final String reasoning;

    public PostFeedbackDecision(Status status, double score, List<String> findings,
                                List<String> evidence, List<String> nextActions,
                                String correctionPrompt, String reasoning) {
        this.status = status != null ? status : Status.FAIL;
        this.score = score;
        this.findings = findings != null ? List.copyOf(findings) : List.of();
        this.evidence = evidence != null ? List.copyOf(evidence) : List.of();
        this.nextActions = nextActions != null ? List.copyOf(nextActions) : List.of();
        this.correctionPrompt = correctionPrompt != null ? correctionPrompt.trim() : "";
        this.reasoning = reasoning != null ? reasoning.trim() : "";
    }

    public static PostFeedbackDecision parse(ObjectMapper mapper, String responseText) {
        String json = EnforcerDecision.extractJson(responseText);
        if (json == null) {
            return new PostFeedbackDecision(Status.FAIL, 0.0,
                    List.of("Judge did not return valid JSON"),
                    List.of(), List.of("Run the audit again or inspect evidence manually"),
                    "", responseText != null ? StringUtils.truncate(responseText, 240) : "");
        }

        try {
            JsonNode root = mapper.readTree(json);
            Status status = parseStatus(root.path("status").asText(root.path("verdict").asText("FAIL")));
            double score = root.path("score").asDouble(status == Status.PASS ? 1.0 : 0.0);
            return new PostFeedbackDecision(
                    status,
                    score,
                    strings(root.path("findings")),
                    strings(root.path("evidence")),
                    strings(root.path("next_actions").isMissingNode()
                            ? root.path("nextActions") : root.path("next_actions")),
                    root.path("correction_prompt").asText(root.path("correctionPrompt").asText("")),
                    root.path("reasoning").asText(""));
        } catch (Exception e) {
            return new PostFeedbackDecision(Status.FAIL, 0.0,
                    List.of("Judge JSON parse failed: " + e.getMessage()),
                    List.of(), List.of("Inspect the raw judge response manually"),
                    "", StringUtils.truncate(responseText, 240));
        }
    }

    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Post Feedback: ").append(status).append("\n\n");
        sb.append(String.format("Score: %.2f%n%n", score));

        appendList(sb, "Findings", findings);
        appendList(sb, "Evidence", evidence);
        appendList(sb, "Next Actions", nextActions);

        if (!correctionPrompt.isBlank()) {
            sb.append("## Correction Prompt\n\n")
                    .append(correctionPrompt)
                    .append("\n\n");
        }
        if (!reasoning.isBlank()) {
            sb.append("## Reasoning\n\n")
                    .append(reasoning)
                    .append("\n");
        }
        return sb.toString().trim();
    }

    public Status getStatus() {
        return status;
    }

    public double getScore() {
        return score;
    }

    public List<String> getFindings() {
        return findings;
    }

    public List<String> getEvidence() {
        return evidence;
    }

    public List<String> getNextActions() {
        return nextActions;
    }

    public String getCorrectionPrompt() {
        return correctionPrompt;
    }

    public String getReasoning() {
        return reasoning;
    }

    private static Status parseStatus(String value) {
        if (value == null || value.isBlank()) {
            return Status.FAIL;
        }
        try {
            return Status.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Status.FAIL;
        }
    }

    private static List<String> strings(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (node.isTextual()) {
            return node.asText().isBlank() ? List.of() : List.of(node.asText());
        }
        if (!node.isArray()) {
            return List.of(node.asText());
        }
        List<String> values = new ArrayList<>();
        node.forEach(item -> {
            String text = item.asText("");
            if (!text.isBlank()) {
                values.add(text);
            }
        });
        return values;
    }

    private static void appendList(StringBuilder sb, String title, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        sb.append("## ").append(title).append("\n\n");
        for (String value : values) {
            sb.append("- ").append(value).append("\n");
        }
        sb.append("\n");
    }

}
