package ai.kompile.compute.graph.n8n.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single connection target in an n8n workflow.
 * Connections are stored as:
 * sourceNodeName → connectionType → outputIndex → List of N8nConnection targets.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class N8nConnection {

    /**
     * Target node name.
     */
    private String node;

    /**
     * Connection type: "main" for standard data flow.
     * AI sub-node types: "ai_agent", "ai_chain", "ai_document",
     * "ai_embedding", "ai_languageModel", "ai_memory", "ai_outputParser",
     * "ai_retriever", "ai_reranker", "ai_textSplitter", "ai_tool", "ai_vectorStore".
     */
    @Builder.Default
    private String type = "main";

    /**
     * Input port index on the target node (usually 0).
     */
    @Builder.Default
    private int index = 0;
}
