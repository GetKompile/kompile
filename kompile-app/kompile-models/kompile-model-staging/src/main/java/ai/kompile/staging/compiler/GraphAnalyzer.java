/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.staging.compiler;

import ai.kompile.staging.web.dto.GraphInfoResponse;
import org.nd4j.autodiff.samediff.SDVariable;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.autodiff.samediff.VariableType;
import org.nd4j.autodiff.samediff.internal.SameDiffOp;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Performs structural analysis of SameDiff computation graphs.
 * Detects layer patterns, parameter counts, attention heads, fused operations, etc.
 */
public class GraphAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(GraphAnalyzer.class);

    private static final Set<String> FUSED_OP_NAMES = Set.of(
            "xw_plus_b", "dot_product_attention", "dot_product_attention_v2",
            "rms_norm", "layer_norm_bp", "fused_batch_norm", "swish"
    );

    private static final Set<String> ATTENTION_OP_NAMES = Set.of(
            "dot_product_attention", "dot_product_attention_v2",
            "multi_head_dot_product_attention", "scaled_dot_product_attention"
    );

    /**
     * Analyze a SameDiff graph and return structural analysis.
     */
    public static GraphInfoResponse.GraphAnalysis analyze(SameDiff sd) {
        try {
            Map<String, SameDiffOp> ops = sd.getOps();

            // Parameter count and data type breakdown
            long parameterCount = 0;
            int constantCount = 0;
            Map<String, Long> parametersByDataType = new LinkedHashMap<>();

            for (SDVariable var : sd.variables()) {
                VariableType vt = var.getVariableType();
                if (vt == VariableType.VARIABLE || vt == VariableType.CONSTANT) {
                    if (vt == VariableType.CONSTANT) {
                        constantCount++;
                    }
                    try {
                        INDArray arr = var.getArr();
                        if (arr != null) {
                            long len = arr.length();
                            parameterCount += len;
                            String dtName = arr.dataType().name();
                            parametersByDataType.merge(dtName, len, Long::sum);
                        }
                    } catch (Exception e) {
                        // Variable may not have array initialized
                    }
                }
            }

            // Graph depth (longest path from any input to any output)
            int graphDepth = computeGraphDepth(sd, ops);

            // Detect fused operations
            int fusedOpCount = 0;
            boolean hasAttentionFusion = false;
            boolean hasLinearFusion = false;
            int attentionHeads = 0;

            for (SameDiffOp op : ops.values()) {
                if (op.getOp() == null) continue;
                String opName = op.getOp().opName();
                if (FUSED_OP_NAMES.contains(opName)) {
                    fusedOpCount++;
                }
                if (ATTENTION_OP_NAMES.contains(opName)) {
                    hasAttentionFusion = true;
                    attentionHeads++;
                }
                if ("xw_plus_b".equals(opName)) {
                    hasLinearFusion = true;
                }
            }

            // Detect attention heads from reshape patterns if no fused attention ops
            if (attentionHeads == 0) {
                attentionHeads = detectAttentionHeadsFromPatterns(sd, ops);
            }

            // Detect layer groups (repeated op sequences)
            List<GraphInfoResponse.LayerGroup> layerGroups = detectLayerGroups(ops);

            // Memory estimate
            long memoryEstimateBytes = estimateMemory(sd, parametersByDataType);

            return GraphInfoResponse.GraphAnalysis.builder()
                    .graphDepth(graphDepth)
                    .parameterCount(parameterCount)
                    .parametersByDataType(parametersByDataType)
                    .constantCount(constantCount)
                    .layerGroups(layerGroups)
                    .attentionHeads(attentionHeads)
                    .hasAttentionFusion(hasAttentionFusion)
                    .hasLinearFusion(hasLinearFusion)
                    .fusedOpCount(fusedOpCount)
                    .memoryEstimateBytes(memoryEstimateBytes)
                    .build();

        } catch (Exception e) {
            log.error("Graph analysis failed: {}", e.getMessage(), e);
            return GraphInfoResponse.GraphAnalysis.builder()
                    .graphDepth(0)
                    .parameterCount(0)
                    .build();
        }
    }

    /**
     * Compute the longest path from any input to any output.
     */
    private static int computeGraphDepth(SameDiff sd, Map<String, SameDiffOp> ops) {
        try {
            List<String> outputs = sd.outputs();
            if (outputs == null || outputs.isEmpty()) return 0;

            // Build adjacency: variable -> ops that consume it
            Map<String, Integer> depthCache = new HashMap<>();
            int maxDepth = 0;

            for (String output : outputs) {
                int depth = computeDepthForVariable(output, sd, ops, depthCache, new HashSet<>());
                maxDepth = Math.max(maxDepth, depth);
            }

            return maxDepth;
        } catch (Exception e) {
            log.debug("Could not compute graph depth: {}", e.getMessage());
            return 0;
        }
    }

    private static int computeDepthForVariable(String varName, SameDiff sd, Map<String, SameDiffOp> ops,
                                                Map<String, Integer> cache, Set<String> visited) {
        if (cache.containsKey(varName)) return cache.get(varName);
        if (visited.contains(varName)) return 0; // cycle protection
        visited.add(varName);

        SDVariable var = sd.getVariable(varName);
        if (var == null) return 0;

        // If this is an input/placeholder/constant, depth = 0
        VariableType vt = var.getVariableType();
        if (vt == VariableType.PLACEHOLDER || vt == VariableType.CONSTANT || vt == VariableType.VARIABLE) {
            cache.put(varName, 0);
            return 0;
        }

        // Find the op that produces this variable by searching the ops map
        SameDiffOp op = null;
        for (SameDiffOp candidate : ops.values()) {
            List<String> outputs = candidate.getOutputsOfOp();
            if (outputs != null && outputs.contains(varName)) {
                op = candidate;
                break;
            }
        }
        if (op == null || op.getInputsToOp() == null) {
            cache.put(varName, 0);
            return 0;
        }

        int maxInputDepth = 0;
        for (String input : op.getInputsToOp()) {
            int d = computeDepthForVariable(input, sd, ops, cache, visited);
            maxInputDepth = Math.max(maxInputDepth, d);
        }

        int depth = maxInputDepth + 1;
        cache.put(varName, depth);
        return depth;
    }

    /**
     * Detect attention head count from Q/K/V reshape patterns.
     */
    private static int detectAttentionHeadsFromPatterns(SameDiff sd, Map<String, SameDiffOp> ops) {
        // Look for batched_gemm or matmul ops with names containing q, k, v patterns
        int headCount = 0;
        for (Map.Entry<String, SameDiffOp> entry : ops.entrySet()) {
            String name = entry.getKey().toLowerCase();
            if (name.contains("attention") && name.contains("reshape")) {
                // Try to detect the head dimension from reshape
                SameDiffOp op = entry.getValue();
                if (op.getOp() != null && "reshape".equals(op.getOp().opName())) {
                    try {
                        List<String> outputs = op.getOutputsOfOp();
                        if (outputs != null && !outputs.isEmpty()) {
                            SDVariable outVar = sd.getVariable(outputs.get(0));
                            if (outVar != null) {
                                long[] shape = outVar.getShape();
                                // For [batch, heads, seq_len, head_dim] patterns
                                if (shape != null && shape.length == 4 && shape[1] > 0) {
                                    headCount = Math.max(headCount, (int) shape[1]);
                                }
                            }
                        }
                    } catch (Exception e) {
                        // ignore
                    }
                }
            }
        }
        return headCount;
    }

    /**
     * Detect repeated layer groups (e.g., transformer blocks).
     */
    private static List<GraphInfoResponse.LayerGroup> detectLayerGroups(Map<String, SameDiffOp> ops) {
        // Group ops by prefix pattern (e.g., "encoder.layer.0.", "encoder.layer.1.")
        Map<String, List<String>> prefixGroups = new LinkedHashMap<>();
        for (String opName : ops.keySet()) {
            // Try to extract a layer number pattern
            String prefix = extractLayerPrefix(opName);
            if (prefix != null) {
                prefixGroups.computeIfAbsent(prefix, k -> new ArrayList<>()).add(opName);
            }
        }

        // Find groups with multiple instances
        Map<String, Integer> groupCounts = new LinkedHashMap<>();
        Map<String, Set<String>> groupOpTypes = new LinkedHashMap<>();

        for (Map.Entry<String, List<String>> entry : prefixGroups.entrySet()) {
            String prefix = entry.getKey();
            // Count how many distinct layer indices exist for this prefix
            Set<String> indices = new HashSet<>();
            for (String opName : entry.getValue()) {
                String idx = extractLayerIndex(opName, prefix);
                if (idx != null) {
                    indices.add(idx);
                }
            }
            if (indices.size() > 1) {
                groupCounts.put(prefix, indices.size());
                // Collect op types from one instance
                Set<String> opTypes = new LinkedHashSet<>();
                for (String opName : entry.getValue()) {
                    SameDiffOp op = ops.get(opName);
                    if (op != null && op.getOp() != null) {
                        opTypes.add(op.getOp().opName());
                    }
                }
                groupOpTypes.put(prefix, opTypes);
            }
        }

        List<GraphInfoResponse.LayerGroup> result = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : groupCounts.entrySet()) {
            String prefix = entry.getKey();
            int count = entry.getValue();
            Set<String> opTypes = groupOpTypes.getOrDefault(prefix, Collections.emptySet());
            int opsPerGroup = prefixGroups.get(prefix).size() / count;

            result.add(GraphInfoResponse.LayerGroup.builder()
                    .name(prefix)
                    .count(count)
                    .opsPerGroup(opsPerGroup)
                    .opTypes(new ArrayList<>(opTypes))
                    .build());
        }

        return result;
    }

    /**
     * Extract a layer prefix like "encoder.layer" from "encoder.layer.3.attention.matmul".
     */
    private static String extractLayerPrefix(String opName) {
        // Match patterns like "xxx.N.yyy" where N is a number
        int lastDot = -1;
        for (int i = 0; i < opName.length(); i++) {
            if (opName.charAt(i) == '.' || opName.charAt(i) == '/') {
                // Check if next segment is numeric
                int nextDot = opName.indexOf('.', i + 1);
                if (nextDot < 0) nextDot = opName.indexOf('/', i + 1);
                if (nextDot < 0) nextDot = opName.length();
                String segment = opName.substring(i + 1, nextDot);
                try {
                    Integer.parseInt(segment);
                    return opName.substring(0, i);
                } catch (NumberFormatException e) {
                    // not numeric, continue
                }
            }
        }
        return null;
    }

    private static String extractLayerIndex(String opName, String prefix) {
        if (!opName.startsWith(prefix)) return null;
        String rest = opName.substring(prefix.length());
        if (rest.isEmpty()) return null;
        // Skip separator
        rest = rest.substring(1);
        int nextSep = rest.indexOf('.');
        if (nextSep < 0) nextSep = rest.indexOf('/');
        if (nextSep < 0) nextSep = rest.length();
        String idx = rest.substring(0, nextSep);
        try {
            Integer.parseInt(idx);
            return idx;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Estimate runtime memory usage in bytes.
     */
    private static long estimateMemory(SameDiff sd, Map<String, Long> parametersByDataType) {
        long totalBytes = 0;

        // Parameters/constants memory
        for (Map.Entry<String, Long> entry : parametersByDataType.entrySet()) {
            try {
                DataType dt = DataType.valueOf(entry.getKey());
                totalBytes += entry.getValue() * dt.width();
            } catch (Exception e) {
                // Default to 4 bytes per element
                totalBytes += entry.getValue() * 4;
            }
        }

        // Rough activation estimate: ~2x parameter memory for single inference
        totalBytes += totalBytes;

        return totalBytes;
    }
}
