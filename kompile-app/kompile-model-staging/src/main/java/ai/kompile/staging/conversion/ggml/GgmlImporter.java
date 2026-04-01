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

package ai.kompile.staging.conversion.ggml;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Importer for GGML/GGUF model files.
 *
 * <p>Parses GGML and GGUF format files to extract model metadata including:
 * architecture, quantization type, context length, vocabulary size, etc.</p>
 *
 * <h3>GGUF Format Structure:</h3>
 * <pre>
 * 1. Magic number (4 bytes): "GGUF"
 * 2. Version (4 bytes): uint32 (1, 2, or 3)
 * 3. Tensor count (8 bytes): uint64
 * 4. Metadata key-value count (8 bytes): uint64
 * 5. Metadata entries (variable)
 * 6. Tensor info entries (variable)
 * 7. Tensor data (variable, aligned)
 * </pre>
 *
 * <h3>Supported Architectures:</h3>
 * <ul>
 *   <li>LLaMA, LLaMA 2, LLaMA 3</li>
 *   <li>Mistral, Mixtral</li>
 *   <li>Phi, Phi-2, Phi-3</li>
 *   <li>Qwen, Qwen2</li>
 *   <li>Gemma</li>
 *   <li>Falcon</li>
 *   <li>MPT</li>
 *   <li>StarCoder, StarCoder2</li>
 *   <li>And many more...</li>
 * </ul>
 */
@Component
public class GgmlImporter {

    private static final Logger log = LoggerFactory.getLogger(GgmlImporter.class);

    // Magic numbers
    private static final int GGUF_MAGIC = 0x46554747; // "GGUF" in little-endian
    private static final int GGML_MAGIC = 0x6C6D6767; // "ggml" in little-endian

    // GGUF value types
    private static final int GGUF_TYPE_UINT8 = 0;
    private static final int GGUF_TYPE_INT8 = 1;
    private static final int GGUF_TYPE_UINT16 = 2;
    private static final int GGUF_TYPE_INT16 = 3;
    private static final int GGUF_TYPE_UINT32 = 4;
    private static final int GGUF_TYPE_INT32 = 5;
    private static final int GGUF_TYPE_FLOAT32 = 6;
    private static final int GGUF_TYPE_BOOL = 7;
    private static final int GGUF_TYPE_STRING = 8;
    private static final int GGUF_TYPE_ARRAY = 9;
    private static final int GGUF_TYPE_UINT64 = 10;
    private static final int GGUF_TYPE_INT64 = 11;
    private static final int GGUF_TYPE_FLOAT64 = 12;

    /**
     * Parse a GGML/GGUF file and extract model information.
     *
     * @param modelPath Path to the .gguf or .ggml file
     * @return Extracted model information
     */
    public GgmlModelInfo parseFile(Path modelPath) {
        if (!Files.exists(modelPath)) {
            return GgmlModelInfo.invalid("File does not exist: " + modelPath);
        }

        try (RandomAccessFile raf = new RandomAccessFile(modelPath.toFile(), "r");
             FileChannel channel = raf.getChannel()) {

            long fileSize = channel.size();
            if (fileSize < 24) {
                return GgmlModelInfo.invalid("File too small to be a valid GGML/GGUF file");
            }

            // Read header
            ByteBuffer header = ByteBuffer.allocate(24);
            header.order(ByteOrder.LITTLE_ENDIAN);
            channel.read(header);
            header.flip();

            int magic = header.getInt();

            if (magic == GGUF_MAGIC) {
                return parseGguf(channel, header, fileSize);
            } else if (magic == GGML_MAGIC) {
                return parseGgmlLegacy(channel, fileSize);
            } else {
                return GgmlModelInfo.invalid(
                        String.format("Unknown file format. Magic: 0x%08X (expected GGUF: 0x%08X or GGML: 0x%08X)",
                                magic, GGUF_MAGIC, GGML_MAGIC));
            }

        } catch (IOException e) {
            log.error("Failed to parse GGML/GGUF file: {}", modelPath, e);
            return GgmlModelInfo.invalid("IO error: " + e.getMessage());
        }
    }

    /**
     * Check if a file is a valid GGML/GGUF file by checking the magic number.
     */
    public boolean isGgmlFile(Path path) {
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            return false;
        }

        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
            if (raf.length() < 4) {
                return false;
            }
            int magic = Integer.reverseBytes(raf.readInt());
            return magic == GGUF_MAGIC || magic == GGML_MAGIC;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Parse GGUF format (modern format with full metadata support)
     */
    private GgmlModelInfo parseGguf(FileChannel channel, ByteBuffer header, long fileSize) throws IOException {
        int version = header.getInt();
        long tensorCount = header.getLong();
        long metadataKvCount = header.getLong();

        log.debug("GGUF version: {}, tensors: {}, metadata keys: {}", version, tensorCount, metadataKvCount);

        if (version < 1 || version > 3) {
            return GgmlModelInfo.invalid("Unsupported GGUF version: " + version);
        }

        Map<String, Object> metadata = new HashMap<>();

        // Read metadata key-value pairs
        for (long i = 0; i < metadataKvCount && i < 10000; i++) { // Cap at 10k entries for safety
            try {
                String key = readGgufString(channel, version);
                int valueType = readUint32(channel);
                Object value = readGgufValue(channel, valueType, version);
                metadata.put(key, value);
                log.trace("Metadata: {} = {} (type {})", key, value, valueType);
            } catch (Exception e) {
                log.warn("Error reading metadata entry {}: {}", i, e.getMessage());
                break;
            }
        }

        // Extract model information from metadata
        return GgmlModelInfo.builder()
                .valid(true)
                .format("gguf")
                .version(version)
                .tensorCount((int) tensorCount)
                .fileSizeBytes(fileSize)
                .architecture(getStringMetadata(metadata, "general.architecture"))
                .modelName(getStringMetadata(metadata, "general.name"))
                .quantizationType(getStringMetadata(metadata, "general.file_type"))
                .contextLength(getIntMetadata(metadata, getArchKey(metadata, "context_length")))
                .embeddingDimension(getIntMetadata(metadata, getArchKey(metadata, "embedding_length")))
                .numAttentionHeads(getIntMetadata(metadata, getArchKey(metadata, "attention.head_count")))
                .numKvHeads(getIntMetadata(metadata, getArchKey(metadata, "attention.head_count_kv")))
                .numLayers(getIntMetadata(metadata, getArchKey(metadata, "block_count")))
                .vocabSize(getIntMetadata(metadata, "tokenizer.ggml.tokens", -1) != -1 ?
                        getArrayLength(metadata, "tokenizer.ggml.tokens") :
                        getIntMetadata(metadata, getArchKey(metadata, "vocab_size")))
                .feedForwardDimension(getIntMetadata(metadata, getArchKey(metadata, "feed_forward_length")))
                .ropeFrequencyBase(getFloatMetadata(metadata, getArchKey(metadata, "rope.freq_base")))
                .ropeScalingFactor(getFloatMetadata(metadata, getArchKey(metadata, "rope.scale")))
                .bosTokenId(getIntMetadata(metadata, "tokenizer.ggml.bos_token_id"))
                .eosTokenId(getIntMetadata(metadata, "tokenizer.ggml.eos_token_id"))
                .padTokenId(getIntMetadata(metadata, "tokenizer.ggml.padding_token_id"))
                .metadata(metadata)
                .build();
    }

    /**
     * Parse legacy GGML format (limited metadata)
     */
    private GgmlModelInfo parseGgmlLegacy(FileChannel channel, long fileSize) throws IOException {
        // Legacy GGML format has minimal header
        // Just return basic info since full metadata isn't available
        log.debug("Parsing legacy GGML format");

        return GgmlModelInfo.builder()
                .valid(true)
                .format("ggml")
                .version(1)
                .fileSizeBytes(fileSize)
                .build();
    }

    /**
     * Read a GGUF string (version-dependent format)
     */
    private String readGgufString(FileChannel channel, int version) throws IOException {
        long length;
        if (version >= 2) {
            length = readUint64(channel);
        } else {
            length = readUint32(channel);
        }

        if (length > 1_000_000) { // Sanity check
            throw new IOException("String length too large: " + length);
        }

        if (length == 0) {
            return "";
        }

        ByteBuffer buffer = ByteBuffer.allocate((int) length);
        channel.read(buffer);
        buffer.flip();
        return StandardCharsets.UTF_8.decode(buffer).toString();
    }

    /**
     * Read a GGUF value based on type
     */
    private Object readGgufValue(FileChannel channel, int type, int version) throws IOException {
        switch (type) {
            case GGUF_TYPE_UINT8:
                return readUint8(channel);
            case GGUF_TYPE_INT8:
                return readInt8(channel);
            case GGUF_TYPE_UINT16:
                return readUint16(channel);
            case GGUF_TYPE_INT16:
                return readInt16(channel);
            case GGUF_TYPE_UINT32:
                return readUint32(channel);
            case GGUF_TYPE_INT32:
                return readInt32(channel);
            case GGUF_TYPE_FLOAT32:
                return readFloat32(channel);
            case GGUF_TYPE_BOOL:
                return readUint8(channel) != 0;
            case GGUF_TYPE_STRING:
                return readGgufString(channel, version);
            case GGUF_TYPE_ARRAY:
                return readGgufArray(channel, version);
            case GGUF_TYPE_UINT64:
                return readUint64(channel);
            case GGUF_TYPE_INT64:
                return readInt64(channel);
            case GGUF_TYPE_FLOAT64:
                return readFloat64(channel);
            default:
                throw new IOException("Unknown GGUF value type: " + type);
        }
    }

    /**
     * Read a GGUF array
     */
    private Object readGgufArray(FileChannel channel, int version) throws IOException {
        int elementType = readUint32(channel);
        long length;
        if (version >= 2) {
            length = readUint64(channel);
        } else {
            length = readUint32(channel);
        }

        // For large arrays (like token lists), just return metadata
        if (length > 10000) {
            // Skip the array data
            long skipBytes = calculateArraySize(elementType, length);
            if (skipBytes > 0) {
                channel.position(channel.position() + skipBytes);
            }
            return Map.of("_type", "array", "_element_type", elementType, "_length", length);
        }

        // Read small arrays fully
        Object[] array = new Object[(int) length];
        for (int i = 0; i < length; i++) {
            array[i] = readGgufValue(channel, elementType, version);
        }
        return array;
    }

    /**
     * Calculate array data size for skipping
     */
    private long calculateArraySize(int elementType, long length) {
        switch (elementType) {
            case GGUF_TYPE_UINT8:
            case GGUF_TYPE_INT8:
            case GGUF_TYPE_BOOL:
                return length;
            case GGUF_TYPE_UINT16:
            case GGUF_TYPE_INT16:
                return length * 2;
            case GGUF_TYPE_UINT32:
            case GGUF_TYPE_INT32:
            case GGUF_TYPE_FLOAT32:
                return length * 4;
            case GGUF_TYPE_UINT64:
            case GGUF_TYPE_INT64:
            case GGUF_TYPE_FLOAT64:
                return length * 8;
            default:
                return -1; // Unknown, can't skip
        }
    }

    // Primitive readers
    private int readUint8(FileChannel channel) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(1);
        channel.read(buf);
        buf.flip();
        return buf.get() & 0xFF;
    }

    private int readInt8(FileChannel channel) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(1);
        channel.read(buf);
        buf.flip();
        return buf.get();
    }

    private int readUint16(FileChannel channel) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(2);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        channel.read(buf);
        buf.flip();
        return buf.getShort() & 0xFFFF;
    }

    private int readInt16(FileChannel channel) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(2);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        channel.read(buf);
        buf.flip();
        return buf.getShort();
    }

    private int readUint32(FileChannel channel) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(4);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        channel.read(buf);
        buf.flip();
        return buf.getInt();
    }

    private int readInt32(FileChannel channel) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(4);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        channel.read(buf);
        buf.flip();
        return buf.getInt();
    }

    private long readUint64(FileChannel channel) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(8);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        channel.read(buf);
        buf.flip();
        return buf.getLong();
    }

    private long readInt64(FileChannel channel) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(8);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        channel.read(buf);
        buf.flip();
        return buf.getLong();
    }

    private float readFloat32(FileChannel channel) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(4);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        channel.read(buf);
        buf.flip();
        return buf.getFloat();
    }

    private double readFloat64(FileChannel channel) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(8);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        channel.read(buf);
        buf.flip();
        return buf.getDouble();
    }

    // Metadata helpers
    private String getArchKey(Map<String, Object> metadata, String suffix) {
        String arch = getStringMetadata(metadata, "general.architecture");
        if (arch != null) {
            return arch + "." + suffix;
        }
        return suffix;
    }

    private String getStringMetadata(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value instanceof String ? (String) value : null;
    }

    private int getIntMetadata(Map<String, Object> metadata, String key) {
        return getIntMetadata(metadata, key, 0);
    }

    private int getIntMetadata(Map<String, Object> metadata, String key, int defaultValue) {
        Object value = metadata.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return defaultValue;
    }

    private float getFloatMetadata(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value instanceof Number) {
            return ((Number) value).floatValue();
        }
        return 0f;
    }

    private int getArrayLength(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value instanceof Object[]) {
            return ((Object[]) value).length;
        }
        if (value instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> arrayInfo = (Map<String, Object>) value;
            Object length = arrayInfo.get("_length");
            if (length instanceof Number) {
                return ((Number) length).intValue();
            }
        }
        return 0;
    }
}
