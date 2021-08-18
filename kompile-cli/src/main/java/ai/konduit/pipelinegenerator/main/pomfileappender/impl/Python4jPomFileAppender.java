package ai.konduit.pipelinegenerator.main.pomfileappender.impl;

import ai.konduit.pipelinegenerator.main.PomFileAppender;

import java.util.Arrays;
import java.util.List;

public class Python4jPomFileAppender implements PomFileAppender {
    @Override
    public DependencyType dependencyType() {
        return DependencyType.PYTHON4J;
    }

    @Override
    public List<String> classesToAppend() {
       return Arrays.asList(
               "org.nd4j.python4j.PythonExecutioner",
               "org.nd4j.python4j.PythonGIL",
               "org.nd4j.python4j.NumpyArray"

       );
    }

    @Override
    public InitializeType initializeType() {
        return InitializeType.RUNTIME;
    }
}
