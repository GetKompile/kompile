/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.pipeline.serving.subprocess;

import ai.kompile.app.subprocess.SubprocessArgsIo;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.IOException;
import java.nio.file.Path;

/** Startup configuration for the single persistent stdio pipeline runtime. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PipelineServingSubprocessArgs(
        String pipelineDefinitionJson
) {
    public static PipelineServingSubprocessArgs fromFile(Path path) throws IOException {
        return SubprocessArgsIo.fromFile(path, PipelineServingSubprocessArgs.class);
    }

    public Path writeToTempFile() throws IOException {
        return SubprocessArgsIo.writeToTempFile(this, "pipeline-runtime-args-");
    }
}
