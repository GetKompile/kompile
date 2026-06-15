package ai.kompile.compute.graph.drools;

import ai.kompile.compute.graph.model.ComputeNode;
import lombok.extern.slf4j.Slf4j;
import org.kie.api.KieBase;
import org.kie.api.KieServices;
import org.kie.api.builder.*;
import org.kie.api.io.Resource;
import org.kie.api.runtime.KieContainer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Compiles DRL rule definitions from compute nodes into executable KieBase instances.
 * Caches compiled rules for reuse across multiple executions of the same graph.
 */
@Slf4j
public class DroolsRuleCompiler {

    private final KieServices kieServices;
    private final Map<String, KieBase> compiledCache = new ConcurrentHashMap<>();

    public DroolsRuleCompiler() {
        this.kieServices = KieServices.Factory.get();
    }

    /**
     * Compile DRL source from a compute node into an executable KieBase.
     * Results are cached by node ID + script hash.
     */
    public KieBase compile(ComputeNode node) {
        String cacheKey = node.getId() + ":" + node.getScript().hashCode();
        return compiledCache.computeIfAbsent(cacheKey, k -> doCompile(node));
    }

    /**
     * Invalidate the cache for a specific node (e.g., when script is updated).
     */
    public void invalidate(String nodeId) {
        compiledCache.entrySet().removeIf(e -> e.getKey().startsWith(nodeId + ":"));
    }

    /**
     * Clear all cached compilations.
     */
    public void clearCache() {
        compiledCache.clear();
    }

    private KieBase doCompile(ComputeNode node) {
        String drl = wrapIfNeeded(node.getScript(), node);

        KieFileSystem kfs = kieServices.newKieFileSystem();
        String resourcePath = "src/main/resources/rules/node_" + sanitize(node.getId()) + ".drl";
        Resource resource = kieServices.getResources()
                .newByteArrayResource(drl.getBytes())
                .setResourceType(org.kie.api.io.ResourceType.DRL)
                .setSourcePath(resourcePath);
        kfs.write(resourcePath, resource);

        KieBuilder kieBuilder = kieServices.newKieBuilder(kfs).buildAll();
        Results results = kieBuilder.getResults();

        if (results.hasMessages(Message.Level.ERROR)) {
            StringBuilder errors = new StringBuilder("DRL compilation errors:\n");
            for (Message msg : results.getMessages(Message.Level.ERROR)) {
                errors.append("  Line ").append(msg.getLine()).append(": ").append(msg.getText()).append("\n");
            }
            throw new IllegalArgumentException(errors.toString());
        }

        if (results.hasMessages(Message.Level.WARNING)) {
            for (Message msg : results.getMessages(Message.Level.WARNING)) {
                log.warn("DRL warning for node '{}' at line {}: {}", node.getName(), msg.getLine(), msg.getText());
            }
        }

        KieContainer kieContainer = kieServices.newKieContainer(
                kieServices.getRepository().getDefaultReleaseId());
        return kieContainer.getKieBase();
    }

    /**
     * If the script doesn't contain package/import declarations,
     * wrap it with standard imports for the fact classes.
     */
    private String wrapIfNeeded(String script, ComputeNode node) {
        if (script.trim().startsWith("package ") || script.trim().startsWith("import ")) {
            return script;
        }

        // Wrap bare rule definitions with standard imports
        StringBuilder drl = new StringBuilder();
        drl.append("package ai.kompile.compute.graph.drools.generated;\n\n");
        drl.append("import ai.kompile.compute.graph.drools.NodeFacts;\n");
        drl.append("import ai.kompile.compute.graph.drools.NamedFact;\n");
        drl.append("import java.util.Map;\n");
        drl.append("import java.util.List;\n\n");

        // If the script doesn't start with "rule", wrap it in a rule block
        if (!script.trim().startsWith("rule ")) {
            drl.append("rule \"").append(sanitize(node.getName())).append("\"\n");
            drl.append("  when\n");
            drl.append("    $facts : NodeFacts()\n");
            drl.append("  then\n");
            drl.append("    ").append(script).append("\n");
            drl.append("end\n");
        } else {
            drl.append(script);
        }

        return drl.toString();
    }

    private String sanitize(String input) {
        if (input == null) return "unnamed";
        return input.replaceAll("[^a-zA-Z0-9_]", "_");
    }
}
