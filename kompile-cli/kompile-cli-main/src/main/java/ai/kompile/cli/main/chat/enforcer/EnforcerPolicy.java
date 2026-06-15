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

package ai.kompile.cli.main.chat.enforcer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * User-authored rules and retry limits for an enforcer-controlled turn.
 */
public class EnforcerPolicy {

    public static final int DEFAULT_MAX_CORRECTIONS = 2;
    public static final int HARD_MAX_CORRECTIONS = 8;

    private final String rules;
    private final int maxCorrections;
    private final boolean returnAttempts;

    public EnforcerPolicy(String rules, int maxCorrections, boolean returnAttempts) {
        this.rules = normalizeRules(rules);
        this.maxCorrections = clampCorrections(maxCorrections);
        this.returnAttempts = returnAttempts;
    }

    public String getRules() {
        return rules;
    }

    public int getMaxCorrections() {
        return maxCorrections;
    }

    public boolean isReturnAttempts() {
        return returnAttempts;
    }

    public boolean hasRules() {
        return rules != null && !rules.isBlank();
    }

    public static EnforcerPolicy from(String rules, int maxCorrections) {
        return new EnforcerPolicy(rules, maxCorrections, false);
    }

    public static String resolveRules(String inlineRules, String ruleFile, Path workingDir) throws IOException {
        if (inlineRules != null && !inlineRules.isBlank()) {
            return normalizeRules(inlineRules);
        }

        if (ruleFile == null || ruleFile.isBlank()) {
            return "";
        }

        Path path = Path.of(ruleFile);
        if (!path.isAbsolute() && workingDir != null) {
            path = workingDir.resolve(path);
        }
        return normalizeRules(Files.readString(path.normalize(), StandardCharsets.UTF_8));
    }

    public static String normalizeRules(String rules) {
        if (rules == null) {
            return "";
        }
        return rules.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private static int clampCorrections(int maxCorrections) {
        if (maxCorrections < 0) {
            return 0;
        }
        return Math.min(maxCorrections, HARD_MAX_CORRECTIONS);
    }
}
