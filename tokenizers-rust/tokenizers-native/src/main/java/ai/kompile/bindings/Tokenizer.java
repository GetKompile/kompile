package ai.kompile.bindings;

import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.io.IOException;
import java.nio.IntBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.bytedeco.javacpp.*;

/**
 * A user-friendly wrapper around the native tokenizer bindings.
 * This class provides a more intuitive Java API for tokenization operations.
 *
 * <p><b>Thread Safety:</b> This class uses exclusive locking for ALL native operations
 * (encode, decode, etc.) because the underlying native tokenizer is NOT thread-safe
 * for concurrent calls on the same instance. Only one thread can access the native
 * tokenizer at a time.
 *
 * <p><b>Shutdown Coordination:</b> The write lock also coordinates graceful shutdown:
 * <ul>
 *   <li>Operations acquire write lock (ensuring exclusive access)</li>
 *   <li>Close operations also acquire write lock (waiting for current operation)</li>
 *   <li>Shutdown flag prevents new operations from starting</li>
 * </ul>
 *
 * <p><b>Performance Note:</b> If you need concurrent tokenization, create separate
 * Tokenizer instances (one per thread) rather than sharing a single instance.
 */
public class Tokenizer implements AutoCloseable {

    private final TokenizersNative.OpaqueTokenizer nativeTokenizer;
    private final TokenizersNative nativeLib;

    /**
     * Atomic flag indicating shutdown has been initiated.
     * Once true, no new encoding operations will be accepted.
     */
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    /**
     * Lock for exclusive access to native tokenizer operations.
     * ALL operations (encode, decode, etc.) acquire WRITE lock because the native
     * tokenizer is NOT thread-safe for concurrent access on the same instance.
     * This also coordinates shutdown - close() acquires write lock to wait for
     * any in-progress operation.
     */
    private final ReentrantReadWriteLock operationLock = new ReentrantReadWriteLock();

    /**
     * Counter for active operations (for monitoring/debugging).
     */
    private final AtomicInteger activeOperations = new AtomicInteger(0);

    /**
     * Maximum time to wait for active operations during shutdown (in seconds).
     */
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 30;
    
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
     * Check if this tokenizer is valid and not shutting down.
     * @return true if valid and available for operations, false otherwise
     */
    public boolean isValid() {
        if (shuttingDown.get()) return false;
        return nativeLib.tokenizerIsValid(nativeTokenizer);
    }

    /**
     * Check if this tokenizer is currently shutting down.
     * @return true if shutdown has been initiated
     */
    public boolean isShuttingDown() {
        return shuttingDown.get();
    }

    /**
     * Get the number of currently active operations.
     * Useful for monitoring and debugging shutdown behavior.
     * @return count of active operations
     */
    public int getActiveOperationCount() {
        return activeOperations.get();
    }

    /**
     * Initiates shutdown without waiting. After calling this method,
     * new encoding operations will be rejected, but existing operations
     * will be allowed to complete.
     * <p>
     * Use {@link #close()} for a full shutdown that waits for operations.
     */
    public void initiateShutdown() {
        shuttingDown.set(true);
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
     * @throws IllegalStateException if the tokenizer is closed or shutting down
     */
    public int[] encode(String text, boolean addSpecialTokens) {
        // Acquire WRITE lock for exclusive access - native tokenizer is NOT thread-safe
        // for concurrent encode() calls on the same instance. Using write lock ensures
        // only one thread can access the native tokenizer at a time.
        operationLock.writeLock().lock();
        try {
            // Check shutdown status after acquiring lock
            if (shuttingDown.get()) {
                throw new IllegalStateException("Tokenizer is shutting down, cannot encode");
            }

            activeOperations.incrementAndGet();
            try {
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
            } finally {
                activeOperations.decrementAndGet();
            }
        } finally {
            operationLock.writeLock().unlock();
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
     * @throws IllegalStateException if the tokenizer is closed or shutting down
     */
    public EncodingResult encodeWithDetails(String text, boolean addSpecialTokens) {
        // Acquire WRITE lock for exclusive access - native tokenizer is NOT thread-safe
        operationLock.writeLock().lock();
        try {
            // Check shutdown status after acquiring lock
            if (shuttingDown.get()) {
                throw new IllegalStateException("Tokenizer is shutting down, cannot encode");
            }

            activeOperations.incrementAndGet();
            try {
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
                        for (int i = 0; i < offsetCount; i++) {
                            startOffsetsArray[i] = (int) startOffsets.get(i);
                            endOffsetsArray[i] = (int) endOffsets.get(i);
                        }
                    }

                    return new EncodingResult(ids, tokens, startOffsetsArray, endOffsetsArray);
                } finally {
                    nativeLib.freeEncoding(encoding);
                }
            } finally {
                activeOperations.decrementAndGet();
            }
        } finally {
            operationLock.writeLock().unlock();
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
     * @throws IllegalStateException if the tokenizer is closed or shutting down
     */
    public String decode(int[] ids, boolean skipSpecialTokens) {
        // Acquire WRITE lock for exclusive access - native tokenizer is NOT thread-safe
        operationLock.writeLock().lock();
        try {
            // Check shutdown status after acquiring lock
            if (shuttingDown.get()) {
                throw new IllegalStateException("Tokenizer is shutting down, cannot decode");
            }

            activeOperations.incrementAndGet();
            try {
                if (ids == null || ids.length == 0) {
                    return "";
                }

                String result = nativeLib.decodeIds(nativeTokenizer, ids, ids.length, skipSpecialTokens);
                if (result == null) {
                    throw new RuntimeException("Failed to decode token IDs");
                }

                return result;
            } finally {
                activeOperations.decrementAndGet();
            }
        } finally {
            operationLock.writeLock().unlock();
        }
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
        if (shuttingDown.get()) {
            throw new IllegalStateException("Tokenizer has been closed or is shutting down");
        }
    }

    /**
     * Closes this tokenizer and releases all native resources.
     * <p>
     * This method implements graceful shutdown:
     * <ol>
     *   <li>Sets the shutting down flag to reject new operations</li>
     *   <li>Waits for active operations to complete (up to SHUTDOWN_TIMEOUT_SECONDS)</li>
     *   <li>Acquires exclusive write lock</li>
     *   <li>Frees native tokenizer memory</li>
     * </ol>
     * <p>
     * If active operations don't complete within the timeout, the tokenizer
     * will still be closed, but a warning will be logged. This is a best-effort
     * graceful shutdown - it prioritizes not crashing over waiting forever.
     */
    @Override
    public void close() {
        // Set shutdown flag first - this prevents new operations from starting
        if (!shuttingDown.compareAndSet(false, true)) {
            // Already shutting down or closed
            return;
        }

        // Wait for active operations to complete before freeing native memory
        int waitAttempts = 0;
        int maxWaitAttempts = SHUTDOWN_TIMEOUT_SECONDS * 10; // Check every 100ms

        while (activeOperations.get() > 0 && waitAttempts < maxWaitAttempts) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // If interrupted, proceed with shutdown anyway
                break;
            }
            waitAttempts++;
        }

        if (activeOperations.get() > 0) {
            // Log warning but proceed - we can't wait forever
            System.err.println("WARNING: Tokenizer closing with " + activeOperations.get() +
                    " active operations after " + SHUTDOWN_TIMEOUT_SECONDS + "s timeout");
        }

        // Acquire write lock to ensure no operations are in flight
        // This blocks until all read locks (from encode/decode) are released
        operationLock.writeLock().lock();
        try {
            if (nativeTokenizer != null && !nativeTokenizer.isNull()) {
                nativeLib.freeTokenizer(nativeTokenizer);
            }
        } finally {
            operationLock.writeLock().unlock();
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
