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

import ai.kompile.core.llm.chat.LLMChat;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM-backed {@link AnswerClaimExtractor} for the grounded-answer verification loop (grounded-RAG rec
 * 1). Runs one focused extraction call — the answer is turned into KB atom keys
 * ({@code predicate(subject, object)}) that {@code KbGroundingService.verify} can check. Kept separate
 * from {@code GroundedAnswerVerifier} (which stays LLM-free + unit-testable); when no {@link LLMChat}
 * bean is wired this extractor returns nothing and the verification loop is a graceful no-op.
 */
@Component
public class LlmAnswerClaimExtractor implements AnswerClaimExtractor {

    private static final Logger log = LoggerFactory.getLogger(LlmAnswerClaimExtractor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired(required = false)
    private LLMChat llmChat;

    @Override
    public List<String> extractClaimAtoms(String answerText, long factSheetId, int maxClaims) {
        if (llmChat == null || answerText == null || answerText.isBlank() || maxClaims <= 0) {
            return List.of();
        }
        try {
            String response = llmChat.prompt(buildPrompt(answerText, maxClaims)).call().content();
            return parseAtomKeys(response, maxClaims);
        } catch (Exception e) {
            log.debug("LlmAnswerClaimExtractor: extraction failed (non-fatal) — {}", e.getMessage());
            return List.of();
        }
    }

    private static String buildPrompt(String answerText, int maxClaims) {
        return "Extract up to " + maxClaims + " concrete factual claims asserted in the ANSWER below, "
                + "as knowledge-base atom keys of the form predicate(subject, object) — a lowercase "
                + "snake_case predicate with entity names as arguments. Include only claims that assert a "
                + "relationship or attribute; skip questions, hedged/opinion/meta statements, and anything "
                + "not a checkable fact. Respond with ONLY a JSON array of strings, for example "
                + "[\"works_at(alice, acme)\", \"headquartered_in(acme, seattle)\"].\n\nANSWER:\n"
                + answerText;
    }

    /**
     * Parse the LLM response into atom keys. Pure + static so it is unit-testable without an LLM.
     * Tolerates surrounding prose / code fences (extracts the first JSON array) and accepts either an
     * array of strings or an array of {@code {"atom": "..."}} objects. Keeps only atom-shaped entries
     * ({@code pred(...)}), trimmed and de-duplicated, capped at {@code max}.
     */
    static List<String> parseAtomKeys(String llmResponse, int max) {
        if (llmResponse == null || llmResponse.isBlank() || max <= 0) {
            return List.of();
        }
        int start = llmResponse.indexOf('[');
        int end = llmResponse.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        try {
            JsonNode arr = MAPPER.readTree(llmResponse.substring(start, end + 1));
            if (!arr.isArray()) {
                return List.of();
            }
            for (JsonNode n : arr) {
                if (out.size() >= max) {
                    break;
                }
                String atom = n.isTextual() ? n.asText()
                        : (n.isObject() && n.hasNonNull("atom") ? n.get("atom").asText() : null);
                if (atom == null) {
                    continue;
                }
                atom = atom.trim();
                if (!atom.isEmpty() && atom.contains("(") && atom.endsWith(")") && !out.contains(atom)) {
                    out.add(atom);
                }
            }
        } catch (Exception e) {
            log.debug("LlmAnswerClaimExtractor: could not parse claim JSON — {}", e.getMessage());
            return List.of();
        }
        return out;
    }
}
