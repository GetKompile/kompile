package ai.kompile.cli.main.chat.tools;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.jline.reader.EndOfFileException;
import org.jline.reader.UserInterruptException;

/** Uses the owning chat's reader; never opens a second terminal or executes a command. */
public final class ResourceWizard {
    private ResourceWizard() { }

    public static boolean handles(String args) {
        return Set.of("add", "setup", "wizard", "global add", "global setup", "global wizard")
                .contains(args.strip());
    }

    public static String run(Path root, String args, BiFunction<List<String>, String, String> prompt) {
        return run(args, prompt, command -> ResourcePolicy.command(root, command));
    }

    static String run(String args, BiFunction<List<String>, String, String> prompt,
                      Function<String, String> command) {
        if (prompt == null) return "Resource wizard needs an interactive chat reader. Use /resources help.";
        try {
            String scope = args.strip().startsWith("global ") ? "global "
                    : choose(prompt, "Save to", List.of("project", "user"), "project").equals("user") ? "global " : "";
            String operation = args.strip().endsWith("add") ? "add"
                    : choose(prompt, "What would you like to configure?",
                            List.of("add", "default", "unknown-shell", "remove", "inherit"), "add");
            String change;
            String description;
            if (operation.equals("add")) {
                String id = required(prompt, "Rule name (letters, digits, dash or underscore): ");
                while (!id.matches("[A-Za-z0-9_-]+"))
                    id = required(prompt, "Use only letters, digits, dash or underscore: ");
                String executable = required(prompt, "Executable name or path (e.g. java): ");
                String prefix;
                while (true) {
                    prefix = ask(prompt, List.of("Optional initial arguments, e.g. -version.",
                            "Quotes group arguments. Blank matches ALL invocations of this executable."), "Argument prefix: ");
                    var parsed = ResourcePolicy.shell(prefix);
                    if (prefix.isBlank() || parsed != null && parsed.size() == 1) break;
                }
                String cost = choose(prompt, "Resource class: high = exclusive lane and capacity checks; low = neither.",
                        List.of("high", "low"), "high");
                change = "rule " + quote(id) + " " + cost + " " + quote(executable);
                if (!prefix.isBlank()) {
                    for (String word : ResourcePolicy.shell(prefix).get(0)) change += " " + quote(word);
                }
                description = "Add " + id + " for bash AND process launch: " + executable
                        + (prefix.isBlank() ? " (ALL arguments)" : " starting with " + prefix) + " → " + cost
                        + ". Executable matches by basename, not full path. New rules take priority.";
            } else if (operation.equals("remove")) {
                String id = required(prompt, command.apply(scope + "rules") + "\nRule name to remove: ");
                change = "remove " + id;
                description = "Remove rule " + id;
            } else if (operation.equals("inherit")) {
                String field = choose(prompt, "Remove which override?", List.of("default", "unknown-shell", "rules"), "default");
                change = "inherit " + field;
                description = "Clear " + field + " override and use inherited settings";
            } else {
                String cost = choose(prompt, "Class for " + operation + ": high keeps lane/capacity checks; low skips both.",
                        List.of("high", "low"), "high");
                change = operation + " " + cost;
                description = "Set " + operation + " to " + cost;
            }
            String confirmed = choose(prompt, description + "\nScope: " + (scope.isEmpty() ? "project" : "user (project overrides still win)")
                    + "\nNothing has been saved. Permissions and running processes are unchanged. Save?",
                    List.of("yes", "no"), "no");
            return confirmed.equals("yes") ? command.apply(scope + change) : "Resource wizard cancelled; nothing saved.";
        } catch (UserInterruptException | EndOfFileException | Cancelled ignored) {
            return "Resource wizard cancelled; nothing saved.";
        }
    }

    private static String choose(BiFunction<List<String>, String, String> prompt, String title,
                                 List<String> options, String fallback) {
        while (true) {
            StringBuilder menu = new StringBuilder(title);
            for (int i = 0; i < options.size(); i++) menu.append("\n").append(i + 1).append(") ").append(options.get(i));
            String answer = ask(prompt, List.of(menu.toString().split("\n")), "Choice [" + fallback + "]: ");
            if (answer.isBlank()) return fallback;
            for (int i = 0; i < options.size(); i++)
                if (answer.equalsIgnoreCase(options.get(i)) || answer.equals(String.valueOf(i + 1))) return options.get(i);
        }
    }

    private static String required(BiFunction<List<String>, String, String> prompt, String question) {
        String answer;
        do { answer = ask(prompt, List.of("Resource configuration — type cancel or press Ctrl+C to discard."), question); }
        while (answer.isBlank());
        return answer;
    }

    private static String ask(BiFunction<List<String>, String, String> prompt, List<String> lines, String question) {
        String answer = prompt.apply(lines, question);
        if (answer == null || answer.strip().equalsIgnoreCase("cancel") || answer.contains("\u001b")) throw new Cancelled();
        return answer.strip();
    }

    private static String quote(String text) { return "'" + text.replace("'", "'\\''") + "'"; }
    private static final class Cancelled extends RuntimeException { }
}
