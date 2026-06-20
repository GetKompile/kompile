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

package ai.kompile.process.discovery.mining.declare;

import ai.kompile.process.discovery.mining.log.EventLog;
import ai.kompile.process.discovery.mining.log.Trace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A MINERful-style declarative miner: in a single pass it evaluates each Declare template over every
 * activity (pair), then keeps the constraints that clear support and confidence thresholds. This is the
 * complementary, rule-based view of a process — what must (not) happen — alongside the imperative process
 * tree from the Inductive Miner. No LLM.
 */
public final class DeclareMiner {

    private DeclareMiner() {
    }

    public static List<DeclareConstraint> mine(EventLog log, double minSupport, double minConfidence) {
        List<List<String>> traces = new ArrayList<>();
        for (Trace t : log.traces()) {
            traces.add(t.activitySequence());
        }
        int total = traces.size();
        if (total == 0) {
            return List.of();
        }
        List<String> activities = new ArrayList<>(log.activityNames());
        List<DeclareConstraint> out = new ArrayList<>();

        // Unary: INIT / END.
        for (String a : activities) {
            long init = traces.stream().filter(t -> !t.isEmpty() && t.get(0).equals(a)).count();
            long end = traces.stream().filter(t -> !t.isEmpty() && t.get(t.size() - 1).equals(a)).count();
            consider(out, DeclareTemplate.INIT, a, null, init, total, total, minSupport, minConfidence);
            consider(out, DeclareTemplate.END, a, null, end, total, total, minSupport, minConfidence);
        }

        // Binary templates.
        for (String a : activities) {
            for (String b : activities) {
                if (a.equals(b)) {
                    continue;
                }
                int actResp = 0, satResp = 0;
                int actPrec = 0, satPrec = 0;
                int actChain = 0, satChain = 0;
                int actNce = 0, satNce = 0;
                for (List<String> t : traces) {
                    boolean hasA = t.contains(a);
                    boolean hasB = t.contains(b);
                    if (hasA) {
                        actResp++;
                        if (responseHolds(t, a, b)) {
                            satResp++;
                        }
                        actChain++;
                        if (chainResponseHolds(t, a, b)) {
                            satChain++;
                        }
                    }
                    if (hasB) {
                        actPrec++;
                        if (precedenceHolds(t, a, b)) {
                            satPrec++;
                        }
                    }
                    if (hasA || hasB) {
                        actNce++;
                        if (!(hasA && hasB)) {
                            satNce++;
                        }
                    }
                }
                consider(out, DeclareTemplate.RESPONSE, a, b, satResp, actResp, total, minSupport, minConfidence);
                consider(out, DeclareTemplate.PRECEDENCE, a, b, satPrec, actPrec, total, minSupport, minConfidence);
                consider(out, DeclareTemplate.CHAIN_RESPONSE, a, b, satChain, actChain, total, minSupport, minConfidence);
                if (a.compareTo(b) < 0) { // NotCoExistence is symmetric — emit once per unordered pair
                    consider(out, DeclareTemplate.NOT_CO_EXISTENCE, a, b, satNce, actNce, total, minSupport, minConfidence);
                }
            }
        }

        out.sort(Comparator.comparingDouble(DeclareConstraint::confidence).reversed());
        return out;
    }

    private static void consider(List<DeclareConstraint> out, DeclareTemplate template, String a, String b,
                                 long satisfied, long activated, int total,
                                 double minSupport, double minConfidence) {
        if (activated <= 0) {
            return; // vacuous: the constraint never applies, so it carries no information
        }
        double confidence = (double) satisfied / activated;
        double support = (double) satisfied / total;
        if (confidence >= minConfidence && support >= minSupport) {
            out.add(new DeclareConstraint(template, a, b, support, confidence));
        }
    }

    /** {@code a} is eventually followed by {@code b}: some b occurs after the last a. */
    private static boolean responseHolds(List<String> t, String a, String b) {
        return t.lastIndexOf(b) > t.lastIndexOf(a);
    }

    /** {@code b} is preceded by {@code a}: an a occurs before the first b. */
    private static boolean precedenceHolds(List<String> t, String a, String b) {
        int firstA = t.indexOf(a);
        return firstA >= 0 && firstA < t.indexOf(b);
    }

    /** Every {@code a} is immediately followed by {@code b}. */
    private static boolean chainResponseHolds(List<String> t, String a, String b) {
        for (int i = 0; i < t.size(); i++) {
            if (t.get(i).equals(a) && (i + 1 >= t.size() || !t.get(i + 1).equals(b))) {
                return false;
            }
        }
        return true;
    }
}
