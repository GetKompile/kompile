package ai.kompile.pipelines.framework.core.context;

import ai.kompile.pipelines.framework.api.context.*;
import ai.kompile.pipelines.framework.api.data.Data;
import lombok.extern.slf4j.Slf4j;


import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;


public class DefaultContext implements Context {

    private final Map<String, Object> entries;
    private final Metrics metrics;
    private final Profiler profiler;
    private final Data originalInput; // Immutable reference to the very first input
    private final String executionId;
    private final String contextName;
    private final DefaultContext parentContext;


    public DefaultContext(Data originalInput, String executionId, String contextName, DefaultContext parentContext, Metrics metrics, Profiler profiler) {
        this.entries = new ConcurrentHashMap<>();
        this.originalInput = (originalInput != null) ? originalInput.dup() : Data.empty(); // Store a defensive copy
        this.executionId = (executionId != null) ? executionId : UUID.randomUUID().toString();
        this.contextName = (contextName != null) ? contextName : "root";
        this.parentContext = parentContext; // Can be null for root context
        this.metrics = (metrics != null) ? metrics : NoOpMetrics.INSTANCE; // Use NoOp if null
        this.profiler = (profiler != null) ? profiler : NoOpProfiler.INSTANCE; // Use NoOp if null
    }

    // Root context constructor
    public DefaultContext(Data originalInput) {
        this(originalInput, null, "root", null, null, null);
    }

    // Root context with custom ID
    public DefaultContext(Data originalInput, String executionId) {
        this(originalInput, executionId, "root", null, null, null);
    }


    @Override
    public Context put(String key, Object value) {
        entries.put(key, value);
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> get(String key, Class<T> type) {
        Object value = entries.get(key);
        if (value != null && type.isInstance(value)) {
            return Optional.of((T) value);
        }
        // Optionally, could check parent context if not found here
        // if (parentContext != null) { return parentContext.get(key, type); }
        return Optional.empty();
    }

    @Override
    public <T> T getOrDefault(String key, Class<T> type, T defaultValue) {
        return get(key, type).orElse(defaultValue);
    }

    @Override
    public Object remove(String key) {
        return entries.remove(key);
    }

    @Override
    public boolean containsKey(String key) {
        return entries.containsKey(key);
        // Optionally, check parent: || (parentContext != null && parentContext.containsKey(key));
    }

    @Override
    public Map<String, Object> entries() {
        // Could provide a merged view with parent context if hierarchical lookup is desired
        return Collections.unmodifiableMap(entries);
    }

    @Override
    public Metrics metrics() {
        return metrics;
    }

    @Override
    public Profiler profiler() {
        return profiler;
    }

    @Override
    public Data originalInput() {
        return originalInput; // Already a defensive copy
    }

    @Override
    public Optional<String> executionId() {
        return Optional.ofNullable(executionId);
    }

    @Override
    public Context child(String name) {
        String childContextName = this.contextName + "." + name;
        // Child context inherits originalInput, executionId, metrics, and profiler from parent.
        // Entries are scoped to the child.
        return new DefaultContext(this.originalInput, this.executionId, childContextName, this, this.metrics, this.profiler);
    }


    // --- No-Op Implementations for Metrics/Profiler (can be in separate files if preferred) ---
    private static class NoOpMetrics implements Metrics {
        static final NoOpMetrics INSTANCE = new NoOpMetrics();
        private static final NoOpCounter COUNTER_INSTANCE = new NoOpCounter();
        private static final NoOpTimer TIMER_INSTANCE = new NoOpTimer();
        private static final NoOpGauge GAUGE_INSTANCE = new NoOpGauge();

        @Override public Counter counter(String name, String help, String... tags) { return COUNTER_INSTANCE; }
        @Override public Timer timer(String name, String help, String... tags) { return TIMER_INSTANCE; }
        @Override public Gauge gauge(String name, String help, String... tags) { return GAUGE_INSTANCE; }
    }

    private static class NoOpCounter implements Counter {
        @Override public void increment() {}
        @Override public void increment(double amount) {}
        @Override public double count() { return 0; }
        @Override public String name() { return "noop"; }
        @Override public String help() { return ""; }
        @Override public String[] tags() { return new String[0];}
    }

    private static class NoOpTimer implements Timer {
        private static final Sample SAMPLE_INSTANCE = () -> 0L;
        @Override public Sample start() { return SAMPLE_INSTANCE; }
        @Override public void record(long amount, java.util.concurrent.TimeUnit unit) {}
        @Override public <T> T record(java.util.concurrent.Callable<T> f) throws Exception { return f.call(); }
        @Override public void record(Runnable f) { f.run(); }
        @Override public long totalTime(java.util.concurrent.TimeUnit unit) { return 0; }
        @Override public long count() { return 0; }
        @Override public String name() { return "noop"; }
        @Override public String help() { return ""; }
        @Override public String[] tags() { return new String[0];}
    }

    private static class NoOpGauge implements Gauge {
        private java.util.function.Supplier<Number> supplier = () -> 0;
        @Override public void setSupplier(java.util.function.Supplier<Number> supplier) { if(supplier!=null) this.supplier = supplier; }
        @Override public double value() { return supplier.get().doubleValue(); }
        @Override public String name() { return "noop"; }
        @Override public String help() { return ""; }
        @Override public String[] tags() { return new String[0];}
    }
}