package ai.kompile.process.release;

import java.util.List;

/**
 * Raised when deployment or activation preflight cannot verify release artifacts.
 */
public class ReleaseDeploymentException extends RuntimeException {

    private final List<String> errors;

    public ReleaseDeploymentException(List<String> errors) {
        super("Release deployment readiness check failed");
        this.errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public List<String> getErrors() {
        return errors;
    }
}
