package ai.kompile.compute.graph.camel;

import ai.kompile.core.graphrag.format.GraphExtractionValidator;
import ai.kompile.core.graphrag.model.Graph;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

import java.util.HashMap;
import java.util.Map;

/**
 * A Camel Processor that invokes the kompile graph extractor within a Camel route.
 * <p>
 * This bridges Camel's Enterprise Integration Patterns with kompile's graph
 * extraction pipeline. Documents flowing through Camel routes can be processed
 * to extract knowledge graph entities and relationships.
 * <p>
 * The processor expects the exchange body to contain text content (String)
 * or a Map with a "text" key. It passes the text through the configured
 * graph extraction service and sets the extraction result on the exchange.
 * <p>
 * Usage in a Camel route:
 * <pre>{@code
 * <route>
 *   <from uri="file:documents"/>
 *   <convertBodyTo type="java.lang.String"/>
 *   <process ref="graphExtractorProcessor"/>
 *   <to uri="direct:store-graph"/>
 * </route>
 * }</pre>
 */
@Slf4j
public class GraphExtractorProcessor implements Processor {

    private final Object graphExtractionService;

    /**
     * @param graphExtractionService An implementation of DocumentGraphExtractor or
     *                                ExtractionLlmService. May be null if no extractor
     *                                is configured; in that case, a validation-only
     *                                extraction is performed.
     */
    public GraphExtractorProcessor(Object graphExtractionService) {
        this.graphExtractionService = graphExtractionService;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) throws Exception {
        Object body = exchange.getIn().getBody();
        String text;

        if (body instanceof String) {
            text = (String) body;
        } else if (body instanceof Map) {
            Map<String, Object> bodyMap = (Map<String, Object>) body;
            text = String.valueOf(bodyMap.getOrDefault("text",
                    bodyMap.getOrDefault("content", "")));
        } else {
            text = exchange.getIn().getBody(String.class);
        }

        if (text == null || text.isBlank()) {
            log.debug("Empty text — skipping graph extraction");
            exchange.getIn().setHeader("kompile_graphExtracted", false);
            return;
        }

        try {
            if (graphExtractionService != null) {
                // Invoke the extraction service via reflection to avoid hard coupling
                Graph graph = invokeExtraction(text, exchange);
                if (graph != null) {
                    Map<String, Object> result = new HashMap<>();
                    result.put("graph", graph);
                    result.put("entityCount", graph.getEntities() != null ? graph.getEntities().size() : 0);
                    result.put("relationshipCount", graph.getRelationships() != null ? graph.getRelationships().size() : 0);
                    result.put("sourceText", text.length() > 200 ? text.substring(0, 200) + "..." : text);

                    exchange.getIn().setBody(result);
                    exchange.getIn().setHeader("kompile_graphExtracted", true);
                    exchange.getIn().setHeader("kompile_entityCount",
                            graph.getEntities() != null ? graph.getEntities().size() : 0);
                    exchange.getIn().setHeader("kompile_relationshipCount",
                            graph.getRelationships() != null ? graph.getRelationships().size() : 0);

                    log.debug("Graph extraction completed: {} entities, {} relationships",
                            graph.getEntities() != null ? graph.getEntities().size() : 0,
                            graph.getRelationships() != null ? graph.getRelationships().size() : 0);
                    return;
                }
            }

            // Fallback: provide extraction prompt instructions for downstream LLM processing
            String extractionPrompt = GraphExtractionValidator.getExtractionPromptInstructions();
            Map<String, Object> result = new HashMap<>();
            result.put("text", text);
            result.put("extractionPrompt", extractionPrompt);
            result.put("needsLlmExtraction", true);

            exchange.getIn().setBody(result);
            exchange.getIn().setHeader("kompile_graphExtracted", false);
            exchange.getIn().setHeader("kompile_needsLlmExtraction", true);

        } catch (Exception e) {
            log.error("Graph extraction failed", e);
            exchange.getIn().setHeader("kompile_graphExtracted", false);
            exchange.getIn().setHeader("kompile_extractionError", e.getMessage());
        }
    }

    private Graph invokeExtraction(String text, Exchange exchange) {
        try {
            // Try DocumentGraphExtractor.extract(String)
            var extractMethod = graphExtractionService.getClass().getMethod("extract", String.class);
            Object result = extractMethod.invoke(graphExtractionService, text);
            if (result instanceof Graph) {
                return (Graph) result;
            }
        } catch (NoSuchMethodException e) {
            // Try alternative method signatures
            try {
                var extractMethod = graphExtractionService.getClass().getMethod("extractGraph", String.class);
                Object result = extractMethod.invoke(graphExtractionService, text);
                if (result instanceof Graph) {
                    return (Graph) result;
                }
            } catch (Exception e2) {
                log.debug("No compatible extraction method found on {}", graphExtractionService.getClass().getSimpleName());
            }
        } catch (Exception e) {
            log.warn("Graph extraction invocation failed", e);
        }
        return null;
    }
}
