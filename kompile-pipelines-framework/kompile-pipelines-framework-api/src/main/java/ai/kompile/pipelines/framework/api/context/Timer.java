package ai.kompile.pipelines.framework.api.context;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * Represents a timer metric, used to measure the duration of events.
 * Typically records both the number of times an event occurred and the total time spent.
 */
public interface Timer {

    /**
     * Represents an active timing sample.
     * Must be stopped to record the duration.
     */
    interface Sample {
        /**
         * Stops the timer sample and records the duration since it was started.
         * @return The duration in nanoseconds.
         */
        long stop();
    }

    /**
     * Starts a new timer sample.
     * Call {@link Sample#stop()} on the returned instance to record the duration.
     * This is useful for try-with-resources patterns if Sample implements AutoCloseable.
     *
     * @return A new {@link Sample} instance.
     */
    Sample start();

    /**
     * Records a manually measured duration.
     *
     * @param amount The duration amount.
     * @param unit The time unit of the amount.
     */
    void record(long amount, TimeUnit unit);

    /**
     * Executes the given {@link Callable} and records its duration.
     *
     * @param f The callable to execute and time.
     * @param <T> The return type of the callable.
     * @return The result of the callable.
     * @throws Exception If the callable throws an exception.
     */
    <T> T record(Callable<T> f) throws Exception;

    /**
     * Executes the given {@link Runnable} and records its duration.
     *
     * @param f The runnable to execute and time.
     */
    void record(Runnable f);

    /**
     * Gets the total accumulated time recorded by this timer, in the specified time unit.
     *
     * @param unit The desired time unit for the total time.
     * @return The total time.
     */
    long totalTime(TimeUnit unit);

    /**
     * Gets the number of times this timer has been recorded.
     *
     * @return The count of recorded events.
     */
    long count();

    /**
     * Gets the name of this timer.
     * @return The timer name.
     */
    String name();

    /**
     * Gets the help/description text for this timer.
     * @return The help text, or an empty string if none.
     */
    String help();

    /**
     * Gets the tags associated with this timer.
     * @return An array of strings representing key-value pairs (key1, value1, key2, value2,...).
     * Returns an empty array if no tags.
     */
    String[] tags();
}