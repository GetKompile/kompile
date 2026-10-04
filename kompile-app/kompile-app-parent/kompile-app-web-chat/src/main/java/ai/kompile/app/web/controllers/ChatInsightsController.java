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

package ai.kompile.app.web.controllers;

import ai.kompile.app.services.agent.ChatInsightsService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Map;

/**
 * The chat app's insights page: one topic's report over every chat session (judge verdicts, tool
 * calls, test milestones, crawls, graphs, or the overview of them all), and the limits in
 * {@code insights.json} that every report keeps to.
 */
@RestController
@RequestMapping("/api/agents/chat/insights")
public class ChatInsightsController {

    private final ChatInsightsService insights;

    public ChatInsightsController(ChatInsightsService insights) {
        this.insights = insights;
    }

    /**
     * {@code menu}, {@code topic} and {@code available}, then {@code headline}, {@code text} and an
     * optional {@code chart}; or a {@code status} saying why the topic's data cannot be read.
     */
    @GetMapping
    public ResponseEntity<?> report(@RequestParam(required = false) String topic,
                                    @RequestParam(required = false) String question,
                                    @RequestParam(required = false) String workingDirectory) {
        try {
            return ResponseEntity.ok(insights.report(topic, question, workingDirectory));
        } catch (IllegalArgumentException invalid) {
            return badRequest(invalid, "Invalid insights request");
        } catch (IllegalStateException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, unavailable.getMessage(), unavailable);
        }
    }

    /** {@code file}, {@code settings}, {@code defaults}, and a {@code warning} when the file was ignored. */
    @GetMapping("/config")
    public JsonNode settings() {
        return insights.settings();
    }

    /** Changes the settings the body names and answers as {@link #settings()} does. */
    @PutMapping("/config")
    public ResponseEntity<?> saveSettings(@RequestBody(required = false) JsonNode changes) {
        try {
            return ResponseEntity.ok(insights.saveSettings(changes));
        } catch (IllegalArgumentException invalid) {
            return badRequest(invalid, "Invalid insights settings");
        } catch (IOException failed) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("ok", false,
                    "message", "Could not save insights settings: " + (failed.getMessage() == null
                            ? failed.getClass().getSimpleName() : failed.getMessage())));
        }
    }

    private static ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException invalid, String fallback) {
        String message = invalid.getMessage();
        return ResponseEntity.badRequest().body(Map.of("ok", false,
                "message", message == null || message.isBlank() ? fallback : message));
    }
}
