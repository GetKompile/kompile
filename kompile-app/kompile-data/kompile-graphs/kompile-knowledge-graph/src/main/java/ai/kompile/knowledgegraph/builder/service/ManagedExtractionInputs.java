package ai.kompile.knowledgegraph.builder.service;

import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bounded full-text inputs for explicit managed extraction. Never substitute a preview for source text. */
public final class ManagedExtractionInputs {
    public static final int MAX_CHARS = 200_000;
    private ManagedExtractionInputs() {}

    public static List<RetrievedDoc> resolve(KnowledgeGraphService store, Long factSheetId,
                                            List<String> ids, List<RetrievedDoc> supplied) {
        List<RetrievedDoc> result = new ArrayList<>();
        if (supplied != null && !supplied.isEmpty()) {
            result.addAll(supplied);
        } else {
            if (store == null || factSheetId == null || factSheetId <= 0) {
                throw new IllegalArgumentException("Supply full chunk text or a managed factSheetId with stored full text");
            }
            List<GraphNode> nodes;
            if (ids != null && !ids.isEmpty()) {
                nodes = ids.stream().map(id -> store.getNode(id).orElseThrow(
                        () -> new IllegalArgumentException("Unknown source chunk: " + id))).toList();
            } else {
                nodes = store.getNodesByTypeInFactSheet(factSheetId, NodeLevel.DOCUMENT);
            }
            if (nodes == null) nodes = List.of();
            for (GraphNode node : nodes) {
                if (!Objects.equals(factSheetId, node.getFactSheetId())) {
                    throw new IllegalArgumentException("Source chunk belongs to another fact sheet: " + node.getNodeId());
                }
                Map<String, Object> metadata = node.getMetadata();
                Object value = metadata == null ? null : metadata.get("text");
                if (!(value instanceof String text) || text.isBlank()) {
                    value = metadata == null ? null : metadata.get("content");
                }
                if (!(value instanceof String text) || text.isBlank()) {
                    throw new IllegalArgumentException("Full source text unavailable for " + node.getNodeId()
                            + "; supply text/chunkTexts explicitly (descriptions and contentPreview are not source text)");
                }
                result.add(new RetrievedDoc(node.getNodeId(), text, Map.of("factSheetId", factSheetId)));
            }
        }
        long chars = 0;
        if (result.isEmpty() || result.size() > 1_000) throw new IllegalArgumentException("Provide 1-1000 non-empty chunks");
        for (RetrievedDoc chunk : result) {
            if (chunk == null || chunk.getText() == null || chunk.getText().isBlank()) {
                throw new IllegalArgumentException("Source chunks must contain text");
            }
            chars += chunk.getText().length();
            if (chars > MAX_CHARS) throw new IllegalArgumentException("Extraction exceeds 200000 characters; split the request");
        }
        return List.copyOf(result);
    }
}
