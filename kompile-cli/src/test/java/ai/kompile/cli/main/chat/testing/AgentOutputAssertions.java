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

package ai.kompile.cli.main.chat.testing;

import ai.kompile.cli.main.chat.PassthroughStreamParser.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Fluent assertion helper for testing lists of {@link PassthroughEvent} objects.
 * <p>
 * Wraps a {@code List<PassthroughEvent>} and provides chainable assertion methods
 * that throw {@link AssertionError} with descriptive messages on failure.
 * <p>
 * Usage:
 * <pre>
 *   AgentOutputAssertions.assertThat(events)
 *       .isNotEmpty()
 *       .hasTextContaining("hello")
 *       .hasToolUse("read")
 *       .hasNoErrors();
 * </pre>
 */
public class AgentOutputAssertions {

    private final List<PassthroughEvent> events;

    private AgentOutputAssertions(List<PassthroughEvent> events) {
        this.events = new ArrayList<>(events);
    }

    /**
     * Create assertions for a list of events.
     */
    public static AgentOutputAssertions assertThat(List<PassthroughEvent> events) {
        if (events == null) {
            throw new AssertionError("Expected non-null event list but got null");
        }
        return new AgentOutputAssertions(events);
    }

    /**
     * Create assertions wrapping a single event.
     */
    public static AgentOutputAssertions assertThat(PassthroughEvent event) {
        if (event == null) {
            return new AgentOutputAssertions(List.of());
        }
        return new AgentOutputAssertions(List.of(event));
    }

    // =========================================================================
    // Count assertions
    // =========================================================================

    /**
     * Assert the event list has exactly the given number of events.
     */
    public AgentOutputAssertions hasEventCount(int expected) {
        if (events.size() != expected) {
            throw new AssertionError(
                    "Expected " + expected + " event(s) but found " + events.size()
                    + ". Events were: " + formatEvents());
        }
        return this;
    }

    /**
     * Assert the event list is empty.
     */
    public AgentOutputAssertions isEmpty() {
        if (!events.isEmpty()) {
            throw new AssertionError(
                    "Expected empty event list but found " + events.size()
                    + " event(s): " + formatEvents());
        }
        return this;
    }

    /**
     * Assert the event list is not empty.
     */
    public AgentOutputAssertions isNotEmpty() {
        if (events.isEmpty()) {
            throw new AssertionError("Expected non-empty event list but it was empty");
        }
        return this;
    }

    // =========================================================================
    // Type-specific presence assertions
    // =========================================================================

    /**
     * Assert at least one {@link TextChunk} with exactly the given text exists.
     */
    public AgentOutputAssertions hasTextChunk(String expectedText) {
        boolean found = events.stream()
                .filter(e -> e instanceof TextChunk)
                .map(e -> (TextChunk) e)
                .anyMatch(e -> expectedText.equals(e.text()));
        if (!found) {
            throw new AssertionError(
                    "Expected TextChunk with text '" + expectedText
                    + "' but events were: " + formatEvents());
        }
        return this;
    }

    /**
     * Assert at least one {@link TextChunk} whose text contains the given substring exists.
     */
    public AgentOutputAssertions hasTextContaining(String substring) {
        boolean found = events.stream()
                .filter(e -> e instanceof TextChunk)
                .map(e -> (TextChunk) e)
                .anyMatch(e -> e.text() != null && e.text().contains(substring));
        if (!found) {
            throw new AssertionError(
                    "Expected a TextChunk containing '" + substring
                    + "' but events were: " + formatEvents());
        }
        return this;
    }

    /**
     * Assert at least one {@link ToolUse} with the given name exists.
     */
    public AgentOutputAssertions hasToolUse(String name) {
        boolean found = events.stream()
                .filter(e -> e instanceof ToolUse)
                .map(e -> (ToolUse) e)
                .anyMatch(e -> name.equals(e.name()));
        if (!found) {
            throw new AssertionError(
                    "Expected ToolUse with name '" + name
                    + "' but events were: " + formatEvents());
        }
        return this;
    }

    /**
     * Assert at least one {@link ToolUse} with the given name and input exists.
     */
    public AgentOutputAssertions hasToolUseWithInput(String name, String input) {
        boolean found = events.stream()
                .filter(e -> e instanceof ToolUse)
                .map(e -> (ToolUse) e)
                .anyMatch(e -> name.equals(e.name()) && input.equals(e.input()));
        if (!found) {
            throw new AssertionError(
                    "Expected ToolUse with name '" + name + "' and input '" + input
                    + "' but events were: " + formatEvents());
        }
        return this;
    }

    /**
     * Assert at least one {@link ToolComplete} with the given name and exit code exists.
     */
    public AgentOutputAssertions hasToolComplete(String name, int exitCode) {
        boolean found = events.stream()
                .filter(e -> e instanceof ToolComplete)
                .map(e -> (ToolComplete) e)
                .anyMatch(e -> name.equals(e.name()) && e.exitCode() == exitCode);
        if (!found) {
            throw new AssertionError(
                    "Expected ToolComplete with name '" + name + "' and exitCode=" + exitCode
                    + " but events were: " + formatEvents());
        }
        return this;
    }

    /**
     * Assert at least one {@link ToolComplete} with the given name, exitCode=0, and error=false exists.
     */
    public AgentOutputAssertions hasToolCompleteWithNoError(String name) {
        boolean found = events.stream()
                .filter(e -> e instanceof ToolComplete)
                .map(e -> (ToolComplete) e)
                .anyMatch(e -> name.equals(e.name()) && e.exitCode() == 0 && !e.error());
        if (!found) {
            throw new AssertionError(
                    "Expected ToolComplete with name '" + name + "', exitCode=0, error=false"
                    + " but events were: " + formatEvents());
        }
        return this;
    }

    /**
     * Assert at least one {@link ToolOutput} with exactly the given output exists.
     */
    public AgentOutputAssertions hasToolOutput(String output) {
        boolean found = events.stream()
                .filter(e -> e instanceof ToolOutput)
                .map(e -> (ToolOutput) e)
                .anyMatch(e -> output.equals(e.output()));
        if (!found) {
            throw new AssertionError(
                    "Expected ToolOutput with output '" + output
                    + "' but events were: " + formatEvents());
        }
        return this;
    }

    /**
     * Assert at least one {@link SessionInit} with the given session ID exists.
     */
    public AgentOutputAssertions hasSessionInit(String sessionId) {
        boolean found = events.stream()
                .filter(e -> e instanceof SessionInit)
                .map(e -> (SessionInit) e)
                .anyMatch(e -> sessionId.equals(e.sessionId()));
        if (!found) {
            throw new AssertionError(
                    "Expected SessionInit with sessionId '" + sessionId
                    + "' but events were: " + formatEvents());
        }
        return this;
    }

    /**
     * Assert at least one {@link TurnComplete} event exists.
     */
    public AgentOutputAssertions hasTurnComplete() {
        boolean found = events.stream().anyMatch(e -> e instanceof TurnComplete);
        if (!found) {
            throw new AssertionError(
                    "Expected at least one TurnComplete event but events were: " + formatEvents());
        }
        return this;
    }

    /**
     * Assert at least one {@link TokenUsage} with the given input and output token counts exists.
     */
    public AgentOutputAssertions hasTokenUsage(long input, long output) {
        boolean found = events.stream()
                .filter(e -> e instanceof TokenUsage)
                .map(e -> (TokenUsage) e)
                .anyMatch(e -> e.inputTokens() == input && e.outputTokens() == output);
        if (!found) {
            throw new AssertionError(
                    "Expected TokenUsage with inputTokens=" + input + ", outputTokens=" + output
                    + " but events were: " + formatEvents());
        }
        return this;
    }

    /**
     * Assert at least one {@link TokenUsage} with the given token counts (including cache) exists.
     */
    public AgentOutputAssertions hasTokenUsageWithCache(long input, long output,
                                                         long cacheRead, long cacheWrite) {
        boolean found = events.stream()
                .filter(e -> e instanceof TokenUsage)
                .map(e -> (TokenUsage) e)
                .anyMatch(e -> e.inputTokens() == input
                        && e.outputTokens() == output
                        && e.cacheReadTokens() == cacheRead
                        && e.cacheCreationTokens() == cacheWrite);
        if (!found) {
            throw new AssertionError(
                    "Expected TokenUsage with inputTokens=" + input + ", outputTokens=" + output
                    + ", cacheReadTokens=" + cacheRead + ", cacheCreationTokens=" + cacheWrite
                    + " but events were: " + formatEvents());
        }
        return this;
    }

    // =========================================================================
    // Absence assertions
    // =========================================================================

    /**
     * Assert no {@link ToolUse} events exist.
     */
    public AgentOutputAssertions hasNoToolUse() {
        List<ToolUse> toolUses = events.stream()
                .filter(e -> e instanceof ToolUse)
                .map(e -> (ToolUse) e)
                .collect(Collectors.toList());
        if (!toolUses.isEmpty()) {
            throw new AssertionError(
                    "Expected no ToolUse events but found: " + toolUses
                    + ". All events were: " + formatEvents());
        }
        return this;
    }

    /**
     * Assert no {@link ToolComplete} events with {@code error=true} exist.
     */
    public AgentOutputAssertions hasNoErrors() {
        List<ToolComplete> errors = events.stream()
                .filter(e -> e instanceof ToolComplete)
                .map(e -> (ToolComplete) e)
                .filter(ToolComplete::error)
                .collect(Collectors.toList());
        if (!errors.isEmpty()) {
            throw new AssertionError(
                    "Expected no ToolComplete events with error=true but found: " + errors
                    + ". All events were: " + formatEvents());
        }
        return this;
    }

    // =========================================================================
    // Positional assertions
    // =========================================================================

    /**
     * Assert the event at the given index is of the specified type.
     */
    public AgentOutputAssertions eventAt(int index, Class<? extends PassthroughEvent> type) {
        if (index < 0 || index >= events.size()) {
            throw new AssertionError(
                    "Expected event at index " + index + " to be of type " + type.getSimpleName()
                    + " but event list has only " + events.size() + " event(s): " + formatEvents());
        }
        PassthroughEvent event = events.get(index);
        if (!type.isInstance(event)) {
            throw new AssertionError(
                    "Expected event at index " + index + " to be " + type.getSimpleName()
                    + " but was " + event.getClass().getSimpleName()
                    + ". Events were: " + formatEvents());
        }
        return this;
    }

    /**
     * Assert the first event is of the specified type.
     */
    public AgentOutputAssertions firstEventIs(Class<? extends PassthroughEvent> type) {
        if (events.isEmpty()) {
            throw new AssertionError(
                    "Expected first event to be " + type.getSimpleName()
                    + " but event list is empty");
        }
        return eventAt(0, type);
    }

    /**
     * Assert the last event is of the specified type.
     */
    public AgentOutputAssertions lastEventIs(Class<? extends PassthroughEvent> type) {
        if (events.isEmpty()) {
            throw new AssertionError(
                    "Expected last event to be " + type.getSimpleName()
                    + " but event list is empty");
        }
        return eventAt(events.size() - 1, type);
    }

    // =========================================================================
    // Sequence assertions
    // =========================================================================

    /**
     * Assert the sequence of {@link ToolUse} event names matches exactly (in order).
     */
    public AgentOutputAssertions toolSequenceIs(String... names) {
        List<String> actualNames = events.stream()
                .filter(e -> e instanceof ToolUse)
                .map(e -> ((ToolUse) e).name())
                .collect(Collectors.toList());
        List<String> expectedNames = Arrays.asList(names);
        if (!actualNames.equals(expectedNames)) {
            throw new AssertionError(
                    "Expected ToolUse sequence " + expectedNames
                    + " but actual ToolUse names were: " + actualNames
                    + ". All events were: " + formatEvents());
        }
        return this;
    }

    // =========================================================================
    // Raw access
    // =========================================================================

    /**
     * Return the underlying event list for custom assertions.
     */
    public List<PassthroughEvent> events() {
        return List.copyOf(events);
    }

    /**
     * Return the first event of the given type, or {@code null} if none exists.
     */
    @SuppressWarnings("unchecked")
    public <T extends PassthroughEvent> T firstOfType(Class<T> type) {
        return events.stream()
                .filter(type::isInstance)
                .map(e -> (T) e)
                .findFirst()
                .orElse(null);
    }

    /**
     * Return all events of the given type (may be empty).
     */
    @SuppressWarnings("unchecked")
    public <T extends PassthroughEvent> List<T> allOfType(Class<T> type) {
        return events.stream()
                .filter(type::isInstance)
                .map(e -> (T) e)
                .collect(Collectors.toList());
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    private String formatEvents() {
        return events.stream()
                .map(Object::toString)
                .collect(Collectors.joining(", ", "[", "]"));
    }
}
