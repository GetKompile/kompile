package ai.konduit.pipelinegenerator.main.pomfileappender.impl;

import ai.konduit.pipelinegenerator.main.PomFileAppender;

import java.util.Arrays;
import java.util.List;

public class KonduitDSLPomAppender implements PomFileAppender {
    @Override
    public DependencyType dependencyType() {
        return DependencyType.KONDUIT_SERVING_DSL;
    }

    @Override
    public List<String> classesToAppend() {
        return Arrays.asList(
                "ai.konduit.serving.pipeline.impl.pipeline.SequencePipelineExecutor",
                "ai.konduit.serving.pipeline.util.ObjectMappers",
                "ai.konduit.serving.pipeline.registry.PipelineRegistry"
        );
    }

    @Override
    public InitializeType initializeType() {
        return InitializeType.BUILD_TIME;
    }
}
