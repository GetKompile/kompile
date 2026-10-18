package ai.kompile.cli.main.chat.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Deterministic, explicitly partial log views. Callers must verify an exact archive first. */
final class ToolLogShaper {
    private static final Set<String> LOG_TOOLS = Set.of("bash", "process", "run_command", "build", "test");
    private static final Pattern CRITICAL = Pattern.compile(
            "(?i)\\b(error|warn(?:ing)?|exception|failed|failure|fatal|denied|cancelled|canceled|"
                    + "vulnerabilit(?:y|ies)|exploit|authentication bypass|privilege escalation)\\b"
                    + "|\\bCVE-[0-9]{4}-|-----BEGIN [^\\r\\n]*PRIVATE KEY-----");

    private ToolLogShaper() { }

    static boolean critical(String text) { return CRITICAL.matcher(text).find(); }

    static String shape(String tool, String text) {
        if (!LOG_TOOLS.contains(tool) || critical(text)) return text;
        List<String> lines = new ArrayList<>();
        for (String line : text.replace("\r\n", "\n").split("\n", -1)) {
            int rewrite = line.lastIndexOf('\r');
            lines.add(rewrite < 0 ? line : line.substring(rewrite + 1));
        }
        List<String> repeated = new ArrayList<>();
        for (int i = 0; i < lines.size();) {
            String line = lines.get(i);
            int end = i + 1;
            while (end < lines.size() && line.equals(lines.get(end))) end++;
            String marker = "[same line repeated " + (end - i - 1) + " more times]";
            if (line.isBlank() && end - i > 1) repeated.add(line);
            else if (end - i >= 3 && (long) line.length() * (end - i - 1) > marker.length() + 1L) {
                repeated.add(line);
                repeated.add(marker);
            } else repeated.addAll(lines.subList(i, end));
            i = end;
        }
        List<String> shaped = new ArrayList<>();
        for (int i = 0; i < repeated.size();) {
            if (indent(repeated.get(i)) < 8) {
                shaped.add(repeated.get(i++));
                continue;
            }
            int end = i + 1;
            while (end < repeated.size() && indent(repeated.get(end)) >= 8) end++;
            if (end - i >= 8) {
                shaped.add(repeated.get(i));
                shaped.add("[" + (end - i - 2) + " deeply indented log lines omitted; read exact archive]");
                shaped.add(repeated.get(end - 1));
            } else shaped.addAll(repeated.subList(i, end));
            i = end;
        }
        String result = String.join("\n", shaped);
        return result.length() < text.length() ? result : text;
    }

    private static int indent(String text) {
        int count = 0;
        while (count < text.length() && text.charAt(count) == ' ') count++;
        return count;
    }
}
