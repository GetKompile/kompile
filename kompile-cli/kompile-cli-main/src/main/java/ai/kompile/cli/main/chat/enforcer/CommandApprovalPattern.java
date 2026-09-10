package ai.kompile.cli.main.chat.enforcer;

import java.util.ArrayList;
import java.util.List;

/** Bounded, whole-command token globs, not shell evaluation or executable regular expressions.
 * '*' matches within one argument (never across '/'); a final '**' accepts remaining arguments.
 * Complex shell syntax requires exact approval instead of inheriting a wildcard approval.
 */
public final class CommandApprovalPattern {
    private final List<String> tokens;
    private final boolean trailingArguments;

    public CommandApprovalPattern(String expression) {
        tokens = tokenize(expression, true);
        if (tokens == null || tokens.isEmpty() || tokens.get(0).isBlank() || tokens.get(0).contains("*")
                || tokens.get(0).contains("=")) {
            throw new IllegalArgumentException("Pattern must start with a literal executable and use simple arguments");
        }
        trailingArguments = tokens.get(tokens.size() - 1).equals("**");
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).contains("**") && !(trailingArguments && i == tokens.size() - 1)) {
                throw new IllegalArgumentException("** is only supported as the final argument");
            }
        }
    }

    public boolean matches(String command) {
        List<String> actual = tokenize(command, false);
        if (actual == null) return false;
        int fixed = tokens.size() - (trailingArguments ? 1 : 0);
        if (actual.size() < fixed || (!trailingArguments && actual.size() != fixed)) return false;
        for (int i = 0; i < fixed; i++) {
            if (!matchesToken(tokens.get(i), actual.get(i))) return false;
        }
        return true;
    }

    private static boolean matchesToken(String pattern, String value) {
        // Greedy glob matching with bounded backtracking; no regex engine or user regex.
        int p = 0, v = 0, star = -1, retry = -1;
        while (v < value.length()) {
            if (p < pattern.length() && pattern.charAt(p) != '*' && pattern.charAt(p) == value.charAt(v)) {
                p++; v++;
            } else if (p < pattern.length() && pattern.charAt(p) == '*') {
                star = p++; retry = v;
            } else if (star >= 0 && retry < value.length() && value.charAt(retry) != '/') {
                p = star + 1; v = ++retry;
            } else {
                return false;
            }
        }
        while (p < pattern.length() && pattern.charAt(p) == '*') p++;
        return p == pattern.length();
    }

    private static List<String> tokenize(String text, boolean pattern) {
        if (text == null || text.isBlank() || text.length() > 4096) return null;
        List<String> result = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        char quote = 0;
        boolean started = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // Conservative even inside quotes. Shell expansion and control operators never
            // receive pattern approval; neither do globs supplied by the executing agent.
            if (";|&<>$`\\(){}[]?!~\r\n".indexOf(c) >= 0 || (!pattern && c == '*')
                    || (Character.isISOControl(c) && c != '\t')) return null;
            if (quote != 0) {
                if (c == quote) quote = 0;
                else token.append(c);
                started = true;
            } else if (c == '\'' || c == '"') {
                quote = c; started = true;
            } else if (c == ' ' || c == '\t') {
                if (started) { result.add(token.toString()); token.setLength(0); started = false; }
            } else {
                token.append(c); started = true;
            }
        }
        if (quote != 0) return null;
        if (started) result.add(token.toString());
        if (result.size() > 128) return null;
        // A wildcard scoped to a directory must not match a traversal component.
        for (String value : result) {
            for (String component : value.split("/")) {
                if (component.equals("..")) return null;
            }
        }
        return List.copyOf(result);
    }
}
