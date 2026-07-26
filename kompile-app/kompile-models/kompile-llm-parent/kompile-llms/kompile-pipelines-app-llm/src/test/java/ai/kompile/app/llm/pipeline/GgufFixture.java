/*
 * Copyright 2025 Kompile Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.kompile.app.llm.pipeline;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * A minimal but real GGUF: magic, version, counts, then metadata. No tensors — the reader parses
 * tensor info separately, and only the header carries the chat template. Writing real bytes keeps
 * the production reader in the path under test instead of mocking it away.
 */
final class GgufFixture {

    private static final int GGUF_MAGIC = 0x46554747;
    private static final int TYPE_UINT32 = 4;
    private static final int TYPE_STRING = 8;
    private static final int TYPE_ARRAY = 9;

    private GgufFixture() {
    }

    /**
     * @param chatTemplate the template to declare, or null to declare none
     */
    static byte[] headerWithChatTemplate(String chatTemplate) throws IOException {
        List<String> tokens = List.of("<|startoftext|>", "<|im_start|>", "<|im_end|>", "hello");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(le4(GGUF_MAGIC));
        out.write(le4(3));                                   // GGUF version
        out.write(le8(0L));                                  // tensor count
        out.write(le8(chatTemplate == null ? 4L : 5L));      // metadata KV count

        writeKvString(out, "general.architecture", "llama");
        writeKvStringArray(out, "tokenizer.ggml.tokens", tokens);
        writeKvUInt32(out, "tokenizer.ggml.bos_token_id", tokens.indexOf("<|startoftext|>"));
        writeKvUInt32(out, "tokenizer.ggml.eos_token_id", tokens.indexOf("<|im_end|>"));
        if (chatTemplate != null) {
            writeKvString(out, "tokenizer.chat_template", chatTemplate);
        }
        return out.toByteArray();
    }

    private static void writeKvString(ByteArrayOutputStream out, String key, String value)
            throws IOException {
        writeGgufString(out, key);
        out.write(le4(TYPE_STRING));
        writeGgufString(out, value);
    }

    private static void writeKvUInt32(ByteArrayOutputStream out, String key, int value)
            throws IOException {
        writeGgufString(out, key);
        out.write(le4(TYPE_UINT32));
        out.write(le4(value));
    }

    private static void writeKvStringArray(ByteArrayOutputStream out, String key, List<String> values)
            throws IOException {
        writeGgufString(out, key);
        out.write(le4(TYPE_ARRAY));
        out.write(le4(TYPE_STRING));
        out.write(le8(values.size()));
        for (String value : values) {
            writeGgufString(out, value);
        }
    }

    private static void writeGgufString(ByteArrayOutputStream out, String value) throws IOException {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        out.write(le8(utf8.length));
        out.write(utf8);
    }

    private static byte[] le4(int value) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }

    private static byte[] le8(long value) {
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array();
    }
}
