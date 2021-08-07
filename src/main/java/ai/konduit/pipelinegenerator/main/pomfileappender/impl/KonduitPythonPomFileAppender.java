package ai.konduit.pipelinegenerator.main.pomfileappender.impl;

import ai.konduit.pipelinegenerator.main.pomfileappender.PomFileAppender;

import java.util.Arrays;
import java.util.List;

public class KonduitPythonPomFileAppender implements PomFileAppender {
    @Override
    public DependencyType dependencyType() {
        return DependencyType.KONDUIT_PYTHON;
    }

    @Override
    public List<String> classesToAppend() {
        return Arrays.asList(
                "ai.konduit.serving.python.PythonRunner",
                "ai.konduit.serving.model.PythonConfig"
        );
    }

    @Override
    public InitializeType initializeType() {
        return InitializeType.RUNTIME;
    }
}
