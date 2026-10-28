package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.agent.SubagentAdmission;
import ai.kompile.cli.main.chat.config.SubagentConfig;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.FailoverConfigWizard;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;

/** Model-free settings commands and interactive-first wizard for /subagent-config. */
public final class SubagentConfigCommands {
    public static final String HELP = "Usage: /subagent-config [status|help|max <N|unlimited>|ban <glob>|unban <glob>|clear-bans|failover]\n"
            + "Bare /subagent-config opens the settings wizard; /subagent-config failover opens its fallback editor.\n"
            + "max: 0 disables new subagents; unlimited restores the default. The count is shared by all chats in this process.\n"
            + "Bans: case-insensitive full glob matches (* and ?) against model id or provider/model (agent/model for vendor CLIs), e.g. *haiku* or anthropic/*.\n"
            + "Settings apply to new runs, resumes and follow-ups; running children are not killed. Routes with unknown models are refused when bans exist.";
    private SubagentConfigCommands() { }

    public static String command(Path path, String arguments) throws IOException {
        String input = arguments == null ? "" : arguments.strip();
        String[] parts = input.split("\\s+", 2);
        String action = parts[0].toLowerCase(Locale.ROOT);
        String value = parts.length == 2 ? parts[1].strip() : "";
        if (action.equals("help")) return HELP;
        SubagentConfig config = SubagentConfig.load(path);
        if (input.isEmpty() || action.equals("status")) return status(path, config);
        var bans = new ArrayList<>(config.bannedModels());
        switch (action) {
            case "max" -> config = new SubagentConfig(parseMax(value), bans, config.failoverEnabled(), config.failoverRungs());
            case "ban" -> {
                if (value.isBlank()) throw new IllegalArgumentException("Usage: /subagent-config ban <glob>");
                bans.add(value);
                config = new SubagentConfig(config.maxConcurrent(), bans, config.failoverEnabled(), config.failoverRungs());
            }
            case "unban" -> {
                if (!bans.remove(value)) throw new IllegalArgumentException("No model ban pattern found: " + value);
                config = new SubagentConfig(config.maxConcurrent(), bans, config.failoverEnabled(), config.failoverRungs());
            }
            case "clear-bans" -> {
                if (!value.isEmpty()) throw new IllegalArgumentException(HELP);
                config = new SubagentConfig(config.maxConcurrent(), List.of(), config.failoverEnabled(), config.failoverRungs());
            }
            default -> throw new IllegalArgumentException(HELP);
        }
        config.save(path);
        return "Subagent settings saved.\n" + status(path, config);
    }

    private static int parseMax(String value) {
        if (value.equalsIgnoreCase("unlimited")) return -1;
        try {
            int max = Integer.parseInt(value);
            if (max >= 0) return max;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException("Use /subagent-config max <non-negative integer|unlimited>");
    }

    private static String status(Path path, SubagentConfig config) {
        return "Subagents running in this process: " + SubagentAdmission.activeCount()
                + "\nMaximum concurrent: " + (config.maxConcurrent() == -1 ? "unlimited" : config.maxConcurrent())
                + "\nBanned model patterns: " + (config.bannedModels().isEmpty() ? "none" : String.join(", ", config.bannedModels()))
                + "\n" + String.join("\n", FailoverConfigWizard.describe(config.failoverEnabled(), config.failoverRungs()))
                + "\nGlobal config: " + path;
    }

    /** Stage changes until Save; interruption/EOF leaves the saved policy untouched. */
    public static void wizard(LineReader reader, Path path,
                              BiConsumer<String, List<String>> page, Runnable closePage) throws IOException {
        wizard(reader, path, page, closePage, null);
    }

    public static void wizard(LineReader reader, Path path, BiConsumer<String, List<String>> page,
                              Runnable closePage, ChatConfig current) throws IOException {
        SubagentConfig config = SubagentConfig.load(path);
        String notice = "Changes apply to new runs after Save. * and ? are model glob wildcards.";
        try {
            while (true) {
                var lines = new ArrayList<>(status(path, config).lines().toList());
                lines.addAll(List.of("", "1. Set maximum concurrent subagents", "2. Ban a model pattern", "3. Remove a model ban pattern",
                        "4. Clear model bans", "5. Save and close", "6. Configure model failover", "0. Cancel", notice));
                page.accept("Subagent settings", List.copyOf(lines));
                String choice = read(reader, "Choice: ");
                try {
                    var bans = new ArrayList<>(config.bannedModels());
                    switch (choice) {
                        case "0" -> { return; }
                        case "1" -> config = new SubagentConfig(parseMax(read(reader, "Maximum (0 disables, unlimited for no limit): ")), bans,
                                config.failoverEnabled(), config.failoverRungs());
                        case "2" -> {
                            bans.add(read(reader, "Model glob (e.g. *haiku*): "));
                            config = new SubagentConfig(config.maxConcurrent(), bans, config.failoverEnabled(), config.failoverRungs());
                        }
                        case "3" -> {
                            String pattern = read(reader, "Exact ban pattern to remove: ");
                            if (!bans.remove(pattern)) throw new IllegalArgumentException("No model ban pattern found: " + pattern);
                            config = new SubagentConfig(config.maxConcurrent(), bans, config.failoverEnabled(), config.failoverRungs());
                        }
                        case "4" -> config = new SubagentConfig(config.maxConcurrent(), List.of(), config.failoverEnabled(), config.failoverRungs());
                        case "5" -> { config.save(path); return; }
                        case "6" -> {
                            var draft = FailoverConfigWizard.edit(reader, config.failoverEnabled(), config.failoverRungs(), current, page);
                            if (draft != null) config = new SubagentConfig(config.maxConcurrent(), config.bannedModels(), draft.enabled(), draft.rungs());
                        }
                        default -> throw new IllegalArgumentException("Choose 0 through 6.");
                    }
                    notice = "Unsaved changes; choose 5 to save or 0 to cancel.";
                } catch (IllegalArgumentException e) { notice = e.getMessage(); }
            }
        } catch (UserInterruptException | EndOfFileException ignored) {
            // Cancel without saving.
        } finally { closePage.run(); }
    }

    public static void failoverWizard(LineReader reader, Path path, ChatConfig current,
                                      BiConsumer<String, List<String>> page, Runnable closePage) throws IOException {
        try {
            SubagentConfig config = SubagentConfig.load(path);
            var draft = FailoverConfigWizard.edit(reader, config.failoverEnabled(), config.failoverRungs(), current, page);
            if (draft != null) new SubagentConfig(config.maxConcurrent(), config.bannedModels(), draft.enabled(), draft.rungs()).save(path);
        } finally { closePage.run(); }
    }

    private static String read(LineReader reader, String prompt) {
        String value = reader.readLine(prompt);
        if (value == null) throw new EndOfFileException();
        return value.strip();
    }
}
