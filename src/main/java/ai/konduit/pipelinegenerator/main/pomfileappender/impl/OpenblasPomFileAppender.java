package ai.konduit.pipelinegenerator.main.pomfileappender.impl;

import ai.konduit.pipelinegenerator.main.pomfileappender.PomFileAppender;

import java.util.Arrays;
import java.util.List;

public class OpenblasPomFileAppender implements PomFileAppender {
    @Override
    public DependencyType dependencyType() {
        return DependencyType.OPENBLAS;
    }

    @Override
    public List<String> classesToAppend() {
        return Arrays.asList(
                "org.bytedeco.openblas.presets.openblas",
                "org.bytedeco.openblas.global.openblas",
                "org.bytedeco.openblas.global.openblas_nolapack"

        );
    }

    @Override
    public List<String> classesToReInitialize() {
        return Arrays.asList(
           /*     "org.bytedeco.openblas.presets.openblas_nolapack",
                "org.bytedeco.openblas.presets.openblas",
                "org.bytedeco.openblas.global.openblas"*/
        );
    }

    @Override
    public InitializeType initializeType() {
        return InitializeType.RUNTIME;
    }
}
