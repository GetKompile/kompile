package ai.kompile.process.release;

/**
 * Verifies that a release can execute in the current deployment target.
 */
public interface ReleaseDeploymentVerifier {

    DeploymentReadiness verify(ProcessRelease release);
}
