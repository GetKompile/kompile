/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.knowledgegraph.reasoning;

import ai.kompile.core.llm.chat.LLMChat;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * D9 Community Reports: on-demand LLM summarisation of a detected community.
 *
 * <p>Given a fact sheet and community id (as returned by
 * {@link GraphCommunityService#detectCommunities}), this service:
 * <ol>
 *   <li>Looks up the member node ids from {@link GraphCommunityService}.</li>
 *   <li>Resolves each member's title and type via {@link KnowledgeGraphService}.</li>
 *   <li>Asks the {@link LLMChat} pool to produce a short natural-language summary
 *       of what the community represents.</li>
 * </ol>
 *
 * <p>The LLM call is <em>lazy</em> (invoked only when this service's endpoint is hit)
 * and <em>resilient</em>: if {@link LLMChat} is unavailable or throws, a graceful
 * fallback message is returned instead of propagating the error.
 *
 * <p>No result caching is performed here — the caller (controller) may add
 * HTTP cache headers or a higher-level cache as needed.
 */
@Slf4j
@Service
public class CommunitySummaryService {

    /** Maximum member nodes included in the prompt digest to keep token cost bounded. */
    private static final int MAX_DIGEST_MEMBERS = 40;

    private final GraphCommunityService communityService;
    private final KnowledgeGraphService graphService;

    /** Optional — injected only when an LLM provider is configured. */
    @Autowired(required = false)
    private LLMChat llmChat;

    /**
     * Primary Spring constructor.
     *
     * @param communityService provides community detection results
     * @param graphService     resolves member node titles/types
     */
    @Autowired
    public CommunitySummaryService(GraphCommunityService communityService,
                                   KnowledgeGraphService graphService) {
        this.communityService = communityService;
        this.graphService = graphService;
    }

    /**
     * Test constructor that accepts an explicit {@link LLMChat} instance.
     *
     * @param communityService provides community detection results
     * @param graphService     resolves member node titles/types
     * @param llmChat          LLM client (may be {@code null} to test the no-LLM path)
     */
    CommunitySummaryService(GraphCommunityService communityService,
                            KnowledgeGraphService graphService,
                            LLMChat llmChat) {
        this.communityService = communityService;
        this.graphService = graphService;
        this.llmChat = llmChat;
    }

    /**
     * DTO returned by
     * {@code GET /api/graph/{factSheetId}/communities/{communityId}/summary}.
     *
     * @param communityId the community whose members were summarised
     * @param summary     natural-language description of the community
     * @param memberCount number of member nodes in the community
     */
    public record CommunitySummaryResult(int communityId, String summary, int memberCount) {}

    /**
     * Summarise a community on demand.
     *
     * <p>Runs community detection (Louvain, default parameters) to discover the
     * members of {@code communityId}, resolves their titles/types from the graph store,
     * then asks the LLM pool for a short natural-language paragraph describing the
     * community's main theme and key members.
     *
     * @param factSheetId the fact sheet the communities were detected over
     * @param communityId the 0-based community id to summarise
     * @return a {@link CommunitySummaryResult} — never throws; falls back gracefully
     */
    public CommunitySummaryResult summarise(long factSheetId, int communityId) {
        log.info("Summarising community factSheetId={} communityId={}", factSheetId, communityId);

        // Re-run community detection with default parameters to obtain membership.
        // This is intentionally on-demand: no pre-computed cache at this layer.
        GraphCommunityService.CommunityResult communities;
        try {
            communities = communityService.detectCommunities(factSheetId, "louvain", 1.0, 42L, 500);
        } catch (Exception ex) {
            log.warn("Community detection failed during summarisation: {}", ex.getMessage(), ex);
            return new CommunitySummaryResult(communityId,
                    "Community summary unavailable: graph data could not be loaded.", 0);
        }

        List<String> memberIds = communities.communityMembers().getOrDefault(communityId, List.of());
        int memberCount = memberIds.size();

        if (memberCount == 0) {
            return new CommunitySummaryResult(communityId,
                    "Community " + communityId + " has no members.", 0);
        }

        // Build a compact text digest of the community members for the LLM prompt.
        String digest = buildDigest(memberIds);
        String summary = invokeLlm(digest, communityId, memberCount);

        return new CommunitySummaryResult(communityId, summary, memberCount);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Builds a plain-text digest listing each member node's title and type.
     * Capped at {@link #MAX_DIGEST_MEMBERS} to keep the LLM prompt short.
     */
    private String buildDigest(List<String> memberIds) {
        List<String> lines = new ArrayList<>(Math.min(memberIds.size(), MAX_DIGEST_MEMBERS));
        int limit = Math.min(memberIds.size(), MAX_DIGEST_MEMBERS);
        for (int i = 0; i < limit; i++) {
            String nodeId = memberIds.get(i);
            try {
                graphService.getNode(nodeId).ifPresentOrElse(
                        node -> lines.add(formatNode(node)),
                        () -> lines.add("- [node " + nodeId + "]")
                );
            } catch (Exception ex) {
                log.debug("Could not resolve node {} for community digest: {}", nodeId, ex.getMessage());
                lines.add("- [node " + nodeId + "]");
            }
        }
        return String.join("\n", lines);
    }

    private static String formatNode(GraphNode node) {
        StringBuilder sb = new StringBuilder("- ");
        sb.append(node.getTitle() != null ? node.getTitle() : node.getNodeId());
        if (node.getNodeType() != null) {
            sb.append(" [").append(node.getNodeType().name()).append("]");
        }
        return sb.toString();
    }

    /**
     * Calls the LLM pool with a community-summarisation prompt.
     * Returns a graceful fallback string if {@link LLMChat} is unavailable or throws.
     */
    private String invokeLlm(String digest, int communityId, int memberCount) {
        if (llmChat == null) {
            log.debug("LLMChat not configured — returning raw digest for community {}", communityId);
            return "Community " + communityId + " (" + memberCount + " members):\n" + digest;
        }

        String prompt = """
                You are analysing a knowledge-graph community. \
                The following nodes belong to the same tightly-connected cluster:

                %s

                Write a concise (2-3 sentence) natural-language description of what this community \
                represents: its main theme, the kinds of entities it contains, and any notable patterns. \
                Be factual and specific. Do not start with "This community".""".formatted(digest);

        try {
            String result = llmChat.prompt().user(prompt).call().content();
            log.debug("LLM summary for community {}: {} chars", communityId,
                    result == null ? 0 : result.length());
            return result != null && !result.isBlank()
                    ? result.strip()
                    : fallback(communityId, memberCount, digest);
        } catch (Exception ex) {
            log.warn("LLM summarisation failed for community {} — falling back: {}", communityId, ex.getMessage());
            return fallback(communityId, memberCount, digest);
        }
    }

    private static String fallback(int communityId, int memberCount, String digest) {
        return "Community " + communityId + " (" + memberCount + " members). "
                + "LLM summary unavailable.\n" + digest;
    }
}
