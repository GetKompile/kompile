/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.staging.execution;

import org.nd4j.ggml.format.GGUFReader;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Resolves the chat template for a locally staged SameDiff model.
 *
 * <p>A staged model directory holds the converted {@code .sdnb} shards and {@code tokenizer.json},
 * and {@code tokenizer.json} carries no {@code chat_template} — that field lives in
 * {@code tokenizer_config.json} upstream, which conversion does not copy. A pipeline handed no
 * template therefore encodes the prompt verbatim, and the model answers as a base completion model:
 * it continues the text instead of taking a turn. On an instruction-shaped prompt that ends with a
 * directive, the most likely continuation is the end of the document, so the model emits
 * end-of-sequence and says nothing at all.</p>
 *
 * <p>The GGUF the model was converted from is staged alongside the shards and does carry
 * {@code tokenizer.chat_template}, so that is read here — the model's own template, not a generic
 * stand-in.</p>
 */
final class LocalModelChatTemplate {

    private LocalModelChatTemplate() {
    }

    /**
     * @param modelDir a staged model directory
     * @return the template declared by the GGUF in that directory, or null if there is none
     */
    static String resolve(Path modelDir) {
        try (Stream<Path> entries = Files.list(modelDir)) {
            Path gguf = entries
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".gguf"))
                    .max(Comparator.comparing(p -> p.getFileName().toString()))
                    .orElse(null);
            if (gguf == null) {
                return null;
            }
            try (GGUFReader reader = new GGUFReader(new File(gguf.toString()))) {
                String template = reader.getHeader().getChatTemplate();
                return template == null || template.isBlank() ? null : template;
            }
        } catch (Exception e) {
            // Absent or unreadable metadata is reported by the caller as "no template", which the
            // harness prints — a silent fallback to raw completion is exactly the failure this
            // class exists to make visible.
            return null;
        }
    }
}
