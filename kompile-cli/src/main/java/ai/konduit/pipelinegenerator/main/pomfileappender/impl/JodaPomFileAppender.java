package ai.konduit.pipelinegenerator.main.pomfileappender.impl;

import ai.konduit.pipelinegenerator.main.pomfileappender.PomFileAppender;

import java.util.Arrays;
import java.util.List;

public class JodaPomFileAppender implements PomFileAppender {
    @Override
    public DependencyType dependencyType() {
        return DependencyType.JODA;
    }

    @Override
    public List<String> classesToAppend() {
        return Arrays.asList("org.joda.time.DateTimeFieldType$StandardDateTimeFieldType",
        "org.joda.time.tz.FixedDateTimeZone",
        "org.joda.time.DateTimeFieldType",
         "org.joda.time.tz.CachedDateTimeZone",
         "org.joda.time.DateTimeZone");
    }

    @Override
    public InitializeType initializeType() {
        return InitializeType.BUILD_TIME;
    }
}
