/*
 *  Copyright 2025 Kompile Inc.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  * http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 */

// Suggested Path: upload/kompile-pipelines-framework-core/src/main/java/ai/kompile/pipelines/framework/core/context/NoOpMetrics.java
package ai.kompile.pipelines.framework.core.context; // Or your preferred core context package

import ai.kompile.pipelines.framework.api.context.Counter;
import ai.kompile.pipelines.framework.api.context.Gauge;
import ai.kompile.pipelines.framework.api.context.Metrics;
import ai.kompile.pipelines.framework.api.context.Timer;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * A No-Operation (NoOp) implementation of the {@link Metrics} interface.
 * This implementation provides do-nothing stubs for all metric operations,
 * allowing the framework to function without a concrete metrics backend.
 * It produces no actual metrics data.
 *
 * This can be used as a default or fallback when no specific metrics
 * implementation is configured.
 */
public final class NoOpMetrics implements Metrics {

    /**
     * Singleton instance of NoOpMetrics.
     */
    public static final NoOpMetrics INSTANCE = new NoOpMetrics();

    private static final NoOpCounter COUNTER_INSTANCE = new NoOpCounter();
    private static final NoOpTimer TIMER_INSTANCE = new NoOpTimer();
    private static final NoOpGauge GAUGE_INSTANCE = new NoOpGauge();

    private NoOpMetrics() {
        // Private constructor for singleton
    }

    @Override
    public Counter counter(String name, String help, String... tags) {
        return COUNTER_INSTANCE;
    }

    @Override
    public Timer timer(String name, String help, String... tags) {
        return TIMER_INSTANCE;
    }

    @Override
    public Gauge gauge(String name, String help, String... tags) {
        return GAUGE_INSTANCE;
    }

    private static class NoOpCounter implements Counter {
        @Override
        public void increment() { /* no-op */ }

        @Override
        public void increment(double amount) { /* no-op */ }

        @Override
        public double count() { return 0; }

        @Override
        public String name() { return "noop_counter"; }

        @Override
        public String help() { return "No-op counter"; }

        @Override
        public String[] tags() { return new String[0]; }
    }

    private static class NoOpTimer implements Timer {
        private static final NoOpSample SAMPLE_INSTANCE = new NoOpSample();

        @Override
        public Sample start() { return SAMPLE_INSTANCE; }

        @Override
        public void record(long amount, TimeUnit unit) { /* no-op */ }

        @Override
        public <T> T record(Callable<T> f) throws Exception {
            return f.call();
        }

        @Override
        public void record(Runnable f) {
            f.run();
        }

        @Override
        public long totalTime(TimeUnit unit) { return 0; }

        @Override
        public long count() { return 0; }

        @Override
        public String name() { return "noop_timer"; }

        @Override
        public String help() { return "No-op timer"; }

        @Override
        public String[] tags() { return new String[0]; }

        private static class NoOpSample implements Sample {
            @Override
            public long stop() { return 0; }
        }
    }

    private static class NoOpGauge implements Gauge {
        private Supplier<Number> supplier = () -> 0;

        @Override
        public void setSupplier(Supplier<Number> supplier) {
            if (supplier != null) {
                this.supplier = supplier;
            }
        }

        @Override
        public double value() {
            return supplier.get().doubleValue();
        }

        @Override
        public String name() { return "noop_gauge"; }

        @Override
        public String help() { return "No-op gauge"; }

        @Override
        public String[] tags() { return new String[0]; }
    }
}