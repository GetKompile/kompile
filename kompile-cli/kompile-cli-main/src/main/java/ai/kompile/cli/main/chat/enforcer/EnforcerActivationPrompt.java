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

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;

import java.io.Console;

/**
 * Per-session activation prompt for a {@code .kompile/enforcer-config.json} found on disk.
 *
 * <p>Enforcement is strictly opt-in: a project config alone never activates it. Every
 * session that would use the config must ask the user first. When the session is
 * non-interactive (no console), the answer is always {@code FALSE} and a one-line notice
 * explains how to opt in explicitly ({@code --rules}/{@code --rule-file} or
 * {@code kompile enforcer}).</p>
 */
public final class EnforcerActivationPrompt {

    private static final String RESET = "\033[0m";
    private static final String DIM = "\033[2m";
    private static final String CYAN = "\033[36m";

    private EnforcerActivationPrompt() {}

    /**
     * One-line description of what the found config would enforce, shown above the prompt.
     */
    static String describeConfig(EnforcerConfig config) {
        if (config == null) {
            return "enforcer config";
        }
        int ruleSources = 0;
        StringBuilder sb = new StringBuilder(config.isKeywordMode() ? "keyword mode" : "LLM-judge mode");
        if (config.getInlineRules() != null && !config.getInlineRules().isBlank()) {
            ruleSources += (int) config.getInlineRules().lines().filter(l -> !l.isBlank()).count();
        }
        if (config.getRuleFile() != null && !config.getRuleFile().isBlank()) {
            sb.append(", rule file ").append(config.getRuleFile());
        }
        ruleSources += config.getBannedTools() != null ? config.getBannedTools().size() : 0;
        ruleSources += config.getBannedCommands() != null ? config.getBannedCommands().size() : 0;
        ruleSources += config.getBannedKeywords() != null ? config.getBannedKeywords().size() : 0;
        ruleSources += config.getDiffPatternRules() != null ? config.getDiffPatternRules().size() : 0;
        if (ruleSources > 0) {
            sb.append(", ").append(ruleSources).append(" rule").append(ruleSources == 1 ? "" : "s");
        }
        return sb.toString();
    }

    /**
     * Interpret a raw prompt answer. Only an explicit yes activates; empty input,
     * EOF ({@code null}) and anything else decline — enforcement defaults to OFF.
     */
    static boolean interpretAnswer(String answer) {
        if (answer == null) {
            return false;
        }
        String normalized = answer.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("y") || normalized.equals("yes");
    }

    /**
     * Prompt via {@code System.console()}. Used by launch paths that run before any
     * JLine terminal exists (e.g. {@code kompile chat} routing).
     *
     * @return {@code TRUE}/{@code FALSE} for an interactive answer; {@code FALSE} with a
     *         notice when no console is attached (non-interactive runs never enforce)
     */
    public static Boolean confirmViaConsole(EnforcerConfig config) {
        Console console = System.console();
        if (console == null) {
            printNonInteractiveNotice();
            return Boolean.FALSE;
        }
        printHeader(config);
        String answer = console.readLine(
                "  Enable rule enforcement for this session? [y/N]: ");
        return interpretAnswer(answer);
    }

    /**
     * Prompt via an existing JLine {@link LineReader} (REPL contexts that already own
     * the terminal). Ctrl+C / EOF decline.
     */
    public static Boolean confirmViaReader(LineReader reader, EnforcerConfig config) {
        if (reader == null) {
            return confirmViaConsole(config);
        }
        printHeader(config);
        try {
            String answer = reader.readLine("  Enable rule enforcement for this session? [y/N]: ");
            return interpretAnswer(answer);
        } catch (UserInterruptException | EndOfFileException e) {
            return Boolean.FALSE;
        }
    }

    private static void printHeader(EnforcerConfig config) {
        System.out.println(CYAN + "  Enforcer config found" + RESET + DIM
                + " (.kompile/enforcer-config.json — " + describeConfig(config) + ")" + RESET);
    }

    private static void printNonInteractiveNotice() {
        System.out.println(DIM + "  Enforcer config found but not activated (non-interactive session). "
                + "Pass --rules/--rule-file or run 'kompile enforcer' to enforce." + RESET);
    }
}
