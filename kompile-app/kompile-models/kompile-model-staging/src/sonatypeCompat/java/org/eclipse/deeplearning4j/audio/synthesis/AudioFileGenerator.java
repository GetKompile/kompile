/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package org.eclipse.deeplearning4j.audio.synthesis;

import java.nio.file.Path;

/** Fail-closed API bridge for Sonatype snapshots that predate audio synthesis. */
@FunctionalInterface
public interface AudioFileGenerator extends AutoCloseable {
    GeneratedAudioFile generate(AudioSynthesisRequest request, Path outputDirectory) throws Exception;

    @Override
    default void close() throws Exception {
    }
}
