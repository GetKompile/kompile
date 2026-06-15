package ai.kompile.embedding.anserini;

import io.anserini.encoder.samediff.SameDiffEncoder;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Utility class for classifying exceptions as retriable (transient) or non-retriable (permanent).
 *
 * <p>This helps the model loading logic make intelligent retry decisions:
 * <ul>
 *   <li><b>Retriable errors</b>: Network timeouts, connection refused, temporary service unavailability.
 *       These should be retried with exponential backoff.</li>
 *   <li><b>Non-retriable errors</b>: Model validation failures, file corruption, parsing errors,
 *       configuration errors. Retrying won't help - user intervention is required.</li>
 * </ul>
 * </p>
 */
public final class RetryableErrorClassifier {

    private RetryableErrorClassifier() {
        // Utility class - prevent instantiation
    }

    /**
     * Exception types that are always retriable (transient network issues).
     */
    private static final Set<Class<? extends Throwable>> RETRIABLE_EXCEPTION_TYPES = Set.of(
            SocketTimeoutException.class,
            ConnectException.class,
            UnknownHostException.class,
            SocketException.class
    );

    /**
     * Exception types that are never retriable (permanent failures).
     */
    private static final Set<Class<? extends Throwable>> NON_RETRIABLE_EXCEPTION_TYPES = Set.of(
            SameDiffEncoder.ModelValidationException.class,
            SameDiffEncoder.EncodingException.class,
            IllegalArgumentException.class,
            IllegalStateException.class,
            UnsupportedOperationException.class,
            OutOfMemoryError.class,
            NoClassDefFoundError.class,
            ClassNotFoundException.class,
            LinkageError.class
    );

    /**
     * Patterns in error messages that indicate retriable errors.
     */
    private static final Pattern[] RETRIABLE_MESSAGE_PATTERNS = {
            Pattern.compile("(?i)timeout"),
            Pattern.compile("(?i)timed out"),
            Pattern.compile("(?i)connection refused"),
            Pattern.compile("(?i)connection reset"),
            Pattern.compile("(?i)network unreachable"),
            Pattern.compile("(?i)host unreachable"),
            Pattern.compile("(?i)temporarily unavailable"),
            Pattern.compile("(?i)service unavailable"),
            Pattern.compile("(?i)503"),
            Pattern.compile("(?i)502"),
            Pattern.compile("(?i)429"),  // Rate limited
            Pattern.compile("(?i)rate limit"),
            Pattern.compile("(?i)try again"),
            Pattern.compile("(?i)retry"),
            Pattern.compile("(?i)no route to host"),
            Pattern.compile("(?i)connection closed"),
            Pattern.compile("(?i)broken pipe"),
            Pattern.compile("(?i)ssl.*handshake"),
            Pattern.compile("(?i)remote host.*closed"),
            Pattern.compile("(?i)staging service"),  // Staging service issues are retriable
            Pattern.compile("(?i)not available after.*attempts")  // Staging retry exhausted
    };

    /**
     * Patterns in error messages that indicate non-retriable errors.
     */
    private static final Pattern[] NON_RETRIABLE_MESSAGE_PATTERNS = {
            Pattern.compile("(?i)model validation failed"),
            Pattern.compile("(?i)zero.?magnitude"),
            Pattern.compile("(?i)corrupted"),
            Pattern.compile("(?i)invalid.*format"),
            Pattern.compile("(?i)malformed"),
            Pattern.compile("(?i)parse error"),
            Pattern.compile("(?i)unsupported"),
            Pattern.compile("(?i)not compatible"),
            Pattern.compile("(?i)incompatible"),
            Pattern.compile("(?i)cannot.*load"),
            Pattern.compile("(?i)failed to load"),
            Pattern.compile("(?i)onnx.*error"),
            Pattern.compile("(?i)onnx.*import"),
            Pattern.compile("(?i)model file.*not found"),
            Pattern.compile("(?i)vocabulary.*not found"),
            Pattern.compile("(?i)tokenizer.*error"),
            Pattern.compile("(?i)out of memory"),
            Pattern.compile("(?i)heap space"),
            Pattern.compile("(?i)gc overhead"),
            Pattern.compile("(?i)native.*library"),
            Pattern.compile("(?i)jni.*error"),
            Pattern.compile("(?i)unsatisfied.*link"),
            Pattern.compile("(?i)class.*not found"),
            Pattern.compile("(?i)no such file"),
            Pattern.compile("(?i)file not found"),
            Pattern.compile("(?i)invalid model"),
            Pattern.compile("(?i)broken model"),
            Pattern.compile("(?i)model.*corrupt"),
            Pattern.compile("(?i)checksum.*mismatch"),
            Pattern.compile("(?i)sha256.*mismatch"),
            Pattern.compile("(?i)403"),  // Forbidden - auth issue
            Pattern.compile("(?i)401"),  // Unauthorized - auth issue
            Pattern.compile("(?i)404")   // Not found - model doesn't exist
    };

    /**
     * Determines if an exception represents a retriable (transient) error.
     *
     * <p>The classification logic:
     * <ol>
     *   <li>Check if the exception is a ModelLoadingException with explicit retry info</li>
     *   <li>Check if the exception type is in the known non-retriable list (immediate false)</li>
     *   <li>Check if the exception type is in the known retriable list (immediate true)</li>
     *   <li>Analyze the exception message for retriable patterns</li>
     *   <li>Analyze the exception message for non-retriable patterns</li>
     *   <li>Check the cause chain recursively</li>
     *   <li>Default to non-retriable for unknown errors (fail-safe)</li>
     * </ol>
     * </p>
     *
     * @param e The exception to classify
     * @return true if the error is transient and should be retried, false otherwise
     */
    public static boolean isRetriable(Throwable e) {
        if (e == null) {
            return false;
        }

        // FIRST: Check if this is our own ModelLoadingException with explicit retry info
        // This takes precedence over all other classification logic
        if (e instanceof ModelLoadingException) {
            return ((ModelLoadingException) e).isRetriable();
        }

        // Check the exception and its cause chain
        Throwable current = e;
        boolean foundRetriableIndicator = false;

        while (current != null) {
            // Check if any exception in the chain is our ModelLoadingException
            if (current instanceof ModelLoadingException) {
                return ((ModelLoadingException) current).isRetriable();
            }

            // Check exception type against known non-retriable types first
            for (Class<? extends Throwable> nonRetriableType : NON_RETRIABLE_EXCEPTION_TYPES) {
                if (nonRetriableType.isInstance(current)) {
                    return false;  // Definitely non-retriable
                }
            }

            // Check exception type against known retriable types
            for (Class<? extends Throwable> retriableType : RETRIABLE_EXCEPTION_TYPES) {
                if (retriableType.isInstance(current)) {
                    foundRetriableIndicator = true;
                }
            }

            // Check message for patterns
            String message = current.getMessage();
            if (message != null && !message.isBlank()) {
                // Check non-retriable patterns first (they take precedence)
                for (Pattern pattern : NON_RETRIABLE_MESSAGE_PATTERNS) {
                    if (pattern.matcher(message).find()) {
                        return false;  // Found a non-retriable pattern
                    }
                }

                // Check retriable patterns
                for (Pattern pattern : RETRIABLE_MESSAGE_PATTERNS) {
                    if (pattern.matcher(message).find()) {
                        foundRetriableIndicator = true;
                    }
                }
            }

            // Move to cause
            current = current.getCause();
        }

        // If we found any retriable indicator and no non-retriable indicators, it's retriable
        // Otherwise, default to non-retriable (fail-safe - don't waste resources retrying permanent failures)
        return foundRetriableIndicator;
    }

    /**
     * Determines if an exception represents a non-retriable (permanent) error.
     * This is the inverse of {@link #isRetriable(Throwable)}.
     *
     * @param e The exception to classify
     * @return true if the error is permanent and should not be retried
     */
    public static boolean isNonRetriable(Throwable e) {
        return !isRetriable(e);
    }

    /**
     * Gets a human-readable classification of the error.
     *
     * @param e The exception to classify
     * @return A string describing the error type: "RETRIABLE", "NON_RETRIABLE", or "UNKNOWN"
     */
    public static String classify(Throwable e) {
        if (e == null) {
            return "UNKNOWN";
        }
        return isRetriable(e) ? "RETRIABLE" : "NON_RETRIABLE";
    }

    /**
     * Gets a reason explaining why the error was classified as retriable or non-retriable.
     * Useful for logging and debugging.
     *
     * @param e The exception to analyze
     * @return A human-readable reason for the classification
     */
    public static String getClassificationReason(Throwable e) {
        if (e == null) {
            return "null exception";
        }

        // FIRST: Check for our own ModelLoadingException with explicit retry info
        if (e instanceof ModelLoadingException) {
            ModelLoadingException mle = (ModelLoadingException) e;
            return mle.isRetriable()
                    ? "ModelLoadingException marked as retriable"
                    : "ModelLoadingException marked as permanent (non-retriable)";
        }

        StringBuilder reason = new StringBuilder();
        Throwable current = e;

        while (current != null) {
            // Check for ModelLoadingException in the cause chain
            if (current instanceof ModelLoadingException) {
                ModelLoadingException mle = (ModelLoadingException) current;
                return mle.isRetriable()
                        ? "ModelLoadingException (in cause chain) marked as retriable"
                        : "ModelLoadingException (in cause chain) marked as permanent (non-retriable)";
            }

            // Check exception type
            for (Class<? extends Throwable> nonRetriableType : NON_RETRIABLE_EXCEPTION_TYPES) {
                if (nonRetriableType.isInstance(current)) {
                    return "Non-retriable exception type: " + current.getClass().getSimpleName();
                }
            }

            for (Class<? extends Throwable> retriableType : RETRIABLE_EXCEPTION_TYPES) {
                if (retriableType.isInstance(current)) {
                    return "Retriable exception type: " + current.getClass().getSimpleName();
                }
            }

            // Check message patterns
            String message = current.getMessage();
            if (message != null && !message.isBlank()) {
                for (Pattern pattern : NON_RETRIABLE_MESSAGE_PATTERNS) {
                    if (pattern.matcher(message).find()) {
                        return "Non-retriable pattern in message: " + pattern.pattern();
                    }
                }

                for (Pattern pattern : RETRIABLE_MESSAGE_PATTERNS) {
                    if (pattern.matcher(message).find()) {
                        return "Retriable pattern in message: " + pattern.pattern();
                    }
                }
            }

            current = current.getCause();
        }

        return "No specific pattern matched - defaulting to non-retriable";
    }

    /**
     * Wraps an exception with retry classification information.
     * The resulting exception can be queried for its retriable status.
     *
     * @param message The error message
     * @param cause The original exception
     * @param retriable Whether this error should be considered retriable
     * @return A ModelLoadingException with retry information
     */
    public static ModelLoadingException wrapWithRetryInfo(String message, Throwable cause, boolean retriable) {
        return new ModelLoadingException(message, cause, retriable);
    }

    /**
     * Exception class that carries retry classification information.
     * Use this when you want to explicitly mark an exception as retriable or not.
     */
    public static class ModelLoadingException extends IOException {
        private final boolean retriable;

        public ModelLoadingException(String message, boolean retriable) {
            super(message);
            this.retriable = retriable;
        }

        public ModelLoadingException(String message, Throwable cause, boolean retriable) {
            super(message, cause);
            this.retriable = retriable;
        }

        /**
         * Returns whether this error is retriable (transient).
         * @return true if the error might succeed on retry
         */
        public boolean isRetriable() {
            return retriable;
        }

        /**
         * Returns whether this error is permanent (non-retriable).
         * @return true if retrying won't help
         */
        public boolean isPermanent() {
            return !retriable;
        }
    }
}
