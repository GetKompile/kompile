package ai.kompile.pipelines.framework.api.context;

import ai.kompile.pipelines.framework.api.data.Data;

import java.util.Map;
import java.util.Optional;

/**
 * Provides execution context for a pipeline or a pipeline step.
 * It can hold shared state accessible during a single pipeline execution,
 * provide access to metrics and profiling tools, and manage other runtime aspects.
 * Context instances are typically hierarchical and specific to an execution flow.
 */
public interface Context {

    /**
     * Stores a value in the context's current scope.
     * This can be used to pass information between non-adjacent steps or
     * to make globally available resources (scoped to this execution) accessible.
     *
     * @param key The key under which to store the value. Must not be null.
     * @param value The value to store. Can be null.
     * @return This Context instance for fluent chaining.
     */
    Context put(String key, Object value);

    /**
     * Retrieves a value from the context's current scope.
     *
     * @param key The key of the value to retrieve. Must not be null.
     * @param <T> The expected type of the value.
     * @return An {@link Optional} containing the value if present and assignable to T,
     * otherwise an empty Optional.
     */
    <T> Optional<T> get(String key, Class<T> type);

    /**
     * Retrieves a value from the context's current scope, or a default value if not found or not assignable.
     *
     * @param key The key of the value to retrieve. Must not be null.
     * @param type The expected type of the value.
     * @param defaultValue The default value to return if the key is not found,
     * its value is null, or if the type does not match.
     * @param <T> The expected type of the value.
     * @return The value or the defaultValue.
     */
    <T> T getOrDefault(String key, Class<T> type, T defaultValue);

    /**
     * Removes a value from the context's current scope.
     * @param key The key to remove. Must not be null.
     * @return The removed value (as Object), or null if the key was not found in the current scope.
     */
    Object remove(String key);

    /**
     * Checks if the context's current scope contains a specific key.
     * @param key The key to check for. Must not be null.
     * @return True if the key exists in the current scope, false otherwise.
     */
    boolean containsKey(String key);

    /**
     * Returns an unmodifiable map of all key-value pairs stored in this context's current scope.
     * @return An unmodifiable map of all context entries in the current scope.
     */
    Map<String, Object> entries();

    /**
     * Gets the {@link Metrics} system associated with this context.
     * @return The Metrics instance. Never null (could be a No-Op implementation).
     */
    Metrics metrics();

    /**
     * Gets the {@link Profiler} associated with this context.
     * @return The Profiler instance. Never null (could be a No-Op implementation).
     */
    Profiler profiler();

    /**
     * Returns the initial {@link Data} object that was provided as input to the root of the pipeline execution.
     * This allows steps deep in the pipeline to access original request parameters or metadata.
     * The returned Data object should be treated as immutable or a defensive copy should be returned.
     * @return The original input Data object for the current pipeline execution. Never null.
     */
    Data originalInput();

    /**
     * Returns an ID for the current pipeline execution, if available.
     * This can be useful for tracing, logging, and correlating events.
     * @return An optional execution ID.
     */
    Optional<String> executionId();

    /**
     * Creates a child context, typically for a sub-scope like a specific step or a nested pipeline.
     * Child contexts may inherit properties (like executionId, originalInput, metrics/profiler instances)
     * from their parent but can also have their own isolated entries.
     *
     * @param name A descriptive name for the child context, often related to a step name or ID. Must not be null.
     * @return A new child Context.
     */
    Context child(String name);
}