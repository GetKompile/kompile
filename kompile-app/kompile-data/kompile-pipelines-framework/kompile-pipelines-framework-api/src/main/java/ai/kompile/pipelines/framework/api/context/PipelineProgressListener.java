/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.pipelines.framework.api.context;

/** Request-scoped listener used to stream pipeline progress without provider coupling. */
@FunctionalInterface
public interface PipelineProgressListener {
    String CONTEXT_KEY = PipelineProgressListener.class.getName();

    void onProgress(PipelineExecutionProgress progress);

    static PipelineProgressListener from(Context context) {
        return context == null ? null
                : context.get(CONTEXT_KEY, PipelineProgressListener.class).orElse(null);
    }

    static void report(Context context, PipelineExecutionProgress progress) {
        PipelineProgressListener listener = from(context);
        if (listener != null && progress != null) listener.onProgress(progress);
    }
}
