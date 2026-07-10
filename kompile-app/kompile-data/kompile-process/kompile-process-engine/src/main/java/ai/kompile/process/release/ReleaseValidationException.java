package ai.kompile.process.release;

import java.util.List;

/**
 * Raised when release preflight fails. Errors are retained as structured API output.
 */
public class ReleaseValidationException extends IllegalStateException {

    private final List<String> errors;

    public ReleaseValidationException(List<String> errors) {
        super("Process release validation failed: " + String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> getErrors() {
        return errors;
    }
}
