package ai.kompile.process.release;

public interface ArtifactMaterializer {
    ArtifactDeployment materialize(ArtifactManifest manifest, byte[] content);
}
