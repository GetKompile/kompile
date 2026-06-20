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

import java.util.Locale;
import java.util.Optional;

/**
 * A discovered Declare constraint with its quality measures.
 *
 * @param template   the constraint template
 * @param activityA  the first (or only) activity
 * @param activityB  the second activity, or {@code null} for unary templates
 * @param support    fraction of all traces in which the constraint is activated <em>and</em> satisfied
 * @param confidence fraction of activations that are satisfied (1.0 = always holds when it applies)
 */
public record DeclareConstraint(
        DeclareTemplate template,
        String activityA,
        String activityB,
        double support,
        double confidence) {

    public boolean isUnary() {
        return activityB == null;
    }

    /**
     * Encodes the constraint as a weighted PSL rule over an {@code Occurs} predicate (the same engine the
     * causal coupling uses), where the template has an implicational form. Returns empty for templates
     * (e.g. {@code NOT_CO_EXISTENCE}, the unary ones) that this minimal encoding does not cover.
     */
    public Optional<String> toPslRule() {
        if (activityA == null) {
            return Optional.empty();
        }
        String a = sanitize(activityA);
        String weight = String.format(Locale.ROOT, "%.2f", Math.max(0.1, Math.min(0.99, confidence)));
        return switch (template) {
            case RESPONSE, CHAIN_RESPONSE ->
                    Optional.of(weight + ": Occurs(\"" + a + "\") -> Occurs(\"" + sanitize(activityB) + "\") ^2");
            case PRECEDENCE ->
                    Optional.of(weight + ": Occurs(\"" + sanitize(activityB) + "\") -> Occurs(\"" + a + "\") ^2");
            default -> Optional.empty();
        };
    }

    @Override
    public String toString() {
        return isUnary()
                ? String.format(Locale.ROOT, "%s(%s) [conf=%.2f]", template, activityA, confidence)
                : String.format(Locale.ROOT, "%s(%s, %s) [conf=%.2f, supp=%.2f]",
                        template, activityA, activityB, confidence, support);
    }

    private static String sanitize(String s) {
        return s == null ? "" : s.replace('"', ' ').trim();
    }
}
