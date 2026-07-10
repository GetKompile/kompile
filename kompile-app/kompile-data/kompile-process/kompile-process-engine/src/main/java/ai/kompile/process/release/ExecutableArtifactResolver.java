package ai.kompile.process.release;

/**
 * Resolves and verifies immutable executable content at runtime.
 */
public interface ExecutableArtifactResolver {

    ResolvedExecutable resolve(ProcessRelease release, ExecutableRef reference);

    record ResolvedExecutable(ArtifactManifest manifest, String content, String language) {
    }
}
