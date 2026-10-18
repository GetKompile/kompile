package ai.kompile.cli.main.chat.tui;

import ai.kompile.cli.main.chat.config.ChatUiConfig;
import ai.kompile.cli.main.chat.render.ChatDisplayBlock;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import org.jline.utils.AttributedString;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Retained result cards and their view state. Never owns input routing or execution. */
public final class ChatSidebar {
    private final TerminalRenderer renderer;
    public ChatSidebar() { this(new TerminalRenderer(true)); }
    public ChatSidebar(TerminalRenderer renderer) { this.renderer = java.util.Objects.requireNonNull(renderer); }

    private final Map<String, ChatDisplayBlock> blocks = new LinkedHashMap<>();
    private final Map<String, Boolean> expanded = new HashMap<>();
    private final Map<String, Boolean> sectionsExpanded = new HashMap<>();
    private String selected = "";
    private String filter = "all";
    private String pinnedKey = "";
    private int offset;
    private boolean followingTail = true;
    private int omitted;
    private boolean revealSelection;
    private List<Row> cachedRows;
    private ChatUiConfig cachedConfig;
    private long cachedConfigRevision;
    private int cachedWidth;
    private void invalidate() { cachedRows = null; }

    private record Row(String text, String key, String section) { }

    public synchronized void upsert(ChatDisplayBlock block, ChatUiConfig config) {
        if (block == null || block.key().isBlank()) return;
        if (block.kind().equals("custom") && block.summary().isBlank() && block.classic().isBlank() && block.sections().isEmpty()) {
            remove(block.key());
            return;
        }
        blocks.put(block.key(), block);
        invalidate();
        trim(config);
    }

    /** Completed history is bounded; hidden transcript payloads must not displace visible results. */
    public synchronized void trim(ChatUiConfig config) {
        if (selected.isEmpty()) {
            selected = blocks.values().stream().filter(b -> sidebarEnabled(b, config))
                    .map(ChatDisplayBlock::key).findFirst().orElse("");
            invalidate();
        }
        int items = (int) blocks.values().stream().filter(b -> !b.running()).count();
        long chars = blocks.values().stream().filter(b -> !b.running()).mapToLong(ChatSidebar::size).sum();
        for (boolean hiddenOnly : new boolean[]{true, false}) {
            var iterator = blocks.entrySet().iterator();
            while ((items > config.getSidebarMaxItems() || chars > config.getSidebarMaxChars()) && iterator.hasNext()) {
                var entry = iterator.next();
                ChatDisplayBlock block = entry.getValue();
                if (block.running() || entry.getKey().equals(selected) || entry.getKey().equals(pinnedKey)
                        || (hiddenOnly && sidebarEnabled(block, config))) continue;
                chars -= size(block);
                items--;
                iterator.remove();
                forgetExpansion(entry.getKey());
                omitted++;
                invalidate();
            }
        }
    }

    private static long size(ChatDisplayBlock b) {
        return (long) b.classic().length() + b.summary().length() + b.sections().stream().mapToLong(s -> (long) s.content().length() + s.formattedContent().length()).sum();
    }
    public synchronized ChatDisplayBlock get(String key) { return blocks.get(key); }
    public synchronized List<ChatDisplayBlock> blocks() { return List.copyOf(blocks.values()); }
    public synchronized void remove(String key) {
        blocks.remove(key);
        invalidate();
        forgetExpansion(key);
        if (selected.equals(key)) selected = "";
        if (pinnedKey.equals(key)) pinnedKey = "";
    }
    private void forgetExpansion(String key) {
        expanded.remove(key);
        sectionsExpanded.keySet().removeIf(s -> s.startsWith(key + "\u0000"));
    }
    public synchronized String selectedKey() { return selected; }
    public synchronized void selectKey(String key) { if (blocks.containsKey(key)) { selected = key; revealSelection = true; invalidate(); } }
    public synchronized String getFilter() { return filter; }
    public synchronized boolean isPinned() { return !pinnedKey.isBlank(); }
    public synchronized void togglePin() { pinnedKey = pinnedKey.equals(selected) ? "" : selected; followingTail = false; offset = 0; invalidate(); }
    public synchronized void setFilter(String filter) {
        if (!List.of("all", "tools", "processes", "subagents", "todos", "sources", "custom").contains(filter)) throw new IllegalArgumentException("Unknown result filter: " + filter);
        this.filter = filter;
        invalidate();
        offset = 0;
        followingTail = false;
    }

    private ChatUiConfig.Policy policy(ChatDisplayBlock b, ChatUiConfig config) {
        return config.policy(b.error() ? "error" : b.kind(), b.toolName());
    }
    private boolean sidebarEnabled(ChatDisplayBlock block, ChatUiConfig config) {
        if (!config.getSidebarSections().contains(group(block.kind()))) return false;
        ChatUiConfig.Policy parent = policy(block, config);
        return !parent.sidebar().equals("off") || block.sections().stream()
                .anyMatch(s -> !config.sectionPolicy(s.kind(), block.toolName(), parent).sidebar().equals("off"));
    }
    private List<ChatDisplayBlock> visible(ChatUiConfig config) {
        List<ChatDisplayBlock> result = new ArrayList<>();
        for (String group : config.getSidebarSections()) {
            if (!filter.equals("all") && !filter.equals(group)) continue;
            for (ChatDisplayBlock b : blocks.values()) {
                if (group(b.kind()).equals(group) && sidebarEnabled(b, config)) result.add(b);
            }
        }
        if (!pinnedKey.isBlank()) {
            ChatDisplayBlock pinned = blocks.get(pinnedKey);
            if (pinned != null && result.remove(pinned)) result.add(0, pinned);
        }
        return result;
    }
    private static String group(String kind) {
        return switch (kind) {
            case "tool", "error" -> "tools";
            case "process", "monitor", "task" -> "processes";
            case "subagent" -> "subagents";
            case "todos" -> "todos";
            case "sources" -> "sources";
            default -> "custom";
        };
    }
    private boolean isExpanded(ChatDisplayBlock b, ChatUiConfig config) {
        return expanded.getOrDefault(b.key(), policy(b, config).sidebar().equals("expanded"));
    }
    private boolean sectionExpanded(ChatDisplayBlock b, ChatDisplayBlock.Section s, ChatUiConfig config) {
        boolean initial = config.sectionPolicy(s.kind(), b.toolName(), policy(b, config)).sidebar().equals("expanded");
        return sectionsExpanded.getOrDefault(b.key() + "\u0000" + s.id(), initial);
    }
    public synchronized void select(int delta, ChatUiConfig config) {
        List<ChatDisplayBlock> items = visible(config);
        if (items.isEmpty()) return;
        int index = 0;
        for (int i = 0; i < items.size(); i++) if (items.get(i).key().equals(selected)) index = i;
        selected = items.get(Math.max(0, Math.min(index + delta, items.size() - 1))).key();
        revealSelection = true;
        invalidate();
    }
    public synchronized void toggleSelected() {
        if (blocks.containsKey(selected)) { expanded.put(selected, !expanded.getOrDefault(selected, false)); invalidate(); }
    }
    public synchronized void toggleSelected(ChatUiConfig config) {
        ChatDisplayBlock block = blocks.get(selected);
        if (block != null) { expanded.put(selected, !isExpanded(block, config)); revealSelection = true; invalidate(); }
    }
    public synchronized void toggleSection(String sectionId) { toggleSection(sectionId, ChatUiConfig.classic()); }
    public synchronized void toggleSection(String sectionId, ChatUiConfig config) {
        ChatDisplayBlock block = blocks.get(selected);
        if (block == null) return;
        for (ChatDisplayBlock.Section s : block.sections()) if (s.id().equals(sectionId)) {
            String key = selected + "\u0000" + sectionId;
            sectionsExpanded.put(key, !sectionExpanded(block, s, config));
            invalidate();
        }
    }

    private List<Row> rows(ChatUiConfig config, int width) {
        if (cachedRows != null && cachedConfig == config && cachedWidth == width && cachedConfigRevision == config.revision()) return cachedRows;
        List<Row> rows = new ArrayList<>();
        List<ChatDisplayBlock> items = visible(config);
        addRows(rows, renderer.bold(renderer.cyan("Results · " + items.size() + (!pinnedKey.isBlank() ? " · pinned" : "") + (filter.equals("all") ? "" : " · " + filter))), "", "", width, false);
        if (omitted > 0) addRows(rows, "… " + omitted + " older cards omitted", "", "", width, false);
        if (items.isEmpty()) addRows(rows, "No matching results", "", "", width, false);
        String previousGroup = "";
        for (ChatDisplayBlock b : items) {
            String group = group(b.kind());
            if (!previousGroup.equals(group)) { addRows(rows, renderer.dim("── " + group + " ──"), "", "", width, false); previousGroup = group; }
            String summary = summary(b).replace('\n', ' ');
            String heading = renderer.cyan((b.key().equals(selected) ? "› " : "  ") + (isExpanded(b, config) ? "▾ " : "▸ "))
                    + (b.running() ? renderer.yellow("⟳ ") : b.error() ? renderer.red("✗ ") : "") + summary;
            addRows(rows, heading, b.key(), "", width, config.isSidebarWrap());
            if (!isExpanded(b, config)) continue;
            for (ChatDisplayBlock.Section section : b.sections()) {
                ChatUiConfig.Policy p = config.sectionPolicy(section.kind(), b.toolName(), policy(b, config));
                if (p.sidebar().equals("off")) continue;
                boolean open = sectionExpanded(b, section, config);
                String title = section.title().isBlank() ? section.kind() : section.title();
                addRows(rows, renderer.dim("    " + (open ? "▾ " : "▸ ") + title), b.key(), section.id(), width, config.isSidebarWrap());
                if (open) for (String line : renderer.renderDisplaySection(section).split("\\R", -1)) {
                    addRows(rows, "      " + line, b.key(), null, width, config.isSidebarWrap());
                }
            }
        }
        cachedRows = List.copyOf(rows);
        cachedConfig = config;
        cachedConfigRevision = config.revision();
        cachedWidth = width;
        return cachedRows;
    }
    private String summary(ChatDisplayBlock block) {
        String text = block.summary().isBlank() ? block.kind() : block.summary();
        return renderer.isAnsiEnabled() ? text : TerminalRenderer.printableToolOutput(text);
    }
    private static void addRows(List<Row> rows, String text, String key, String section, int width, boolean wrap) {
        AttributedString safe = TerminalRowText.fromAnsi(text);
        int columns = Math.max(1, width);
        List<AttributedString> parts = wrap ? safe.columnSplitLength(columns) : List.of(safe.columnSubSequence(0, Math.min(columns, safe.columnLength())));
        if (parts.isEmpty()) rows.add(new Row("", key, section));
        else for (AttributedString part : parts) rows.add(new Row(part.toAnsi(), key, section));
    }
    public synchronized List<String> allRows(ChatUiConfig config, int width) { return rows(config, width).stream().map(Row::text).toList(); }
    public synchronized int selectedRow(ChatUiConfig config, int width) {
        List<Row> all = rows(config, width);
        for (int i = 0; i < all.size(); i++) if (all.get(i).key().equals(selected) && "".equals(all.get(i).section())) return i;
        return 0;
    }
    public synchronized List<String> render(ChatUiConfig config, int width, int height) {
        if (height <= 0 || width <= 0) return List.of();
        List<Row> rows = rows(config, width);
        int maximum = Math.max(0, rows.size() - height);
        if (followingTail && config.getSidebarFollow().equals("when-at-bottom")) offset = maximum;
        if (revealSelection) {
            for (int i = 0; i < rows.size(); i++) {
                Row row = rows.get(i);
                if (row.key().equals(selected) && "".equals(row.section())) {
                    if (i <= offset) offset = Math.max(0, i - 1);
                    else if (i >= offset + height) offset = i - height + 1;
                    break;
                }
            }
            revealSelection = false;
            followingTail = false;
        }
        offset = Math.max(0, Math.min(offset, maximum));
        // Keep the section identity visible while long outputs scroll below it.
        List<String> visible = new ArrayList<>(height);
        visible.add(rows.get(0).text());
        if (height > 1) visible.addAll(rows.subList(Math.min(rows.size(), offset + 1),
                Math.min(rows.size(), offset + height)).stream().map(Row::text).toList());
        return List.copyOf(visible);
    }
    public synchronized void scroll(int delta, ChatUiConfig config, int width, int height) {
        int maximum = Math.max(0, rows(config, width).size() - Math.max(1, height));
        offset = Math.max(0, Math.min(offset + delta, maximum));
        followingTail = offset == maximum;
    }
    /** Whether newer cards exist below the viewport (drives the floating "latest" shortcut). */
    public synchronized boolean isScrolledAwayFromLatest(ChatUiConfig config, int width, int height) {
        if (height <= 0 || width <= 0 || followingTail) return false;
        int maximum = Math.max(0, rows(config, width).size() - Math.max(1, height));
        return offset < maximum;
    }
    /** Snap the viewport back to the newest cards and re-engage tail following. */
    public synchronized void jumpToLatest(ChatUiConfig config, int width, int height) {
        int maximum = Math.max(0, rows(config, width).size() - Math.max(1, height));
        offset = maximum;
        followingTail = true;
    }
    public synchronized boolean clickRow(int relativeRow, ChatUiConfig config, int width, int height) {
        render(config, width, height);
        List<Row> rows = rows(config, width);
        int index = offset + relativeRow;
        if (width <= 0 || relativeRow <= 0 || relativeRow >= height || index >= rows.size()) return false;
        Row row = rows.get(index);
        if (row.key().isBlank()) return false;
        selected = row.key();
        invalidate();
        if (row.section() == null) return true;
        if (row.section().isBlank()) toggleSelected(config);
        else toggleSection(row.section(), config);
        followingTail = false;
        return true;
    }
    public synchronized List<String> inspectorLines() {
        ChatDisplayBlock block = blocks.get(selected);
        if (block == null) return List.of("Select a result first.");
        List<String> lines = new ArrayList<>();
        lines.add(summary(block));
        for (ChatDisplayBlock.Section s : block.sections()) {
            lines.add(renderer.dim("── " + (s.title().isBlank() ? s.kind() : s.title()) + " ──"));
            lines.addAll(List.of(renderer.renderDisplaySection(s).split("\\R", -1)));
        }
        return List.copyOf(lines);
    }

    /** Main-pane projection; classic is deliberately unchanged unless overridden. */
    public synchronized String inline(ChatDisplayBlock block, ChatUiConfig config) {
        if (config.getPreset().equals("classic") && !config.hasContentOverrides())
            return renderer.isAnsiEnabled() ? block.classic() : TerminalRenderer.printableToolOutput(block.classic());
        ChatUiConfig.Policy parent = policy(block, config);
        boolean childInline = block.sections().stream().anyMatch(s -> !config.sectionPolicy(s.kind(), block.toolName(), parent).inline().equals("none"));
        if (parent.inline().equals("none") && !block.error() && !childInline) return "";
        StringBuilder text = new StringBuilder(block.summary().isBlank() ? "" : summary(block));
        if (text.isEmpty() && (!parent.inline().equals("none") || block.error())) {
            String plain = TerminalRenderer.printableToolOutput(block.classic());
            text.append(parent.inline().equals("full") && renderer.isAnsiEnabled() ? block.classic()
                    : parent.inline().equals("full") ? plain : plain.lines().filter(line -> !line.isBlank()).findFirst().orElse(block.kind()));
        }
        for (ChatDisplayBlock.Section s : block.sections()) {
            ChatUiConfig.Policy p = config.sectionPolicy(s.kind(), block.toolName(), parent);
            if (!p.inline().equals("full")) continue;
            // Text bursts use their classic renderer (markdown/thinking styling), once only.
            if (block.summary().isBlank() && s.id().equals("body") && parent.inline().equals("full")) continue;
            if (!text.isEmpty()) text.append('\n');
            if (!s.title().isBlank()) text.append(renderer.dim("  ↳ " + s.title() + ":")).append('\n');
            String body = ChatDisplayBlock.boundedStyledText(renderer.renderDisplaySection(s), 2_400);
            int count = 0;
            for (String line : body.split("\\R", -1)) {
                if (count++ >= 48) { text.append("… (inline preview truncated; open results)\n"); break; }
                text.append(line).append('\n');
            }
        }
        return text.toString().stripTrailing();
    }
}
