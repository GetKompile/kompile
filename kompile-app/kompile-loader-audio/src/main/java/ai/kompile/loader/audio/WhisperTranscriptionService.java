/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.loader.audio;

import org.eclipse.deeplearning4j.audio.whisper.WhisperConfig;
import org.eclipse.deeplearning4j.audio.whisper.WhisperDecoderResult;
import org.eclipse.deeplearning4j.audio.whisper.WhisperModel;
import org.eclipse.deeplearning4j.audio.whisper.WhisperModelDownloader;
import org.eclipse.deeplearning4j.audio.whisper.WhisperModelDownloader.WhisperModelFormat;
import org.eclipse.deeplearning4j.audio.whisper.WhisperModelDownloader.WhisperModelSize;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;

/**
 * Wraps samediff-audio's {@link WhisperModel} to provide audio transcription
 * for the kompile document ingest pipeline.
 * <p>
 * Supports loading Whisper models from:
 * <ul>
 *   <li>Local GGUF files (via {@code fromGgml})</li>
 *   <li>Local ONNX model directories (via {@code fromOnnx})</li>
 *   <li>Auto-download from HuggingFace (via {@code fromDownload})</li>
 * </ul>
 */
public class WhisperTranscriptionService implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(WhisperTranscriptionService.class);

    private WhisperModel model;

    private WhisperTranscriptionService(WhisperModel model) {
        this.model = model;
    }

    /**
     * Create from a local GGUF model file.
     */
    public static WhisperTranscriptionService fromGguf(File ggufFile) throws IOException {
        return fromGguf(ggufFile, null);
    }

    /**
     * Create from a local GGUF model file with explicit config.
     */
    public static WhisperTranscriptionService fromGguf(File ggufFile, WhisperConfig config) throws IOException {
        log.info("Loading Whisper model from GGUF: {}", ggufFile);
        WhisperModel model = WhisperModel.fromGgml(ggufFile, config);
        return new WhisperTranscriptionService(model);
    }

    /**
     * Create from a local ONNX model directory.
     */
    public static WhisperTranscriptionService fromOnnx(File modelDir) throws IOException {
        return fromOnnx(modelDir, null);
    }

    /**
     * Create from a local ONNX model directory with explicit config.
     */
    public static WhisperTranscriptionService fromOnnx(File modelDir, WhisperConfig config) throws IOException {
        log.info("Loading Whisper model from ONNX dir: {}", modelDir);
        WhisperModel model = WhisperModel.fromOnnx(modelDir, config);
        return new WhisperTranscriptionService(model);
    }

    /**
     * Download and load a Whisper model.
     * Uses ~/.cache/dl4j-whisper-models for caching.
     */
    public static WhisperTranscriptionService fromDownload(WhisperModelSize size,
                                                            WhisperModelFormat format) throws IOException {
        log.info("Downloading Whisper model: size={}, format={}", size, format);
        WhisperModel model = WhisperModel.fromDownload(size, format);
        return new WhisperTranscriptionService(model);
    }

    /**
     * Transcribe an audio file with timestamped segments.
     *
     * @param audioFile the audio file to transcribe (WAV, MP3, FLAC, etc.)
     * @param language  language code (e.g. "en") or null for auto-detect
     * @return transcription result with text and timestamped segments
     */
    public WhisperDecoderResult transcribe(File audioFile, String language) throws IOException {
        if (!audioFile.exists()) {
            throw new IOException("Audio file not found: " + audioFile);
        }
        String lang = language != null ? language : "en";
        log.debug("Transcribing {} (language={})", audioFile.getName(), lang);
        return model.transcribe(audioFile, lang, "transcribe", true);
    }

    /**
     * Transcribe an audio file with default language (English) and timestamps.
     */
    public WhisperDecoderResult transcribe(File audioFile) throws IOException {
        return transcribe(audioFile, null);
    }

    /**
     * Check if the model is loaded and ready.
     */
    public boolean isReady() {
        return model != null && model.getEncoder() != null;
    }

    @Override
    public void close() throws IOException {
        if (model != null) {
            model.close();
            model = null;
        }
    }
}
