package ai.kompile.compute.graph.drools;

import ai.kompile.compute.graph.model.ComputeNode;
import lombok.extern.slf4j.Slf4j;
import org.drools.decisiontable.DecisionTableProviderImpl;
import org.kie.api.KieBase;
import org.kie.api.KieServices;
import org.kie.api.builder.*;
import org.kie.api.io.Resource;
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.KieContainer;
import org.kie.internal.builder.DecisionTableConfiguration;
import org.kie.internal.builder.DecisionTableInputType;
import org.kie.internal.builder.KnowledgeBuilderFactory;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Compiles Drools decision tables (XLS/CSV spreadsheets) into executable KieBase instances.
 * <p>
 * Decision tables allow business analysts to define rules in a familiar spreadsheet format.
 * The compiler converts these to DRL, then compiles to a KieBase for execution.
 * <p>
 * The node's {@code script} field can contain:
 * <ul>
 *   <li>Base64-encoded XLS/XLSX content (detected by prefix or parameter)</li>
 *   <li>CSV decision table content (plain text)</li>
 *   <li>Pre-generated DRL from a decision table (passed through to standard compilation)</li>
 * </ul>
 * <p>
 * Node parameters:
 * <ul>
 *   <li>{@code inputType}: "XLS" or "CSV" (auto-detected if not specified)</li>
 *   <li>{@code worksheetName}: Specific worksheet name for XLS files</li>
 * </ul>
 */
@Slf4j
public class DroolsDecisionTableCompiler {

    private final Map<String, KieBase> compiledCache = new ConcurrentHashMap<>();
    private final Map<String, String> drlCache = new ConcurrentHashMap<>();

    /**
     * Compile a decision table from a compute node into an executable KieBase.
     */
    public KieBase compile(ComputeNode node) {
        String cacheKey = node.getId() + ":dt:" + node.getScript().hashCode();
        return compiledCache.computeIfAbsent(cacheKey, k -> doCompile(node));
    }

    /**
     * Convert a decision table to DRL without compiling.
     * Useful for debugging or inspecting the generated rules.
     */
    public String toDrl(ComputeNode node) {
        String cacheKey = node.getId() + ":drl:" + node.getScript().hashCode();
        return drlCache.computeIfAbsent(cacheKey, k -> convertToDrl(node));
    }

    /**
     * Invalidate caches for a specific node.
     */
    public void invalidate(String nodeId) {
        compiledCache.entrySet().removeIf(e -> e.getKey().startsWith(nodeId + ":"));
        drlCache.entrySet().removeIf(e -> e.getKey().startsWith(nodeId + ":"));
    }

    public void clearCache() {
        compiledCache.clear();
        drlCache.clear();
    }

    private KieBase doCompile(ComputeNode node) {
        DecisionTableInputType inputType = detectInputType(node);
        String drl = convertToDrl(node, inputType);

        log.debug("Generated DRL from decision table for node '{}' ({} chars)", node.getName(), drl.length());

        // Compile the generated DRL
        KieServices kieServices = KieServices.Factory.get();
        KieFileSystem kfs = kieServices.newKieFileSystem();
        String resourcePath = "src/main/resources/rules/dt_" + sanitize(node.getId()) + ".drl";
        Resource resource = kieServices.getResources()
                .newByteArrayResource(drl.getBytes(StandardCharsets.UTF_8))
                .setResourceType(ResourceType.DRL)
                .setSourcePath(resourcePath);
        kfs.write(resourcePath, resource);

        KieBuilder kieBuilder = kieServices.newKieBuilder(kfs).buildAll();
        Results results = kieBuilder.getResults();

        if (results.hasMessages(Message.Level.ERROR)) {
            StringBuilder errors = new StringBuilder("Decision table DRL compilation errors:\n");
            for (Message msg : results.getMessages(Message.Level.ERROR)) {
                errors.append("  Line ").append(msg.getLine()).append(": ").append(msg.getText()).append("\n");
            }
            errors.append("\nGenerated DRL:\n").append(drl);
            throw new IllegalArgumentException(errors.toString());
        }

        KieContainer kieContainer = kieServices.newKieContainer(
                kieServices.getRepository().getDefaultReleaseId());
        return kieContainer.getKieBase();
    }

    private String convertToDrl(ComputeNode node) {
        return convertToDrl(node, detectInputType(node));
    }

    private String convertToDrl(ComputeNode node, DecisionTableInputType inputType) {
        DecisionTableProviderImpl provider = new DecisionTableProviderImpl();
        DecisionTableConfiguration dtConfig = KnowledgeBuilderFactory.newDecisionTableConfiguration();
        dtConfig.setInputType(inputType);

        String worksheetName = node.getParameters() != null
                ? (String) node.getParameters().get("worksheetName")
                : null;
        if (worksheetName != null) {
            dtConfig.setWorksheetName(worksheetName);
        }

        // Build a KIE Resource from the script content
        KieServices kieServices = KieServices.Factory.get();
        byte[] contentBytes;
        if (inputType == DecisionTableInputType.XLS) {
            contentBytes = Base64.getDecoder().decode(node.getScript().trim());
        } else {
            contentBytes = node.getScript().getBytes(StandardCharsets.UTF_8);
        }

        Resource dtResource = kieServices.getResources()
                .newByteArrayResource(contentBytes)
                .setResourceType(inputType == DecisionTableInputType.XLS ? ResourceType.DTABLE : ResourceType.DTABLE);

        String drl = provider.loadFromResource(dtResource, dtConfig);

        // Cache the generated DRL
        String drlCacheKey = node.getId() + ":drl:" + node.getScript().hashCode();
        drlCache.put(drlCacheKey, drl);

        return drl;
    }

    private DecisionTableInputType detectInputType(ComputeNode node) {
        // Check explicit parameter
        if (node.getParameters() != null) {
            String inputTypeParam = (String) node.getParameters().get("inputType");
            if ("XLS".equalsIgnoreCase(inputTypeParam) || "XLSX".equalsIgnoreCase(inputTypeParam)) {
                return DecisionTableInputType.XLS;
            }
            if ("CSV".equalsIgnoreCase(inputTypeParam)) {
                return DecisionTableInputType.CSV;
            }
        }

        // Auto-detect: if the script looks like Base64, assume XLS
        String script = node.getScript().trim();
        if (isLikelyBase64(script)) {
            return DecisionTableInputType.XLS;
        }

        // Default to CSV
        return DecisionTableInputType.CSV;
    }

    private boolean isLikelyBase64(String text) {
        if (text.length() < 20) return false;
        // Base64 contains only alphanumeric, +, /, = and no commas or newlines at the start
        String firstLine = text.split("\n")[0].trim();
        return firstLine.matches("^[A-Za-z0-9+/=]+$") && firstLine.length() > 50;
    }

    private String sanitize(String input) {
        if (input == null) return "unnamed";
        return input.replaceAll("[^a-zA-Z0-9_]", "_");
    }
}
