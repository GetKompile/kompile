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

package ai.kompile.process.discovery.mining.extract;

import ai.kompile.process.discovery.mining.log.EventLog;
import ai.kompile.process.discovery.mining.log.Trace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Splits an event log into per-process sub-logs before mining. One fact sheet routinely holds
 * SEVERAL unrelated workflows (invoicing and hiring, say); mining them as one log glues them into
 * a single incoherent tree of XOR/flower structure. Traces are clustered by activity-set overlap:
 * two traces belong to the same business process when their activity vocabularies overlap
 * (single-link union-find over Jaccard similarity ≥ threshold).
 *
 * <p>Cost is bounded by deduplicating traces to their distinct activity-set <em>variants</em>
 * first — pairwise comparison runs over variants (typically few), not traces (possibly many).
 *
 * <p>Never a silent cap: when {@code maxClusters} truncates, the dropped cluster count and their
 * trace totals are logged at WARN.
 */
public final class TraceClusterer {

    private static final Logger log = LoggerFactory.getLogger(TraceClusterer.class);

    private TraceClusterer() {
    }

    /**
     * Cluster the log's traces into per-process sub-logs.
     *
     * @param eventLog         the full extracted log
     * @param jaccardThreshold minimum activity-set Jaccard similarity for two trace variants to be
     *                         the same process (in (0,1]; lower links more aggressively)
     * @param maxClusters      mine at most this many clusters (largest by trace count first)
     * @return sub-logs ordered by trace count descending; a single-element list when everything
     *         links into one process (the common case for genuinely one-process fact sheets)
     */
    public static List<EventLog> cluster(EventLog eventLog, double jaccardThreshold, int maxClusters) {
        if (eventLog == null || eventLog.isEmpty()) {
            return List.of();
        }
        List<Trace> traces = eventLog.traces();
        if (traces.size() == 1) {
            return List.of(eventLog);
        }

        // 1. Dedup traces to activity-set variants (sorted-set key), remembering members.
        Map<Set<String>, List<Trace>> byVariant = new LinkedHashMap<>();
        for (Trace t : traces) {
            Set<String> activitySet = new TreeSet<>(t.activitySequence());
            byVariant.computeIfAbsent(activitySet, k -> new ArrayList<>()).add(t);
        }
        List<Set<String>> variants = new ArrayList<>(byVariant.keySet());
        if (variants.size() == 1) {
            return List.of(eventLog);
        }

        // 2. Cluster SIGNATURES exclude communication carriers (Email Message, Attachment, …):
        //    every email-borne workflow contains them, so a carriers-only variant (a newsletter)
        //    is a Jaccard subset of everything and single-link chains unrelated workflows into one
        //    mega-cluster. Carriers-only variants carry no business content: when any
        //    business-bearing variant exists they are excluded from mining (logged, never silent);
        //    when the whole log is carriers-only (email-scaffold-only crawl), fall back to raw
        //    activity sets so mining degrades gracefully instead of yielding nothing.
        List<Set<String>> signatures = new ArrayList<>(variants.size());
        List<Integer> businessIdx = new ArrayList<>();
        for (int i = 0; i < variants.size(); i++) {
            Set<String> signature = new TreeSet<>();
            for (String activity : variants.get(i)) {
                if (!ActivityClassifier.isCommunicationScaffoldLabel(activity)) {
                    signature.add(activity);
                }
            }
            signatures.add(signature);
            if (!signature.isEmpty()) {
                businessIdx.add(i);
            }
        }
        boolean scaffoldOnlyLog = businessIdx.isEmpty();
        List<Integer> clusterable = scaffoldOnlyLog
                ? new ArrayList<>() : businessIdx;
        if (scaffoldOnlyLog) {
            for (int i = 0; i < variants.size(); i++) {
                clusterable.add(i);
            }
        } else if (businessIdx.size() < variants.size()) {
            int scaffoldTraces = 0;
            for (int i = 0; i < variants.size(); i++) {
                if (signatures.get(i).isEmpty()) {
                    scaffoldTraces += byVariant.get(variants.get(i)).size();
                }
            }
            log.info("Trace clustering: {} carriers-only trace(s) (no business activities) excluded "
                    + "from process mining", scaffoldTraces);
        }

        // 3. Single-link union-find over pairwise Jaccard of the clusterable signatures.
        int[] parent = new int[variants.size()];
        for (int i = 0; i < parent.length; i++) {
            parent[i] = i;
        }
        for (int a = 0; a < clusterable.size(); a++) {
            for (int b = a + 1; b < clusterable.size(); b++) {
                int i = clusterable.get(a);
                int j = clusterable.get(b);
                Set<String> si = scaffoldOnlyLog ? variants.get(i) : signatures.get(i);
                Set<String> sj = scaffoldOnlyLog ? variants.get(j) : signatures.get(j);
                if (jaccard(si, sj) >= jaccardThreshold) {
                    union(parent, i, j);
                }
            }
        }

        // 4. Collect clusters (traces grouped by variant component), largest first.
        Map<Integer, List<Trace>> byRoot = new LinkedHashMap<>();
        for (int i : clusterable) {
            byRoot.computeIfAbsent(find(parent, i), k -> new ArrayList<>())
                    .addAll(byVariant.get(variants.get(i)));
        }
        List<List<Trace>> clusters = new ArrayList<>(byRoot.values());
        clusters.sort(Comparator.comparingInt((List<Trace> c) -> c.size()).reversed());

        if (clusters.size() == 1) {
            // Reuse the original log only when nothing was excluded; otherwise the excluded
            // carriers-only traces would be resurrected.
            return clusters.get(0).size() == traces.size()
                    ? List.of(eventLog)
                    : List.of(new EventLog(clusters.get(0)));
        }
        int keep = Math.max(1, maxClusters);
        if (clusters.size() > keep) {
            int droppedClusters = clusters.size() - keep;
            int droppedTraces = clusters.subList(keep, clusters.size()).stream()
                    .mapToInt(List::size).sum();
            log.warn("Trace clustering: {} clusters found, mining the {} largest — "
                            + "{} smaller cluster(s) covering {} trace(s) are NOT mined this run",
                    clusters.size(), keep, droppedClusters, droppedTraces);
            clusters = clusters.subList(0, keep);
        }

        List<EventLog> logs = new ArrayList<>(clusters.size());
        for (List<Trace> cluster : clusters) {
            logs.add(new EventLog(cluster));
        }
        return logs;
    }

    /** The distinct activities of a sub-log — used to label per-cluster suggestions. */
    public static Set<String> activitiesOf(EventLog eventLog) {
        return new LinkedHashSet<>(eventLog.activityNames());
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 1.0;
        }
        int intersection = 0;
        for (String s : a) {
            if (b.contains(s)) {
                intersection++;
            }
        }
        int union = a.size() + b.size() - intersection;
        return union == 0 ? 0.0 : intersection / (double) union;
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra != rb) {
            parent[rb] = ra;
        }
    }
}
