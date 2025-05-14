package ai.kompile.pipelines.framework.core.context;

import ai.kompile.pipelines.framework.api.context.Profiler;

/**
 * A No-Operation (No-Op) implementation of the {@link Profiler} interface.
 * This implementation does nothing when its methods are called, effectively disabling profiling.
 * It can be used as a default when no specific profiler is configured or when profiling
 * is explicitly turned off.
 * <p>
 * This class is designed as a singleton.
 */
public final class NoOpProfiler implements Profiler {

    /**
     * Singleton instance of the NoOpProfiler.
     */
    public static final NoOpProfiler INSTANCE = new NoOpProfiler();

    private NoOpProfiler() {
        // Private constructor to prevent instantiation, ensuring singleton pattern.
    }

    /**
     * {@inheritDoc}
     * This is a no-op.
     */
    @Override
    public void startEvent(String eventName) {
        // No operation
    }

    /**
     * {@inheritDoc}
     * This is a no-op.
     */
    @Override
    public void stopEvent() {
        // No operation
    }

    /**
     * {@inheritDoc}
     * Executes the callable directly without any profiling overhead.
     */
    @Override
    public <V> V profile(String eventName, CallableWithException<V> callable) throws Exception {
        // Argument validation could be added here if desired, e.g., checking for null eventName or callable,
        // though for a NoOp implementation, it might be acceptable to skip them for minimal overhead.
        if (callable == null) {
            throw new NullPointerException("CallableWithException cannot be null in NoOpProfiler.profile");
        }
        return callable.call();
    }

    /**
     * {@inheritDoc}
     * Executes the runnable directly without any profiling overhead.
     */
    @Override
    public void profile(String eventName, RunnableWithException runnable) throws Exception {
        // Argument validation could be added here as well.
        if (runnable == null) {
            throw new NullPointerException("RunnableWithException cannot be null in NoOpProfiler.profile");
        }
        runnable.run();
    }
}