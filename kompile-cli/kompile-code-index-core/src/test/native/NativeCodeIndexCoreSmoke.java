package ai.kompile.cli.main.codeindex;

import ai.kompile.cli.common.util.JsonUtils;

import java.nio.file.Files;
import java.nio.file.Path;

/** Real structural indexing, persistence and retrieval with no graph-learning host on the classpath. */
public final class NativeCodeIndexCoreSmoke {
    public static void main(String[] args) throws Exception {
        Path root = Files.createDirectories(Path.of(args[0]));
        Path home = Files.createDirectories(Path.of(args[1]));
        System.setProperty("user.home", home.toString());
        Files.writeString(root.resolve("NativeIndexed.java"), """
                package fixture;
                public class NativeIndexed {
                    public String greet() { return "hello"; }
                }
                """);
        String project = "native-structural-core";
        var indexer = new LocalCodeIndexer();
        var indexed = indexer.index(root, project, "*.java", null, true, System.out);
        if (indexed.errors() != 0 || indexed.entitiesFound() < 2 || indexed.filesProcessed() != 1) {
            throw new AssertionError("Structural indexing failed: " + indexed);
        }
        Path directory = LocalCodeIndexer.getIndexDir(project);
        var store = new IndexFileStore(directory, JsonUtils.standardMapper());
        if (store.readAllShardsStrict().size() != 1 || store.loadFingerprints().size() != 1) {
            throw new AssertionError("Native shard/fingerprint serialization failed");
        }
        if (new LocalCodeIndexer().search(project, "NativeIndexed", null, 10).isEmpty()) {
            throw new AssertionError("Persisted SQLite search failed");
        }
        // A null optional root disables Git-derived ranking signals in this no-Git qualification.
        if (CodeRelevanceRanker.rankedSearch(project, "NativeIndexed", directory, null, 5).results().isEmpty()) {
            throw new AssertionError("Structural ranking failed");
        }
        if (BlendedCodeSearch.search(project, "NativeIndexed", directory, null, 5).results().isEmpty()) {
            throw new AssertionError("Blended structural retrieval failed");
        }
        CodeGraphReasoningConfig.updateProject(root,
                "{\"enabled\":true,\"kgeTraining\":true,\"kgeDim\":16,\"triggers\":[\"manual\"]}");
        var config = CodeGraphReasoningConfig.loadEffective(root);
        if (!config.isEnabled() || !config.isKgeTraining() || config.getKgeDim() != 16
                || !config.triggersOn("manual") || config.triggersOn("build")) {
            throw new AssertionError("Native learning configuration round-trip lost settings");
        }
        if (Files.exists(root.resolve("data/crawls"))) {
            throw new AssertionError("Structural core must not publish/train a graph implicitly");
        }
        System.out.println("CODE_INDEX_CORE_NATIVE_OK");
    }
}
