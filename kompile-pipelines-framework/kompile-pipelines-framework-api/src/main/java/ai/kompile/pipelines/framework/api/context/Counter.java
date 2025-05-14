package ai.kompile.pipelines.framework.api.context;

/**
 * Represents a counter metric, a value that can only be incremented.
 * Useful for tracking occurrences of events, such as the number of items processed or errors.
 */
public interface Counter {
    /**
     * Increments the counter by 1.
     */
    void increment();

    /**
     * Increments the counter by the given non-negative amount.
     *
     * @param amount The amount to increment by. Must be non-negative.
     * @throws IllegalArgumentException if amount is negative.
     */
    void increment(double amount);

    /**
     * Gets the current count.
     *
     * @return The current value of the counter.
     */
    double count();

    /**
     * Gets the name of this counter.
     * @return The counter name.
     */
    String name();

    /**
     * Gets the help/description text for this counter.
     * @return The help text, or an empty string if none.
     */
    String help();

    /**
     * Gets the tags associated with this counter.
     * @return An array of strings representing key-value pairs (key1, value1, key2, value2,...).
     * Returns an empty array if no tags.
     */
    String[] tags();
}