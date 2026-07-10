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
package ai.kompile.app.services.agent;

import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.knowledgegraph.confidence.KbConfig;
import ai.kompile.knowledgegraph.confidence.KbConfigManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Citation contract (grounded-RAG lit-gap rec 4). When enabled, injects a numbered "Sources" block +
 * an instruction so the LLM anchors claims inline as {@code [n]}, keyed 1..N to the retrieved sources
 * in the SAME order the client renders them ({@code formatSourcesForClient}). Post-answer, the used
 * {@code [n]} indices are extracted so the UI can make them clickable and unattributed sentences can
 * feed the rec-1 verification loop. Opt-in via {@code kbCitationContractEnabled}.
 *
 * <p>v1 pairs each source number with a short preview so the model can map a claim to a source without
 * renumbering the main context blocks; precise sentence↔source alignment is a documented follow-on.</p>
 */
@Component
public class CitationContract {

    private static final Pattern CITATION = Pattern.compile("\\[(\\d{1,3})\\]");
    private static final int PREVIEW_CHARS = 90;

    @Autowired(required = false)
    private KbConfigManager kbConfigManager;

    public boolean isEnabled() {
        KbConfig c = kbConfigManager != null ? kbConfigManager.current() : KbConfig.defaults();
        return c.isCitationContractEnabled();
    }

    /**
     * A numbered Sources block (1..N over {@code sources}, matching the client source order) plus the
     * citation instruction, to append to the prompt. Returns "" for empty sources. Uses a display name
     * + short preview, never a raw id/path as the primary label (Part X). Pure — unit-testable.
     */
    public static String buildSourcesBlock(List<RetrievedDoc> sources) {
        if (sources == null || sources.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n## Sources\n")
                .append("When you state a fact drawn from the retrieved context, cite it inline as [n] ")
                .append("using the source number below. Cite only sources you actually used; never invent ")
                .append("a citation.\n");
        int i = 1;
        for (RetrievedDoc d : sources) {
            sb.append('[').append(i).append("] ").append(displayName(d, i));
            String preview = preview(d);
            if (!preview.isEmpty()) {
                sb.append(" — ").append(preview);
            }
            sb.append('\n');
            i++;
        }
        return sb.toString();
    }

    /** The distinct citation indices {@code [n]} actually used in an answer. Pure — unit-testable. */
    public static Set<Integer> extractCitationIndices(String answer) {
        Set<Integer> out = new LinkedHashSet<>();
        if (answer == null || answer.isEmpty()) {
            return out;
        }
        Matcher m = CITATION.matcher(answer);
        while (m.find()) {
            try {
                out.add(Integer.parseInt(m.group(1)));
            } catch (NumberFormatException ignored) {
                // group is 1-3 digits; unreachable in practice, kept defensive
            }
        }
        return out;
    }

    private static String displayName(RetrievedDoc d, int index) {
        if (d != null && d.getMetadata() != null) {
            for (String k : new String[]{"sourceName", "title", "name"}) {
                Object v = d.getMetadata().get(k);
                if (v instanceof String s && !s.isBlank()) {
                    return s.strip();
                }
            }
        }
        return "Source " + index;
    }

    private static String preview(RetrievedDoc d) {
        String text = d == null ? null : d.getText();
        if (text == null || text.isBlank()) {
            return "";
        }
        String flat = text.strip().replaceAll("\\s+", " ");
        return flat.length() <= PREVIEW_CHARS ? flat : flat.substring(0, PREVIEW_CHARS) + "…";
    }
}
