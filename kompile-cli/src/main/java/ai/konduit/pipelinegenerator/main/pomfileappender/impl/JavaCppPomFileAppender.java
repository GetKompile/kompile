package ai.konduit.pipelinegenerator.main.pomfileappender.impl;

import ai.konduit.pipelinegenerator.main.PomFileAppender;

import java.util.Arrays;
import java.util.List;

public class JavaCppPomFileAppender implements PomFileAppender {
    @Override
    public DependencyType dependencyType() {
        return DependencyType.JAVACPP;
    }

    @Override
    public List<String> classesToAppend() {
        return Arrays.asList(
                "org.bytedeco.javacpp.tools.Logger",
                "org.bytedeco.javacpp.BytePointer",
                "org.bytedeco.javacpp.ClassProperties",
                "org.bytedeco.javacpp.Loader"

        );
    }

    @Override
    public InitializeType initializeType() {
        return InitializeType.RUNTIME;
    }
}
