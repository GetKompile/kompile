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

package ai.kompile.pipelines.framework.api.context;

/**
 * Interface for performance profiling within a pipeline execution.
 * Allows marking the start and end of named events to measure their duration and relationships.
 * The actual profiling backend (e.g., an in-memory trace, a logging profiler, or integration
 * with external tools like JProfiler, YourKit, or OpenTelemetry traces) is abstracted.
 */
public interface Profiler {

    /**
     * Marks the beginning of a named profiling event.
     * Each call to {@code startEvent} should have a corresponding {@code stopEvent}.
     * Events can be nested.
     *
     * @param eventName A descriptive name for the event being profiled (e.g., "DataLoading", "ModelInference", "StepX.PreProcessing").
     * Must not be null or empty.
     */
    void startEvent(String eventName);

    /**
     * Marks the end of the most recently started profiling event that has not yet been stopped.
     * If events are nested, this stops the innermost active event.
     *
     * @throws IllegalStateException if no event is currently active (i.e., {@code startEvent} was not called or all started events have been stopped).
     */
    void stopEvent();

    /**
     * Executes the given {@link CallableWithException} and profiles its execution under the given event name.
     * This is a convenience method that handles starting and stopping the event automatically.
     *
     * @param eventName The name for the profiling event.
     * @param callable The code to execute and profile.
     * @param <V> The return type of the callable.
     * @return The result of the callable.
     * @throws Exception If the callable throws an exception.
     */
    <V> V profile(String eventName, CallableWithException<V> callable) throws Exception;

    /**
     * Executes the given {@link RunnableWithException} and profiles its execution under the given event name.
     * This is a convenience method that handles starting and stopping the event automatically.
     *
     * @param eventName The name for the profiling event.
     * @param runnable The code to execute and profile.
     * @throws Exception If the runnable throws an exception.
     */
    void profile(String eventName, RunnableWithException runnable) throws Exception;

    /**
     * Functional interface similar to {@link java.util.concurrent.Callable} but allows throwing any Exception.
     * @param <V> The result type of the method.
     */
    @FunctionalInterface
    interface CallableWithException<V> {
        V call() throws Exception;
    }

    /**
     * Functional interface similar to {@link java.lang.Runnable} but allows throwing any Exception.
     */
    @FunctionalInterface
    interface RunnableWithException {
        void run() throws Exception;
    }
}