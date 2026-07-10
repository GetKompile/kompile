package ai.kompile.process.release;

/**
 * Promotion lifecycle for a deployable, immutable process bundle.
 */
public enum ProcessReleaseStatus {
    DRAFT,
    VALIDATED,
    PUBLISHED,
    DEPLOYED,
    ACTIVE,
    FAILED,
    DRAINING,
    RETIRED
}
