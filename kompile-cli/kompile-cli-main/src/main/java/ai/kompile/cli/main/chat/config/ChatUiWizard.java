package ai.kompile.cli.main.chat.config;

import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.Reference;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Shared interactive-first settings flow for /ui and kompile configure ui. */
public final class ChatUiWizard {
    private ChatUiWizard() { }

    public static void run(LineReader reader, ChatUiConfig config, Consumer<ChatUiConfig> onChange,
                           BiConsumer<String, List<String>> showPage, Runnable closePage,
                           Path project, String session) throws IOException {
        run(reader, config, onChange, showPage, closePage, project, session, List.of());
    }

    public static void run(LineReader reader, ChatUiConfig config, Consumer<ChatUiConfig> onChange,
                           BiConsumer<String, List<String>> showPage, Runnable closePage,
                           Path project, String session, Collection<String> availableTools) throws IOException {
        Flow flow = new Flow(reader, config, onChange, showPage, project, session, availableTools);
        try { flow.run(); }
        catch (UserInterruptException | EndOfFileException ignored) { flow.cancel(); }
        finally { closePage.run(); }
    }

    private static final List<String> KINDS = List.of("assistant", "thinking", "tool", "error", "process", "subagent", "todos", "sources", "custom", "arguments", "diff", "content", "output", "metadata");
    private static final List<String> LABELS = List.of("Assistant messages", "Thinking", "Tool calls", "Errors", "Processes", "Subagents", "Plans and todos", "Sources", "Custom panels", "Tool arguments", "Diffs", "File content", "Command output", "Metadata");
    private static final List<String> GROUPS = List.of("tools", "processes", "subagents", "todos", "sources", "custom");

    /** One numbered picker shared by every page and both hosts. */
    private static final class Flow {
        private final LineReader reader;
        private final Consumer<ChatUiConfig> change;
        private final BiConsumer<String, List<String>> page;
        private final Path project;
        private final String session;
        private final List<String> tools;
        private ChatUiConfig config;
        private ChatUiConfig saved;
        private boolean changed;
        private String notice = "Changes preview live; finishing saves automatically. Save preferences chooses a scope.";

        Flow(LineReader reader, ChatUiConfig config, Consumer<ChatUiConfig> change,
             BiConsumer<String, List<String>> page, Path project, String session, Collection<String> tools) {
            this.reader = reader; this.config = config.copy(); this.saved = config.copy();
            this.change = change; this.page = page; this.project = project; this.session = session;
            TreeSet<String> names = new TreeSet<>(config.getConfiguredTools());
            names.addAll(tools);
            this.tools = List.copyOf(names);
        }

        void run() throws IOException {
            while (true) {
                int selection = choose("Chat display", List.of(
                        "Preset — " + config.getPreset(), "Layout — " + config.getLayoutMode(),
                        "Content placement", "Sidebar appearance and sections", "Keyboard shortcuts", "Save preferences"), "Done");
                try {
                    if (selection == -1) {
                        if (changed) {
                            config.saveDefault();
                            change.accept(config.copy());
                        }
                        return;
                    }
                    switch (selection) {
                        case 0 -> preset();
                        case 1 -> layout();
                        case 2 -> content();
                        case 3 -> sidebar();
                        case 4 -> keys();
                        case 5 -> { if (save()) return; }
                        default -> throw new IllegalStateException("Unexpected selection");
                    }
                } catch (IllegalArgumentException | IOException e) { notice = e.getMessage(); }
            }
        }

        int choose(String title, List<String> options, String back) {
            while (true) {
                List<String> lines = new ArrayList<>();
                for (int i = 0; i < options.size(); i++) lines.add(String.format("%2d. %s", i + 1, options.get(i)));
                lines.add(" 0. " + back);
                lines.add(String.format("%2d. Cancel unsaved changes", options.size() + 1));
                if (!notice.isBlank()) lines.add(notice);
                page.accept(title, List.copyOf(lines));
                String input = reader.readLine("Choice (number): ");
                if (input == null) throw new EndOfFileException();
                try {
                    int number = Integer.parseInt(input.trim());
                    if (number == 0) { notice = ""; return -1; }
                    if (number == options.size() + 1) throw new UserInterruptException("");
                    if (number >= 1 && number <= options.size()) { notice = ""; return number - 1; }
                } catch (NumberFormatException ignored) { }
                notice = "Choose a number from 0 to " + (options.size() + 1) + ".";
            }
        }

        void apply(Consumer<ChatUiConfig> edit) {
            ChatUiConfig candidate = config.copy();
            edit.accept(candidate);
            change.accept(candidate.copy());
            config = candidate;
            changed = true;
        }

        boolean setting(String title, String path, List<String> values, List<String> labels, String current) {
            List<String> options = new ArrayList<>();
            for (int i = 0; i < values.size(); i++) options.add(labels.get(i) + (values.get(i).equals(current) ? " * current" : ""));
            int selection = choose(title, options, "Back");
            if (selection < 0) return false;
            apply(candidate -> candidate.set(path, values.get(selection)));
            return true;
        }

        void preset() {
            List<String> values = List.of("classic", "focused", "verbose");
            List<String> labels = List.of("Classic — results inline", "Focused — chat and thinking inline, results in sidebar", "Verbose — inline detail and sidebar");
            List<String> options = new ArrayList<>();
            for (int i = 0; i < values.size(); i++) options.add(labels.get(i) + (values.get(i).equals(config.getPreset()) ? " * current" : ""));
            int selection = choose("Choose a preset", options, "Back");
            if (selection >= 0) apply(candidate -> candidate.applyPreset(values.get(selection)));
        }

        void layout() {
            setting("Choose a layout", "layout.mode", List.of("inline", "split", "auto"),
                    List.of("Inline — one chat pane", "Split — chat and results side by side", "Automatic — split when wide enough, drawer otherwise"), config.getLayoutMode());
        }

        void content() {
            while (true) {
                List<String> options = new ArrayList<>(LABELS);
                if (!tools.isEmpty()) options.add("Individual tools");
                int kind = choose("Choose content to configure", options, "Back");
                if (kind < 0) return;
                if (kind == KINDS.size()) {
                    int tool = choose("Choose a tool", tools, "Back");
                    if (tool >= 0) placement("Tool: " + tools.get(tool), "toolOverrides." + tools.get(tool), "tool", tools.get(tool));
                } else placement(LABELS.get(kind), "content." + KINDS.get(kind), KINDS.get(kind), null);
            }
        }

        void placement(String label, String path, String kind, String tool) {
            while (true) {
                ChatUiConfig.Policy policy = config.policy(kind, tool);
                if (List.of("arguments", "diff", "content", "output", "metadata").contains(kind)) {
                    policy = config.sectionPolicy(kind, tool, config.policy("tool", tool));
                }
                int destination = choose(label, List.of("In chat — " + inlineLabel(policy.inline()), "In sidebar — " + sidebarLabel(policy.sidebar())), "Back");
                if (destination < 0) return;
                boolean selected = destination == 0
                        ? setting(label + " in chat", path + ".inline", List.of("none", "summary", "full"),
                                List.of("Hide", "Summary only", "Show content"), policy.inline())
                        : setting(label + " in sidebar", path + ".sidebar", List.of("off", "collapsed", "expanded"),
                                List.of("Hide", "Collapsed card", "Expanded card"), policy.sidebar());
                if (selected) return;
            }
        }

        String inlineLabel(String value) { return switch (value) { case "none" -> "hidden"; case "summary" -> "summary only"; default -> "content shown"; }; }
        String sidebarLabel(String value) { return switch (value) { case "off" -> "hidden"; case "collapsed" -> "collapsed card"; default -> "expanded card"; }; }

        void sidebar() {
            while (true) {
                int choice = choose("Sidebar", List.of("Width — " + config.getSidebarWidthPercent() + "%", "Minimum chat width — " + config.getMinChatColumns() + " columns",
                        "Line wrapping — " + (config.isSidebarWrap() ? "on" : "off"), "Follow new output — " + (config.getSidebarFollow().equals("manual") ? "manual" : "when at bottom"), "Visible sections and ordering",
                        "Retained completed cards — " + config.getSidebarMaxItems(), "Retained content — " + config.getSidebarMaxChars() + " characters"), "Back");
                if (choice < 0) return;
                switch (choice) {
                    case 0 -> size("Sidebar width", "layout.sidebarWidthPercent", true);
                    case 1 -> size("Minimum chat width", "layout.minChatColumns", false);
                    case 2 -> setting("Line wrapping", "sidebar.wrap", List.of("true", "false"), List.of("Wrap long lines", "Clip long lines"), String.valueOf(config.isSidebarWrap()));
                    case 3 -> setting("Follow new output", "sidebar.follow", List.of("when-at-bottom", "manual"), List.of("Follow only when already at bottom", "Manual scrolling"), config.getSidebarFollow());
                    case 4 -> sections();
                    case 5 -> retention(true);
                    case 6 -> retention(false);
                    default -> throw new IllegalStateException("Unexpected sidebar setting");
                }
            }
        }

        void size(String title, String path, boolean percent) {
            int current = percent ? config.getSidebarWidthPercent() : config.getMinChatColumns();
            int step = percent ? 5 : 10, maximum = percent ? 65 : 200, normal = percent ? 35 : 60;
            String units = percent ? "%" : " columns";
            TreeSet<Integer> sizes = new TreeSet<>();
            for (int value = 20; value <= maximum; value += step) sizes.add(value);
            sizes.add(current);
            List<String> values = new ArrayList<>(), labels = new ArrayList<>();
            for (int value : sizes) {
                values.add(String.valueOf(value));
                labels.add(value + units + (value == normal ? " — default" : ""));
            }
            setting(title, path, values, labels, String.valueOf(current));
        }

        void retention(boolean items) {
            int current = items ? config.getSidebarMaxItems() : config.getSidebarMaxChars();
            TreeSet<Integer> amounts = new TreeSet<>(items ? List.of(64, 128, 256, 512, 1024, 4096)
                    : List.of(524288, 1048576, 2097152, 4194304, 8388608, 16777216, 67108864));
            amounts.add(current);
            List<String> values = new ArrayList<>(), labels = new ArrayList<>();
            for (int amount : amounts) {
                values.add(String.valueOf(amount));
                labels.add(amount + (items ? " cards" : " characters"));
            }
            setting(items ? "Retained completed cards" : "Retained content", items ? "sidebar.maxItems" : "sidebar.maxChars",
                    values, labels, String.valueOf(current));
        }

        void sections() {
            while (true) {
                List<String> visible = config.getSidebarSections();
                List<String> options = new ArrayList<>();
                for (String group : GROUPS) options.add(group + (visible.contains(group) ? " — shown, position " + (visible.indexOf(group) + 1) : " — hidden"));
                int choice = choose("Sidebar sections", options, "Back");
                if (choice < 0) return;
                String group = GROUPS.get(choice);
                int index = visible.indexOf(group);
                List<String> actions = new ArrayList<>();
                actions.add(index >= 0 ? "Hide this section" : "Show this section");
                if (index > 0) actions.add("Move earlier");
                if (index >= 0 && index < visible.size() - 1) actions.add("Move later");
                int action = choose("Section: " + group, actions, "Back");
                if (action < 0) continue;
                List<String> next = new ArrayList<>(visible);
                if (action == 0) { if (index >= 0) next.remove(group); else next.add(group); }
                else {
                    int target = index + (actions.get(action).equals("Move earlier") ? -1 : 1);
                    next.remove(index); next.add(target, group);
                }
                apply(candidate -> candidate.set("sidebar.sections", String.join(",", next)));
            }
        }

        void keys() {
            while (true) {
                int action = choose("Keyboard shortcuts", List.of("Toggle sidebar — " + config.getBinding("toggle"), "Focus sidebar — " + config.getBinding("focus")), "Back");
                if (action < 0) return;
                String name = action == 0 ? "toggle" : "focus", other = action == 0 ? "focus" : "toggle";
                List<String> values = new ArrayList<>();
                for (char letter = 'a'; letter <= 'z'; letter++) {
                    String binding = "ctrl-x " + letter;
                    if (!List.of("ctrl-x a", "ctrl-x p", "ctrl-x t", config.getBinding(other)).contains(binding)
                            && shortcutAvailable(letter)) values.add(binding);
                }
                setting("Choose " + (action == 0 ? "toggle" : "focus") + " shortcut", "bindings." + name, values, values, config.getBinding(name));
            }
        }

        boolean shortcutAvailable(char letter) {
            var maps = reader.getKeyMaps();
            KeyMap<Binding> map = maps == null ? null : maps.get(LineReader.EMACS);
            if (map == null) return true;
            for (char key : new char[]{letter, Character.toUpperCase(letter)}) {
                Binding bound = map.getBound(KeyMap.ctrl('X') + key);
                if (bound != null && !(bound instanceof Reference reference && reference.name().startsWith("kompile-ui-"))) return false;
            }
            return true;
        }

        boolean save() throws IOException {
            List<String> scopes = new ArrayList<>(), labels = new ArrayList<>();
            if (session != null) { scopes.add("session"); labels.add("This conversation"); }
            if (project != null) { scopes.add("project"); labels.add("This project"); }
            scopes.add("global"); labels.add("All projects (global preferences)");
            int selection = choose("Save preferences", labels, "Back");
            if (selection < 0) return false;
            config.save(scopes.get(selection), project, session);
            if (changed) change.accept(config.copy());
            saved = config.copy(); changed = false;
            return true;
        }

        void cancel() { if (changed) change.accept(saved.copy()); }
    }
}
