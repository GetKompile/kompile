package ai.kompile.bindings;

import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.io.IOException;
import java.nio.IntBuffer;

import org.bytedeco.javacpp.*;

/**
 * A user-friendly wrapper around the native tokenizer bindings.
 * This class provides a more intuitive Java API for tokenization operations.
 */
public class Tokenizer implements AutoCloseable {
    
    private final TokenizersNative.OpaqueTokenizer nativeTokenizer;
    private final TokenizersNative nativeLib;
    private boolean closed = false;
    
    /**
     * Create a tokenizer from a file path
     * @param modelPath Path to the tokenizer configuration file
     * @throws IOException if the file cannot be loaded or is invalid
     */
    public Tokenizer(String modelPath) throws IOException {
        this.nativeLib = new TokenizersNative();
        this.nativeTokenizer = nativeLib.createTokenizerFromFile(modelPath);
        
        if (nativeTokenizer == null || nativeTokenizer.isNull()) {
            TokenizersNative.TokenizerResult lastError = nativeLib.getLastError();
            String errorMessage = "Failed to create tokenizer from file: " + modelPath;
            if (lastError != null && lastError.error_message() != null) {
                errorMessage += " - " + lastError.error_message();
            }
            throw new IOException(errorMessage);
        }
        
        if (!nativeLib.tokenizerIsValid(nativeTokenizer)) {
            throw new IOException("Created tokenizer is not valid");
        }
    }
    
    /**
     * Create a tokenizer from JSON configuration
     * @param jsonConfig JSON configuration string
     * @throws IOException if the JSON is invalid or tokenizer creation fails
     */
    public static Tokenizer fromJson(String jsonConfig) throws IOException {
        return new Tokenizer(jsonConfig, true);
    }
    
    private Tokenizer(String jsonConfig, boolean isJson) throws IOException {
        this.nativeLib = new TokenizersNative();
        this.nativeTokenizer = nativeLib.createTokenizerFromJson(jsonConfig);
        
        if (nativeTokenizer == null || nativeTokenizer.isNull()) {
            TokenizersNative.TokenizerResult lastError = nativeLib.getLastError();
            String errorMessage = "Failed to create tokenizer from JSON";
            if (lastError != null && lastError.error_message() != null) {
                errorMessage += " - " + lastError.error_message();
            }
            throw new IOException(errorMessage);
        }
        
        if (!nativeLib.tokenizerIsValid(nativeTokenizer)) {
            throw new IOException("Created tokenizer is not valid");
        }
    }
    
    /**
     * Get the vocabulary size of this tokenizer
     * @return vocabulary size
     */
    public long getVocabSize() {
        ensureNotClosed();
        return nativeLib.getVocabSize(nativeTokenizer);
    }
    
    /**
     * Check if this tokenizer is valid
     * @return true if valid, false otherwise
     */
    public boolean isValid() {
        if (closed) return false;
        return nativeLib.tokenizerIsValid(nativeTokenizer);
    }
    
    /**
     * Encode text into token IDs
     * @param text Input text to encode
     * @return array of token IDs
     */
    public int[] encode(String text) {
        return encode(text, true);
    }
    
    /**
     * Encode text into token IDs
     * @param text Input text to encode
     * @param addSpecialTokens Whether to add special tokens
     * @return array of token IDs
     */
    public int[] encode(String text, boolean addSpecialTokens) {
        ensureNotClosed();
        
        TokenizersNative.OpaqueEncoding encoding = nativeLib.encodeText(nativeTokenizer, text, addSpecialTokens);
        if (encoding == null || encoding.isNull()) {
            throw new RuntimeException("Failed to encode text: " + text);
        }
        
        try {
            long length = nativeLib.encodingGetLength(encoding);
            if (length == 0) {
                return new int[0];
            }
            
            IntPointer idsPointer = nativeLib.encodingGetIds(encoding);
            if (idsPointer == null || idsPointer.isNull()) {
                throw new RuntimeException("Failed to get token IDs from encoding");
            }
            
            int[] ids = new int[(int) length];
            idsPointer.get(ids);
            return ids;
        } finally {
            nativeLib.freeEncoding(encoding);
        }
    }
    
    /**
     * Encode text and return detailed encoding information
     * @param text Input text to encode
     * @return EncodingResult containing tokens, IDs, and offsets
     */
    public EncodingResult encodeWithDetails(String text) {
        return encodeWithDetails(text, true);
    }
    
    /**
     * Encode text and return detailed encoding information
     * @param text Input text to encode
     * @param addSpecialTokens Whether to add special tokens
     * @return EncodingResult containing tokens, IDs, and offsets
     */
    public EncodingResult encodeWithDetails(String text, boolean addSpecialTokens) {
        ensureNotClosed();
        
        TokenizersNative.OpaqueEncoding encoding = nativeLib.encodeText(nativeTokenizer, text, addSpecialTokens);
        if (encoding == null || encoding.isNull()) {
            throw new RuntimeException("Failed to encode text: " + text);
        }
        
        try {
            long length = nativeLib.encodingGetLength(encoding);
            if (length == 0) {
                return new EncodingResult(new int[0], new String[0], new int[0], new int[0]);
            }
            
            // Get token IDs
            IntPointer idsPointer = nativeLib.encodingGetIds(encoding);
            if (idsPointer == null || idsPointer.isNull()) {
                throw new RuntimeException("Failed to get token IDs from encoding");
            }
            int[] ids = new int[(int) length];
            idsPointer.get(ids);
            
            // Get token strings
            PointerPointer tokensPointer = nativeLib.encodingGetTokens(encoding);
            String[] tokens = new String[(int) length];
            if (tokensPointer != null && !tokensPointer.isNull()) {
                for (int i = 0; i < length; i++) {
                    Pointer tokenPtr = tokensPointer.get(i);
                    if (tokenPtr != null) {
                        tokens[i] = new BytePointer(tokenPtr).getString();
                    } else {
                        tokens[i] = "";
                    }
                }
            }
            
            // Get offsets
            SizeTPointer startOffsets = new SizeTPointer(1);
            SizeTPointer endOffsets = new SizeTPointer(1);
            long offsetCount = nativeLib.encodingGetOffsets(encoding, startOffsets, endOffsets);
            
            int[] startOffsetsArray = new int[(int) offsetCount];
            int[] endOffsetsArray = new int[(int) offsetCount];
            
            if (offsetCount > 0) {
                // Note: This assumes the offsets are size_t which maps to long in Java
                // You might need to adjust this based on your platform
                for (int i = 0; i < offsetCount; i++) {
                    startOffsetsArray[i] = (int) startOffsets.get(i);
                    endOffsetsArray[i] = (int) endOffsets.get(i);
                }
            }
            
            return new EncodingResult(ids, tokens, startOffsetsArray, endOffsetsArray);
        } finally {
            nativeLib.freeEncoding(encoding);
        }
    }
    
    /**
     * Decode token IDs back to text
     * @param ids Array of token IDs
     * @return decoded text string
     */
    public String decode(int[] ids) {
        return decode(ids, true);
    }
    
    /**
     * Decode token IDs back to text
     * @param ids Array of token IDs
     * @param skipSpecialTokens Whether to skip special tokens in output
     * @return decoded text string
     */
    public String decode(int[] ids, boolean skipSpecialTokens) {
        ensureNotClosed();
        
        if (ids == null || ids.length == 0) {
            return "";
        }
        
        String result = nativeLib.decodeIds(nativeTokenizer, ids, ids.length, skipSpecialTokens);
        if (result == null) {
            throw new RuntimeException("Failed to decode token IDs");
        }
        
        return result;
    }
    
    /**
     * Decode a list of token IDs back to text
     * @param ids List of token IDs
     * @return decoded text string
     */
    public String decode(List<Integer> ids) {
        return decode(ids, true);
    }
    
    /**
     * Decode a list of token IDs back to text
     * @param ids List of token IDs
     * @param skipSpecialTokens Whether to skip special tokens in output
     * @return decoded text string
     */
    public String decode(List<Integer> ids, boolean skipSpecialTokens) {
        if (ids == null || ids.isEmpty()) {
            return "";
        }
        
        int[] idsArray = ids.stream().mapToInt(Integer::intValue).toArray();
        return decode(idsArray, skipSpecialTokens);
    }
    
    /**
     * Get version information
     * @return version string
     */
    public static String getVersion() {
        TokenizersNative nativeLib = new TokenizersNative();
        return nativeLib.getTokenizerVersion();
    }
    
    /**
     * Get build information
     * @return build info string
     */
    public static String getBuildInfo() {
        TokenizersNative nativeLib = new TokenizersNative();
        return nativeLib.getBuildInfo();
    }
    
    /**
     * Check if a model file is valid
     * @param modelPath Path to model file
     * @return true if valid, false otherwise
     */
    public static boolean isValidModelFile(String modelPath) {
        TokenizersNative nativeLib = new TokenizersNative();
        return nativeLib.isValidModelFile(modelPath);
    }
    
    /**
     * Get list of available embedded models
     * @return list of model names
     */
    public static List<String> getEmbeddedModels() {
        TokenizersNative nativeLib = new TokenizersNative();
        SizeTPointer numModels = new SizeTPointer(1);
        PointerPointer modelsPointer = nativeLib.getEmbeddedModels(numModels);
        
        List<String> models = new ArrayList<>();
        if (modelsPointer != null && !modelsPointer.isNull()) {
            long count = numModels.get();
            for (int i = 0; i < count; i++) {
                Pointer modelPtr = modelsPointer.get(i);
                if (modelPtr != null) {
                    models.add(new BytePointer(modelPtr).getString());
                }
            }
        }
        
        return models;
    }
    
    private void ensureNotClosed() {
        if (closed) {
            throw new IllegalStateException("Tokenizer has been closed");
        }
    }
    
    @Override
    public void close() {
        if (!closed && nativeTokenizer != null) {
            nativeLib.freeTokenizer(nativeTokenizer);
            closed = true;
        }
    }
    
    /**
     * Result of encoding operation containing detailed information
     */
    public static class EncodingResult {
        private final int[] ids;
        private final String[] tokens;
        private final int[] startOffsets;
        private final int[] endOffsets;
        
        public EncodingResult(int[] ids, String[] tokens, int[] startOffsets, int[] endOffsets) {
            this.ids = ids;
            this.tokens = tokens;
            this.startOffsets = startOffsets;
            this.endOffsets = endOffsets;
        }
        
        public int[] getIds() { return ids; }
        public String[] getTokens() { return tokens; }
        public int[] getStartOffsets() { return startOffsets; }
        public int[] getEndOffsets() { return endOffsets; }
        public int getLength() { return ids.length; }
        
        @Override
        public String toString() {
            return "EncodingResult{" +
                   "ids=" + Arrays.toString(ids) +
                   ", tokens=" + Arrays.toString(tokens) +
                   ", startOffsets=" + Arrays.toString(startOffsets) +
                   ", endOffsets=" + Arrays.toString(endOffsets) +
                   '}';
        }
    }
}
