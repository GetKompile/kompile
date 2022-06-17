package ai.konduit.pipelinegenerator.main.pomfileappender.impl;

import ai.konduit.pipelinegenerator.main.PomFileAppender;

import java.util.Arrays;
import java.util.List;

public class PythonPomFileAppender implements PomFileAppender {
    @Override
    public DependencyType dependencyType() {
        return DependencyType.PYTHON;
    }

    @Override
    public List<String> classesToAppend() {
        return Arrays.asList(
                "org.bytedeco.cpython.helper.python",
                "org.bytedeco.cpython.global.python",
                "org.bytedeco.numpy.presets.numpy",
                "org.bytedeco.cpython.PyThreadState"
        );
    }

    @Override
    public InitializeType initializeType() {
        return InitializeType.BUILD_TIME;
    }
}
