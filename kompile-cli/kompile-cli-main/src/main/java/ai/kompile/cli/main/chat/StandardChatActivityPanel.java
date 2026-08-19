/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.BackgroundTaskManager.BackgroundTask;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager.ProcessEntry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.tui.StatusBar;
import ai.kompile.cli.main.chat.tui.StatusBar.SubagentEntry;
import ai.kompile.utils.FormatUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntSupplier;

/**
 * Claude-style process and subagent activity pane for literal standard chat.
 *
 * <p>The pane uses a fixed number of rows for the current terminal size. This is
 * intentional: resizing the JLine scroll region while a prompt is being edited
 * moves the cursor on several terminals and can corrupt input. Activity changes
 * only replace the contents of the already-reserved rows.</p>
 */
final class StandardChatActivityPanel {

    enum ActivityKind {
        MAIN("main"),
        PROCESS("process"),
        SUBAGENT("subagent"),
        TOOL("tool"),
        TASK("task");

        private final String label;

        ActivityKind(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    record ActivityItem(
            String key,
            String id,
            ActivityKind kind,
            String label,
            String status,
            boolean active,
            boolean killable,
            Instant startedAt,
            String parentKey,
            int depth) {
    }

    /** A transcript-area view selected from the activity tree. */
    record ActivityView(String key, String title, String content, boolean main) {
    }

    static final String MAIN_KEY = "main";
    private static final int MAX_RETAINED_TOOL_ACTIVITIES = 64;

    private final BackgroundTaskManager taskManager;
    private final BackgroundProcessManager processManager;
    private final StatusBar statusBar;
    private final IntSupplier reservedRowSupplier;
    private final TerminalRenderer toolRenderer = new TerminalRenderer();

    private volatile boolean focused;
    private volatile String selectedKey = "";
    private volatile int selectedIndex = -1;
    private volatile String viewedKey = MAIN_KEY;
    private volatile String panelMessage = "";
    private volatile List<StatusBar.MenuItem> currentMenuItems = List.of();
    private final Object toolActivityLock = new Object();
    private final Map<String, ToolActivity> toolActivities = new LinkedHashMap<>();

    private static final class ToolActivity {
        private final String key;
        private final String id;
        private final String toolName;
        private final String rawInput;
        private final Instant startedAt;
        private volatile Instant updatedAt;
        private volatile ToolResult result;
        private volatile String status;
        private volatile boolean active;

        private ToolActivity(String key, String id, String toolName, String rawInput) {
            this.key = key;
            this.id = id;
            this.toolName = toolName;
            this.rawInput = rawInput == null ? "" : rawInput;
            this.startedAt = Instant.now();
            this.updatedAt = this.startedAt;
            this.status = "running";
            this.active = true;
        }
    }

    StandardChatActivityPanel(
            BackgroundTaskManager taskManager,
            BackgroundProcessManager processManager,
            StatusBar statusBar,
            IntSupplier reservedRowSupplier) {
        this.taskManager = taskManager;
        this.processManager = processManager;
        this.statusBar = statusBar;
        this.reservedRowSupplier = reservedRowSupplier;
    }

    static int reservedRowsForTerminal(int terminalHeight, int terminalWidth) {
        if (terminalHeight < 16) {
            return 1;
        }
        // One hint row plus two-to-four recent activity rows. Full output lives
        // in the selectable transcript view rather than permanently consuming
        // the main chat window.
        return Math.max(3, Math.min(6, terminalHeight / 8));
    }

    void recordToolStart(String callId, String toolName, String rawInput) {
        String id = callId == null || callId.isBlank()
                ? Long.toHexString(System.nanoTime()) : callId;
        String key = "tool:" + id;
        synchronized (toolActivityLock) {
            toolActivities.put(key, new ToolActivity(key, id, toolName, rawInput));
            trimToolActivities();
        }
        refresh();
    }

    void recordToolComplete(String callId, String toolName, String rawInput, ToolResult result) {
        ToolActivity activity = toolActivity(callId, toolName, rawInput);
        activity.result = result;
        activity.active = false;
        activity.updatedAt = Instant.now();
        String outcome = TerminalRenderer.summarizeToolResult(result, 72);
        activity.status = (result != null && result.isError() ? "✗" : "✓")
                + (outcome.isBlank() ? "" : " " + outcome);
        synchronized (toolActivityLock) {
            trimToolActivities();
        }
        refresh();
    }

    void recordToolDenied(String callId, String toolName, String rawInput, String reason) {
        recordToolComplete(callId, toolName, rawInput,
                ToolResult.error(reason == null || reason.isBlank() ? "denied" : reason));
    }

    private ToolActivity toolActivity(String callId, String toolName, String rawInput) {
        String id = callId == null || callId.isBlank()
                ? Long.toHexString(System.nanoTime()) : callId;
        String key = "tool:" + id;
        synchronized (toolActivityLock) {
            ToolActivity activity = toolActivities.get(key);
            if (activity == null) {
                activity = new ToolActivity(key, id, toolName, rawInput);
                toolActivities.put(key, activity);
            }
            return activity;
        }
    }

    private void trimToolActivities() {
        if (toolActivities.size() <= MAX_RETAINED_TOOL_ACTIVITIES) return;
        Iterator<ToolActivity> iterator = toolActivities.values().iterator();
        while (toolActivities.size() > MAX_RETAINED_TOOL_ACTIVITIES && iterator.hasNext()) {
            if (!iterator.next().active) iterator.remove();
        }
    }

    boolean isFocused() {
        return focused;
    }

    boolean hasItems() {
        return activityItems().size() > 1;
    }

    boolean isViewingMain() {
        return MAIN_KEY.equals(viewedKey);
    }

    String viewedSubagentId() {
        return viewedKey.startsWith("subagent:")
                ? viewedKey.substring("subagent:".length()) : "";
    }

    String getSelectedKey() {
        return selectedKey;
    }

    List<StatusBar.MenuItem> currentMenuItems() {
        return currentMenuItems;
    }

    List<ActivityItem> activityItems() {
        List<ActivityItem> rawItems = new ArrayList<>();

        for (SubagentEntry entry : statusBar.getActiveSubagents()) {
            rawItems.add(subagentItem(entry, true));
        }
        for (SubagentEntry entry : statusBar.getRecentSubagents()) {
            rawItems.add(subagentItem(entry, false));
        }
        List<ProcessEntry> processes = processManager.listAll();
        Map<Long, String> processKeysByPid = new HashMap<>();
        Set<String> processKeys = new HashSet<>();
        for (ProcessEntry entry : processes) {
            String key = "process:" + entry.getId();
            processKeys.add(key);
            if (entry.getPid() > 0) {
                processKeysByPid.put(entry.getPid(), key);
            }
        }
        for (ProcessEntry entry : processes) {
            boolean active = entry.isRunning();
            String state = entry.getState().name().toLowerCase(Locale.ROOT);
            rawItems.add(new ActivityItem(
                    "process:" + entry.getId(),
                    entry.getId(),
                    ActivityKind.PROCESS,
                    truncate(entry.getDescription(), 64),
                    state + " · " + FormatUtils.formatDuration(entry.getDuration()),
                    active,
                    active && !entry.isVirtual(),
                    entry.getStartTime(),
                    processParentKey(entry, processKeys, processKeysByPid),
                    0));
        }
        for (BackgroundTask task : taskManager.getActiveTasks()) {
            if (task.getStatus() != BackgroundTask.BackgroundTaskStatus.BACKGROUNDED) {
                continue;
            }
            rawItems.add(new ActivityItem(
                    "task:" + task.getId(),
                    task.getId(),
                    ActivityKind.TASK,
                    truncate(task.getDescription(), 64),
                    "backgrounded · " + task.getElapsedTime(),
                    true,
                    false,
                    task.getStartedAt(),
                    MAIN_KEY,
                    0));
        }
        synchronized (toolActivityLock) {
            for (ToolActivity activity : toolActivities.values()) {
                String label = TerminalRenderer.summarizeToolCall(
                        activity.toolName, activity.rawInput, 72);
                String duration = FormatUtils.formatDuration(java.time.Duration.between(
                        activity.startedAt, activity.active ? Instant.now() : activity.updatedAt));
                rawItems.add(new ActivityItem(
                        activity.key,
                        activity.id,
                        ActivityKind.TOOL,
                        label,
                        activity.status + " · " + duration,
                        activity.active,
                        false,
                        activity.updatedAt,
                        MAIN_KEY,
                        0));
            }
        }

        Comparator<ActivityItem> itemOrder = Comparator
                .comparing(ActivityItem::active).reversed()
                .thenComparing(ActivityItem::startedAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(ActivityItem::id);
        Map<String, List<ActivityItem>> children = new HashMap<>();
        for (ActivityItem item : rawItems) {
            String parent = item.parentKey();
            if (parent == null || parent.isBlank() || parent.equals(item.key())) {
                parent = MAIN_KEY;
            }
            children.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(item);
        }
        children.values().forEach(list -> list.sort(itemOrder));

        List<ActivityItem> items = new ArrayList<>();
        items.add(new ActivityItem(
                MAIN_KEY,
                MAIN_KEY,
                ActivityKind.MAIN,
                "Main chat",
                isViewingMain() ? "current view" : "Enter to restore",
                false,
                false,
                null,
                "",
                0));
        Set<String> visited = new HashSet<>();
        appendChildren(MAIN_KEY, 1, children, visited, items);
        // Malformed or stale parent metadata must not make a process disappear.
        for (ActivityItem item : rawItems) {
            if (!visited.contains(item.key())) {
                items.add(withDepth(item, MAIN_KEY, 1));
            }
        }
        return items;
    }

    private ActivityItem subagentItem(SubagentEntry entry, boolean active) {
        String description = entry.getDescription();
        String label = description == null || description.isBlank()
                ? entry.getType()
                : entry.getType() + " — " + description;
        String status = entry.getStatus();
        if (status == null || status.isBlank()) {
            status = active ? "running" : "completed";
        }
        return new ActivityItem(
                "subagent:" + entry.getId(),
                entry.getId(),
                ActivityKind.SUBAGENT,
                truncate(label, 64),
                status + " · " + entry.getElapsed(),
                active,
                false,
                entry.getStartedAt(),
                MAIN_KEY,
                0);
    }

    private String processParentKey(
            ProcessEntry entry,
            Set<String> processKeys,
            Map<Long, String> processKeysByPid) {
        Map<String, String> metadata = entry.getMetadata();
        for (String name : List.of("parentProcessId", "parent_process_id", "parentId", "parent_id")) {
            String candidate = metadata.get(name);
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            String key = candidate.contains(":") ? candidate : "process:" + candidate;
            if (processKeys.contains(key) && !key.equals("process:" + entry.getId())) {
                return key;
            }
        }
        if (entry.getPid() > 0) {
            try {
                String parent = ProcessHandle.of(entry.getPid())
                        .flatMap(ProcessHandle::parent)
                        .map(ProcessHandle::pid)
                        .map(processKeysByPid::get)
                        .orElse(null);
                if (parent != null && !parent.equals("process:" + entry.getId())) {
                    return parent;
                }
            } catch (RuntimeException ignored) {
                // Process may have exited while the tree was being built.
            }
        }
        return MAIN_KEY;
    }

    private void appendChildren(
            String parent,
            int depth,
            Map<String, List<ActivityItem>> children,
            Set<String> visited,
            List<ActivityItem> output) {
        for (ActivityItem child : children.getOrDefault(parent, List.of())) {
            if (!visited.add(child.key())) {
                continue;
            }
            output.add(withDepth(child, parent, depth));
            appendChildren(child.key(), depth + 1, children, visited, output);
        }
    }

    private static ActivityItem withDepth(ActivityItem item, String parent, int depth) {
        return new ActivityItem(
                item.key(), item.id(), item.kind(), item.label(), item.status(),
                item.active(), item.killable(), item.startedAt(), parent, depth);
    }

    void refresh() {
        List<ActivityItem> items = activityItems();
        normalizeSelection(items);

        int panelRows = Math.max(1, reservedRowSupplier.getAsInt());
        int itemRows = Math.max(0, panelRows - 1);
        List<ActivityItem> visibleItems = visibleItems(items, itemRows);

        List<StatusBar.MenuItem> menu = new ArrayList<>(itemRows);
        for (int i = 0; i < itemRows; i++) {
            if (i < visibleItems.size()) {
                ActivityItem item = visibleItems.get(i);
                String label;
                if (item.kind() == ActivityKind.MAIN) {
                    label = "← [main] Main chat";
                } else {
                    String branch = "  ".repeat(Math.max(0, item.depth() - 1)) + "└─ ";
                    label = branch + "[" + item.id() + "] "
                            + item.kind().label() + " " + item.label();
                }
                String status = item.status() + (item.killable() ? " · Del kill" : "");
                menu.add(new StatusBar.MenuItem(
                        item.key(), label, status,
                        focused && item.key().equals(selectedKey)));
            } else if (items.size() == 1 && i == 0) {
                menu.add(new StatusBar.MenuItem(
                        "activity-idle", "No active processes or subagents", "", false));
            } else {
                menu.add(new StatusBar.MenuItem("activity-spacer-" + i, "", "", false));
            }
        }

        String hint;
        if (focused) {
            hint = "↑/↓ select · ← parent · Enter open · Del kill · type for Main chat";
        } else if (items.size() == 1) {
            hint = "/processes shows session activity";
        } else {
            int hidden = Math.max(0, items.size() - visibleItems.size());
            hint = isViewingMain()
                    ? "↓ manage process tree · Enter opens output in transcript"
                    : "Viewing " + viewedKey.replaceFirst("^[^:]+:", "")
                    + (viewedKey.startsWith("subagent:")
                    ? " · type to send follow-up · PageUp/PageDown scroll"
                    : " · PageUp/PageDown scroll · select Main chat to return");
            hint += ""
                    + (hidden > 0 ? " · " + hidden + " more" : "");
        }
        if (!panelMessage.isBlank()) {
            hint = panelMessage + " · " + hint;
        }

        currentMenuItems = List.copyOf(menu);
        statusBar.setMenuItems(menu, hint);
    }

    boolean selectNext() {
        List<ActivityItem> items = activityItems();
        if (items.size() <= 1) {
            clearSelection();
            return false;
        }
        focused = true;
        int current = indexOf(items, selectedKey);
        selectedIndex = current < 0
                ? (isViewingMain() ? 1 : 0)
                : Math.min(items.size() - 1, current + 1);
        selectedKey = items.get(selectedIndex).key();
        panelMessage = "";
        refresh();
        return true;
    }

    boolean selectParent() {
        if (!focused) {
            return false;
        }
        ActivityItem item = selectedItem();
        if (item == null || item.kind() == ActivityKind.MAIN) {
            return false;
        }
        String parent = item.parentKey();
        selectedKey = parent == null || parent.isBlank() ? MAIN_KEY : parent;
        selectedIndex = indexOf(activityItems(), selectedKey);
        panelMessage = "";
        refresh();
        return true;
    }

    boolean selectPrevious() {
        if (!focused) {
            return false;
        }
        List<ActivityItem> items = activityItems();
        int current = indexOf(items, selectedKey);
        if (items.isEmpty() || current <= 0) {
            clearSelection();
            return true;
        }
        selectedIndex = current - 1;
        selectedKey = items.get(selectedIndex).key();
        panelMessage = "";
        refresh();
        return true;
    }

    void clearSelection() {
        focused = false;
        selectedKey = "";
        selectedIndex = -1;
        panelMessage = "";
        refresh();
    }

    ActivityView openSelectedView() {
        ActivityItem item = selectedItem();
        if (item == null) {
            return null;
        }
        if (item.kind() == ActivityKind.MAIN) {
            viewedKey = MAIN_KEY;
            panelMessage = "restored Main chat";
            refresh();
            return new ActivityView(MAIN_KEY, "Main chat", "", true);
        }

        viewedKey = item.key();
        panelMessage = "viewing " + item.id();
        refresh();
        return activityView(item);
    }

    String inspectSelected() {
        ActivityView view = openSelectedView();
        if (view == null) {
            return "";
        }
        if (view.main()) {
            return view.title();
        }
        return (view.title() + "\n" + view.content()).stripTrailing();
    }

    ActivityView currentView() {
        if (isViewingMain()) {
            return new ActivityView(MAIN_KEY, "Main chat", "", true);
        }
        for (ActivityItem item : activityItems()) {
            if (viewedKey.equals(item.key())) {
                return activityView(item);
            }
        }
        return new ActivityView(MAIN_KEY, "Main chat", "", true);
    }

    private ActivityView activityView(ActivityItem item) {
        StringBuilder details = new StringBuilder();
        details.append("  kind: ").append(item.kind().label())
                .append(" · status: ").append(item.status()).append("\n");
        details.append("  ").append(item.label()).append("\n");

        if (item.kind() == ActivityKind.PROCESS) {
            ProcessEntry process = processManager.get(item.id());
            if (process != null) {
                details.append("  pid: ").append(process.getPid())
                        .append(" · command: ").append(process.getCommand()).append("\n");
                if (process.getOutputFile() != null) {
                    details.append("  output: ").append(process.getOutputFile()).append("\n");
                }
                details.append("\n").append(processManager.readOutput(item.id(), 2000));
            }
        } else if (item.kind() == ActivityKind.SUBAGENT) {
            SubagentEntry subagent = findSubagent(item.id());
            if (subagent != null && !subagent.getTranscript().isBlank()) {
                details.append("\n").append(subagent.getTranscript());
            }
        } else if (item.kind() == ActivityKind.TOOL) {
            ToolActivity activity;
            synchronized (toolActivityLock) {
                activity = toolActivities.get(item.key());
            }
            if (activity != null) {
                String input = TerminalRenderer.prettifyToolInput(
                        TerminalRenderer.stripMcpPrefix(activity.toolName), activity.rawInput, 500);
                if (!input.isBlank()) {
                    details.append("  action: ").append(input).append("\n");
                }
                if (activity.result != null) {
                    if (activity.result.getTitle() != null && !activity.result.getTitle().isBlank()) {
                        details.append("  result: ").append(activity.result.getTitle()).append("\n");
                    }
                    if (!activity.result.getMetadata().isEmpty()) {
                        details.append("  outcome: ").append(
                                TerminalRenderer.summarizeToolResult(activity.result, 500)).append("\n");
                    }
                    String renderedDetail = toolRenderer.renderToolResultDetail(
                            activity.toolName, activity.rawInput, activity.result);
                    if (!renderedDetail.isBlank()) {
                        details.append("\n").append(renderedDetail);
                    }
                }
            }
        } else if (item.kind() == ActivityKind.TASK) {
            BackgroundTask task = taskManager.getTask(item.id());
            if (task != null) {
                if (task.getError() != null) {
                    details.append("  error: ").append(task.getError().getMessage()).append("\n");
                }
                if (task.getOutput() != null && !task.getOutput().isBlank()) {
                    details.append(task.getOutput());
                }
            }
        }

        return new ActivityView(
                item.key(),
                item.kind().label() + " [" + item.id() + "] · " + item.label(),
                details.toString().stripTrailing(),
                false);
    }

    private SubagentEntry findSubagent(String id) {
        for (SubagentEntry entry : statusBar.getActiveSubagents()) {
            if (entry.getId().equals(id)) return entry;
        }
        for (SubagentEntry entry : statusBar.getRecentSubagents()) {
            if (entry.getId().equals(id)) return entry;
        }
        return null;
    }

    void returnToMain() {
        viewedKey = MAIN_KEY;
        panelMessage = "";
        refresh();
    }

    String killSelected() {
        ActivityItem item = selectedItem();
        if (item == null) {
            return "No activity selected";
        }
        if (item.kind() != ActivityKind.PROCESS || !item.killable()) {
            panelMessage = "no kill handle for " + item.id();
            refresh();
            return panelMessage;
        }

        boolean killed = processManager.kill(item.id());
        panelMessage = killed
                ? "kill requested for " + item.id()
                : "process is no longer running: " + item.id();
        refresh();
        return panelMessage;
    }

    private ActivityItem selectedItem() {
        if (!focused || selectedKey.isBlank()) {
            return null;
        }
        for (ActivityItem item : activityItems()) {
            if (item.key().equals(selectedKey)) {
                return item;
            }
        }
        return null;
    }

    private void normalizeSelection(List<ActivityItem> items) {
        if (!focused) {
            return;
        }
        int found = indexOf(items, selectedKey);
        if (found >= 0) {
            selectedIndex = found;
            return;
        }
        if (items.isEmpty()) {
            focused = false;
            selectedKey = "";
            selectedIndex = -1;
            return;
        }
        selectedIndex = Math.max(0, Math.min(selectedIndex, items.size() - 1));
        selectedKey = items.get(selectedIndex).key();
    }

    private List<ActivityItem> visibleItems(List<ActivityItem> items, int itemRows) {
        if (itemRows <= 0 || items.size() <= 1) {
            return List.of();
        }
        if (items.size() <= itemRows) {
            return List.copyOf(items);
        }
        if (itemRows == 1) {
            int current = indexOf(items, selectedKey);
            if (focused && current >= 0) {
                return List.of(items.get(current));
            }
            return List.of(items.get(isViewingMain() && items.size() > 1 ? 1 : 0));
        }

        // Keep the Main chat return control pinned while the remainder scrolls
        // through the process/subagent hierarchy.
        List<ActivityItem> visible = new ArrayList<>(itemRows);
        visible.add(items.get(0));
        int childRows = itemRows - 1;
        int selected = indexOf(items, selectedKey);
        int childIndex = Math.max(0, selected - 1);
        int childCount = items.size() - 1;
        int start = focused
                ? Math.max(0, Math.min(childIndex - childRows + 1, childCount - childRows))
                : 0;
        for (int i = 0; i < childRows && start + i < childCount; i++) {
            visible.add(items.get(1 + start + i));
        }
        return visible;
    }

    private static int indexOf(List<ActivityItem> items, String key) {
        if (key == null || key.isBlank()) {
            return -1;
        }
        for (int i = 0; i < items.size(); i++) {
            if (key.equals(items.get(i).key())) {
                return i;
            }
        }
        return -1;
    }

    private static String truncate(String value, int maxLength) {
        String text = value == null || value.isBlank() ? "(unnamed)" : value.strip();
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, Math.max(0, maxLength - 1)) + "…";
    }
}
