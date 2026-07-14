/*
 *   Copyright 2026 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 */

package ai.kompile.staging.execution.audio;

import ai.kompile.modelmanager.registry.ModelEntry;
import org.eclipse.deeplearning4j.audio.synthesis.AudioFileGenerator;

import java.nio.file.Path;

/**
 * Staging-owned loader bridge for an audio model architecture.
 *
 * <p>Implementations load an activated registry model and return the completed
 * file generator supplied by samediff-audio. They do not expose transport
 * concerns or application callbacks.</p>
 */
public interface AudioSynthesisBackend {

    boolean supports(ModelEntry model);

    AudioFileGenerator load(ModelEntry model, Path modelDirectory) throws Exception;
}
