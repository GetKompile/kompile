package ai.konduit.pipelinegenerator.main.pomfileappender.impl;

import ai.konduit.pipelinegenerator.main.pomfileappender.PomFileAppender;

import java.util.Arrays;
import java.util.List;

public class ApacheCommonsPomFileAppender implements PomFileAppender {
    @Override
    public DependencyType dependencyType() {
        return DependencyType.APACHE_COMMONS;
    }

    @Override
    public List<String> classesToAppend() {
        return Arrays.asList(
                "org.apache.commons.io.FileUtils",
                "org.apache.commons.io.Charsets",
                "org.apache.commons.io.FilenameUtils",
                "org.apache.commons.io.IOUtils"
        );
    }

    @Override
    public InitializeType initializeType() {
        return InitializeType.BUILD_TIME;
    }
}
