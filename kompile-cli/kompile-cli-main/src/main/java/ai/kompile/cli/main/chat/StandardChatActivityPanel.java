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
import ai.kompile.cli.main.chat.activity.ProjectActivityView;
import ai.kompile.cli.main.chat.agent.SubagentRunner;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager.ProcessEntry;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager.ProcessKind;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager.ProcessMonitor;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.tui.StatusBar;
import ai.kompile.cli.main.chat.tui.StatusBar.SubagentEntry;
import ai.kompile.utils.FormatUtils;
import ai.kompile.utils.StringUtils;

import java.time.Instant;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.function.BiConsumer;
import ai.kompile.cli.main.chat.tui.KompileTui;
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
 * Claude-style auxiliary-chat, process, and subagent activity pane for literal standard chat.
 *
 * <p>The pane uses a fixed number of rows for the current terminal size. This is
 * intentional: resizing the JLine scroll region while a prompt is being edited
 * moves the cursor on several terminals and can corrupt input. Activity changes
 * only replace the contents of the already-reserved rows.</p>
 */
final class StandardChatActivityPanel {

    enum ActivityKind {
        MAIN("main"),
        PROJECT("project"),
        REPL("chat"),
        PROCESS("process"),
        SUBAGENT("subagent"),
        TOOL("tool"),
        MONITOR("monitor"),
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
    static final String PROJECT_ACTIVITY_KEY = "project:agents";
    private static final int MAX_RETAINED_TOOL_ACTIVITIES = 64;
    /** One-line steps shown in the collapsed inline block (the rest collapse into a count). */
    private static final int MAX_INLINE_SUBAGENT_STEPS = 3;
    private static final int MAX_INLINE_STEP_CHARS = 160;

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
    private volatile Instant panelMessageExpiresAt = Instant.MIN;
    private final Supplier<Instant> now;
    private final BiConsumer<Long, Runnable> expiryScheduler;
    private final ChatUiSession owner = ChatUiSession.current();
    private final ChatSessionContext context = ChatSessionContext.current();
    private Instant nextExpiry;
    private volatile List<StatusBar.MenuItem> currentMenuItems = List.of();
    private volatile List<ChatCompleter.CompletionItem> completionItems = List.of();
    private final Object toolActivityLock = new Object();
    private final Map<String, ToolActivity> toolActivities = new LinkedHashMap<>();
    private final Object auxiliaryReplLock = new Object();
    private final Map<String, AuxiliaryChatRepl> auxiliaryRepls = new LinkedHashMap<>();
    private final Object backgroundTaskLinkLock = new Object();
    private String linkedBackgroundTaskId = "";
    private String linkedSubagentId = "";
    private volatile SubagentRunner subagentRunner;
    private volatile ProjectActivityView projectActivityView;

    private static final class ToolActivity {
        private final String key;
        private final String id;
        private final String toolName;
        private volatile String rawInput;
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
        this(taskManager, processManager, statusBar, reservedRowSupplier, Instant::now,
                (millis, callback) -> CompletableFuture.delayedExecutor(millis, TimeUnit.MILLISECONDS)
                        .execute(callback));
    }

    StandardChatActivityPanel(BackgroundTaskManager taskManager, BackgroundProcessManager processManager,
            StatusBar statusBar, IntSupplier reservedRowSupplier, Supplier<Instant> now,
            BiConsumer<Long, Runnable> expiryScheduler) {
        this.taskManager = taskManager;
        this.processManager = processManager;
        this.statusBar = statusBar;
        this.reservedRowSupplier = reservedRowSupplier;
        this.now = now;
        this.expiryScheduler = expiryScheduler;
    }

    private synchronized void scheduleExpiry(Instant deadline) {
        if (owner.isClosed() || (nextExpiry != null && !deadline.isBefore(nextExpiry))) return;
        nextExpiry = deadline;
        expiryScheduler.accept(Math.max(1, Duration.between(now.get(), deadline).toMillis()), context.wrap(() -> {
            synchronized (StandardChatActivityPanel.this) {
                if (!deadline.equals(nextExpiry)) return;
                nextExpiry = null;
            }
            if (!owner.isClosed()) refresh();
        }));
    }

    private boolean completionVisible(String key, Instant completedAt) {
        if (completedAt == null) return true;
        Instant deadline = completedAt.plusSeconds(KompileTui.EPHEMERAL_MESSAGE_SECONDS);
        if (now.get().isBefore(deadline)) {
            scheduleExpiry(deadline);
            return true;
        }
        // Expire the notification, not a transcript the user deliberately opened.
        return key.equals(viewedKey);
    }

    private void setPanelMessage(String message) {
        panelMessageExpiresAt = now.get().plusSeconds(KompileTui.EPHEMERAL_MESSAGE_SECONDS);
        panelMessage = message == null ? "" : message;
        if (!panelMessage.isBlank()) scheduleExpiry(panelMessageExpiresAt);
    }

    void setSubagentRunner(SubagentRunner subagentRunner) {
        this.subagentRunner = subagentRunner;
        refresh();
    }

    void setProjectActivityView(ProjectActivityView projectActivityView) {
        this.projectActivityView = projectActivityView;
        refresh();
    }

    void registerAuxiliaryRepl(AuxiliaryChatRepl repl) {
        if (repl == null) return;
        synchronized (auxiliaryReplLock) {
            auxiliaryRepls.put("repl:" + repl.id(), repl);
        }
        refresh();
    }

    /**
     * Build the replaceable main-transcript block for one subagent. The selected
     * activity view remains the full transcript; the main window only shows a
     * collapsed header plus the last few one-line steps — the equivalent of a
     * compact task block. Raw tool output and streamed model text stay in the
     * inspectable transcript (↓ then Enter) instead of flooding the main window.
     */
    String inlineSubagentTranscript(String id) {
        SubagentEntry entry = findSubagent(id);
        if (entry == null) return "";

        String description = entry.getDescription();
        String status = entry.getStatus();
        if (status == null || status.isBlank()) {
            status = entry.isActive() ? "running" : "completed";
        }

        boolean backgrounded = isCurrentTurnBackgrounded();
        List<String> steps = entry.getRecentSummaries();
        StringBuilder block = new StringBuilder()
                .append("  ◉ Subagent [").append(entry.getId()).append("] ")
                .append(entry.getType());
        if (description != null && !description.isBlank()) {
            block.append(" — ").append(description);
        }
        int total = entry.getTotalSteps();
        block.append("\n  ").append(entry.getElapsed());
        if (total > 0) {
            block.append(" · ").append(total).append(total == 1 ? " step" : " steps");
        }
        if (taskManager.isCurrentTaskBackgroundable()) {
            block.append(" · Ctrl+B background this invocation");
        }
        block.append(" · ↓ then Delete to stop or Enter to inspect");
        if (backgrounded) {
            block.append("\n  ◐ Backgrounded · output continues in the subagent row below")
                    .append(" · ↓ selects it; Enter opens it; then type a follow-up · Delete to stop");
            return block.toString();
        }

        int shown = Math.min(steps.size(), MAX_INLINE_SUBAGENT_STEPS);
        int hidden = Math.max(0, total - shown);
        if (hidden > 0) {
            block.append("\n  … +").append(hidden).append(" earlier step").append(hidden == 1 ? "" : "s");
        }

        boolean active = entry.isActive();
        if (steps.isEmpty()) {
            // No discrete steps yet (starting/thinking/connecting): show the live
            // status as the running row so the block never reads as idle.
            block.append("\n  ").append(active ? "⟳ " : "⎿ ").append(status.strip());
            return block.toString();
        }
        int first = steps.size() - shown;
        for (int i = first; i < steps.size(); i++) {
            String step = steps.get(i);
            // The runners append " …" to the in-flight call summary and " ✓ "/" ✗ "
            // to finished ones; a trailing ellipsis alone is not evidence of running.
            boolean runningStep = active && i == steps.size() - 1
                    && step.endsWith(" …") && !step.contains(" ✓ ") && !step.contains(" ✗ ");
            block.append("\n  ").append(runningStep ? "⟳ " : "⎿ ")
                    .append(StringUtils.truncateEllipsis(step, MAX_INLINE_STEP_CHARS));
        }
        return block.toString();
    }

    boolean isCurrentTurnBackgrounded() {
        BackgroundTask task = taskManager.getCurrentTask();
        return task != null
                && task.getStatus() == BackgroundTask.BackgroundTaskStatus.BACKGROUNDED;
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

    void updateToolInput(String callId, String toolName, String rawInput) {
        ToolActivity activity = toolActivity(callId, toolName, rawInput);
        activity.rawInput = rawInput == null ? "" : rawInput;
        activity.updatedAt = now.get();
        refresh();
    }

    void recordToolComplete(String callId, String toolName, String rawInput, ToolResult result) {
        ToolActivity activity = toolActivity(callId, toolName, rawInput);
        activity.result = result;
        activity.active = false;
        activity.updatedAt = now.get();
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

    boolean isViewingProjectActivity() {
        return PROJECT_ACTIVITY_KEY.equals(viewedKey);
    }

    String viewedSubagentId() {
        return subagentTargetForKey(viewedKey);
    }

    private String subagentTargetForKey(String key) {
        if (key == null || key.isBlank()) return "";
        if (key.startsWith("subagent:")) {
            return key.substring("subagent:".length());
        }
        if (!key.startsWith("task:")) return "";
        String taskId = key.substring("task:".length());
        synchronized (backgroundTaskLinkLock) {
            return taskId.equals(linkedBackgroundTaskId) ? linkedSubagentId : "";
        }
    }

    /**
     * Route input to the open subagent only while its retained session accepts
     * follow-ups. A false result deliberately restores Main so the caller can
     * dispatch the same input to the parent rather than dropping it.
     */
    boolean trySendMessageToViewedSubagent(String message) {
        String id = viewedSubagentId();
        if (id.isBlank() || message == null || message.isBlank()) return false;
        SubagentRunner runner = subagentRunner;
        if (runner != null && runner.sendMessage(id, message)) return true;

        statusBar.appendSubagentActivity(
                id,
                "follow-up unavailable",
                "\n  Follow-up was not sent: this subagent session is no longer interactive.");
        returnToMain("subagent " + id + " unavailable; continuing in Main chat");
        return false;
    }

    String getSelectedKey() {
        return selectedKey;
    }

    List<StatusBar.MenuItem> currentMenuItems() {
        return currentMenuItems;
    }

    /**
     * Temporarily replace activity rows with slash-command completions. The row
     * budget is already reserved, so opening the menu never moves the prompt or
     * scrolls the transcript. Passing an empty list restores normal activity.
     */
    boolean updateCompletions(List<ChatCompleter.CompletionItem> items) {
        List<ChatCompleter.CompletionItem> next = items == null
                ? List.of()
                : items.stream().filter(java.util.Objects::nonNull).toList();
        if (next.equals(completionItems)) return true;
        completionItems = List.copyOf(next);
        refresh();
        return true;
    }

    boolean hasCompletions() {
        return !completionItems.isEmpty();
    }

    List<ActivityItem> activityItems() {
        List<ActivityItem> rawItems = new ArrayList<>();

        ProjectActivityView project = projectActivityView;
        if (project != null) {
            rawItems.add(new ActivityItem(
                    PROJECT_ACTIVITY_KEY,
                    "agents",
                    ActivityKind.PROJECT,
                    "Project activity",
                    project.compactStatus(),
                    project.hasLiveWork(),
                    false,
                    null,
                    MAIN_KEY,
                    0));
        }

        synchronized (auxiliaryReplLock) {
            for (Map.Entry<String, AuxiliaryChatRepl> entry : auxiliaryRepls.entrySet()) {
                AuxiliaryChatRepl repl = entry.getValue();
                String summary = repl.status() + " · " + repl.describe();
                String tokens = repl.tokenSummary();
                if (!tokens.isEmpty()) {
                    summary += " · " + tokens;
                }
                rawItems.add(new ActivityItem(
                        entry.getKey(),
                        repl.id(),
                        ActivityKind.REPL,
                        repl.displayName(),
                        summary,
                        repl.isRunning(),
                        false,
                        repl.startedAt(),
                        MAIN_KEY,
                        0));
            }
        }

        List<SubagentEntry> activeSubagents = statusBar.getActiveSubagents();
        List<SubagentEntry> recentSubagents = statusBar.getRecentSubagents().stream()
                .filter(entry -> completionVisible("subagent:" + entry.getId(), entry.getCompletedAt()))
                .toList();
        for (SubagentEntry entry : activeSubagents) {
            rawItems.add(subagentItem(entry, true));
        }
        for (SubagentEntry entry : recentSubagents) {
            rawItems.add(subagentItem(entry, false));
        }
        // The fixed bottom pane is live work, not process history. Keeping terminal
        // entries here crowds out actionable rows and produces misleading Delete
        // failures because completed processes no longer have a kill owner.
        List<ProcessEntry> processes = processManager.listRunning();
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
            boolean shared = entry.getKind() == ProcessKind.SHARED;
            String sharedLabel = shared ? sharedOwnerLabel(entry) + " · " : "";
            rawItems.add(new ActivityItem(
                    "process:" + entry.getId(),
                    entry.getId(),
                    ActivityKind.PROCESS,
                    truncate(sharedLabel + entry.getDescription(), 64),
                    state + " · " + FormatUtils.formatDuration(entry.getDuration()),
                    active,
                    active && !entry.isVirtual() && !shared,
                    entry.getStartTime(),
                    processParentKey(entry, processKeys, processKeysByPid),
                    0));
        }
        // One-shot completion monitors are traceable wake-up configurations, not
        // processes. Surface each one nested under the process it watches so the
        // agent-visible event list shows every managed wake the session armed.
        for (ProcessMonitor monitor : processManager.listMonitors()) {
            String wakeMessage = monitor.message();
            rawItems.add(new ActivityItem(
                    "monitor:" + monitor.processId(),
                    monitor.processId(),
                    ActivityKind.MONITOR,
                    truncate(wakeMessage == null || wakeMessage.isBlank()
                            ? "wake on exit of " + monitor.processId() : wakeMessage, 64),
                    "armed · " + FormatUtils.formatDuration(java.time.Duration.between(
                            monitor.createdAt(), Instant.now())),
                    true,
                    true,
                    monitor.createdAt(),
                    "process:" + monitor.processId(),
                    0));
        }
        for (BackgroundTask task : taskManager.getActiveTasks()) {
            if (task.getStatus() != BackgroundTask.BackgroundTaskStatus.BACKGROUNDED
                    || isRepresentedBySubagent(task, activeSubagents, recentSubagents)) {
                // Ctrl+B detaches a parent turn only while TaskTool is blocked on a
                // child. That exact child row owns cancellation and retained follow-up
                // input even after its first response completes; publishing the parent
                // task beside it creates a second row that looks interactive but routes
                // typed text back to Main. Keep the task row only after the linked child
                // has left both the active and visible recent activity lists.
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
                if (!activity.active && !completionVisible(activity.key, activity.updatedAt)) continue;
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
                .comparing(ActivityItem::killable).reversed()
                .thenComparingInt(item -> item.kind() == ActivityKind.PROJECT ? 0 : 1)
                .thenComparing(Comparator.comparing(ActivityItem::active).reversed())
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

    private boolean isRepresentedBySubagent(
            BackgroundTask task,
            List<SubagentEntry> activeSubagents,
            List<SubagentEntry> recentSubagents) {
        synchronized (backgroundTaskLinkLock) {
            if (!task.getId().equals(linkedBackgroundTaskId)) {
                linkedBackgroundTaskId = task.getId();
                linkedSubagentId = "";
            }
            if (linkedSubagentId.isBlank()) {
                SubagentEntry newest = null;
                for (SubagentEntry entry : activeSubagents) {
                    if (!entry.getStartedAt().isBefore(task.getStartedAt())
                            && (newest == null
                            || entry.getStartedAt().isAfter(newest.getStartedAt()))) {
                        newest = entry;
                    }
                }
                if (newest == null) {
                    for (SubagentEntry entry : recentSubagents) {
                        if (!entry.getStartedAt().isBefore(task.getStartedAt())
                                && (newest == null
                                || entry.getStartedAt().isAfter(newest.getStartedAt()))) {
                            newest = entry;
                        }
                    }
                }
                if (newest != null) linkedSubagentId = newest.getId();
            }
            if (linkedSubagentId.isBlank()) return false;
            for (SubagentEntry entry : activeSubagents) {
                if (linkedSubagentId.equals(entry.getId())) return true;
            }
            for (SubagentEntry entry : recentSubagents) {
                if (linkedSubagentId.equals(entry.getId())) return true;
            }
            return false;
        }
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
        SubagentRunner runner = subagentRunner;
        boolean killable = active && runner != null && runner.canCancel(entry.getId());
        return new ActivityItem(
                "subagent:" + entry.getId(),
                entry.getId(),
                ActivityKind.SUBAGENT,
                truncate(label, 64),
                status + " · " + entry.getElapsed(),
                active,
                killable,
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

    /** "agent · session" owner tag for a coordination-published shared mirror. */
    private static String sharedOwnerLabel(ProcessEntry entry) {
        String agent = entry.getMetadata().get("ownerAgent");
        if (agent == null || agent.isBlank()) {
            agent = entry.getMetadata().get("ownerSessionId");
        }
        return agent == null || agent.isBlank() ? "shared" : agent;
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
        int panelRows = Math.max(1, reservedRowSupplier.getAsInt());
        List<ChatCompleter.CompletionItem> completions = completionItems;
        if (!completions.isEmpty()) {
            renderCompletions(completions, panelRows);
            return;
        }

        List<ActivityItem> items = activityItems();
        normalizeSelection(items);

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
                String status = item.status() + (item.killable() ? " · Del stop" : "");
                menu.add(new StatusBar.MenuItem(
                        item.key(), label, status,
                        focused && item.key().equals(selectedKey)));
            } else if (items.size() == 1 && i == 0) {
                menu.add(new StatusBar.MenuItem(
                        "activity-idle", "No auxiliary chats, active processes, or subagents", "", false));
            } else {
                menu.add(new StatusBar.MenuItem("activity-spacer-" + i, "", "", false));
            }
        }

        String viewedSubagentTarget = viewedSubagentId();
        String hint;
        if (focused) {
            String inputTarget;
            if (!subagentTargetForKey(selectedKey).isBlank()
                    && !selectedKey.equals(viewedKey)) {
                inputTarget = "Enter opens subagent; then type sends follow-up";
            } else if (!viewedSubagentTarget.isBlank()) {
                inputTarget = "type sends to open subagent when supported";
            } else {
                inputTarget = "type for Main chat";
            }
            hint = "↑/↓ select · ← parent · Enter open · Del stop · " + inputTarget;
        } else if (items.size() == 1) {
            hint = "/processes shows session activity";
        } else {
            int hidden = Math.max(0, items.size() - visibleItems.size());
            hint = isViewingMain()
                    ? "↓ manage process tree · Enter opens output in transcript"
                    + (taskManager.isCurrentTaskBackgroundable()
                    ? " · Ctrl+B backgrounds active subagent" : "")
                    : "Viewing " + viewedKey.replaceFirst("^[^:]+:", "")
                    + (!viewedSubagentTarget.isBlank()
                    ? " · type sends follow-up when supported" : "")
                    + " · PageUp/PageDown scroll · select Main chat + Enter to return";
            hint += ""
                    + (hidden > 0 ? " · " + hidden + " more" : "");
        }
        if (!panelMessage.isBlank() && now.get().isBefore(panelMessageExpiresAt)) {
            scheduleExpiry(panelMessageExpiresAt);
            hint = panelMessage + " · " + hint;
        }

        currentMenuItems = List.copyOf(menu);
        statusBar.setMenuItems(menu, hint);
    }

    private void renderCompletions(
            List<ChatCompleter.CompletionItem> completions, int panelRows) {
        boolean showHint = panelRows > 1;
        int itemRows = Math.max(1, panelRows - (showHint ? 1 : 0));
        int showing = Math.min(completions.size(), itemRows);
        List<StatusBar.MenuItem> menu = new ArrayList<>(itemRows);
        for (int i = 0; i < showing; i++) {
            ChatCompleter.CompletionItem completion = completions.get(i);
            String status = completion.description() == null ? "" : completion.description();
            if (i == showing - 1 && completions.size() > showing) {
                String remaining = "+" + (completions.size() - showing) + " more";
                status = status.isBlank() ? remaining : status + " · " + remaining;
            }
            menu.add(new StatusBar.MenuItem(
                    "completion-" + i, completion.value(), status, false));
        }
        // Keep the reserved panel height stable as filtering removes candidates.
        // Blank rows erase the previous frame and keep the separator anchored.
        for (int i = showing; i < itemRows; i++) {
            menu.add(new StatusBar.MenuItem("completion-spacer-" + i, "", "", false));
        }
        currentMenuItems = List.copyOf(menu);
        statusBar.setMenuItems(menu,
                showHint ? "Slash commands · type to filter · Tab completes" : "");
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
            hideProjectActivity();
            viewedKey = MAIN_KEY;
            setPanelMessage("restored Main chat");
            refresh();
            return new ActivityView(MAIN_KEY, "Main chat", "", true);
        }

        if (item.kind() == ActivityKind.PROJECT && projectActivityView != null) {
            projectActivityView.show("");
        } else {
            hideProjectActivity();
        }
        viewedKey = item.key();
        setPanelMessage("viewing " + item.id());
        refresh();
        return activityView(item);
    }

    ActivityView openProjectActivity(String filter) {
        ProjectActivityView project = projectActivityView;
        if (project == null) return null;
        focused = false;
        selectedKey = "";
        selectedIndex = -1;
        viewedKey = PROJECT_ACTIVITY_KEY;
        project.show(filter);
        setPanelMessage(filter == null || filter.isBlank()
                ? "viewing project activity" : "filtered project activity");
        refresh();
        return new ActivityView(PROJECT_ACTIVITY_KEY, project.title(), project.content(), false);
    }

    void refreshProjectActivity() {
        ProjectActivityView project = projectActivityView;
        if (project != null) project.refresh();
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
        if (viewedKey.startsWith("process:")) {
            String id = viewedKey.substring("process:".length());
            ProcessEntry process = processManager.get(id);
            if (process != null) {
                return activityView(new ActivityItem(
                        viewedKey,
                        id,
                        ActivityKind.PROCESS,
                        truncate(process.getDescription(), 64),
                        process.getState().name().toLowerCase(Locale.ROOT)
                                + " · " + FormatUtils.formatDuration(process.getDuration()),
                        process.isRunning(),
                        process.isRunning() && !process.isVirtual(),
                        process.getStartTime(),
                        MAIN_KEY,
                        1));
            }
        }
        // An asynchronously evicted subagent/tool/task must not leave a hidden
        // viewedKey behind after the renderer falls back to Main. That stale key
        // would continue routing subsequent input to the vanished child.
        returnToMain();
        return new ActivityView(MAIN_KEY, "Main chat", "", true);
    }

    private ActivityView activityView(ActivityItem item) {
        if (item.kind() == ActivityKind.PROJECT) {
            ProjectActivityView project = projectActivityView;
            if (project != null) {
                return new ActivityView(
                        PROJECT_ACTIVITY_KEY, project.title(), project.content(), false);
            }
        }
        StringBuilder details = new StringBuilder();
        details.append("  kind: ").append(item.kind().label())
                .append(" · status: ").append(item.status()).append("\n");
        details.append("  ").append(item.label()).append("\n");

        if (item.kind() == ActivityKind.REPL) {
            AuxiliaryChatRepl repl;
            synchronized (auxiliaryReplLock) {
                repl = auxiliaryRepls.get(item.key());
            }
            if (repl != null && !repl.transcript().isBlank()) {
                details.append("\n").append(repl.transcript());
            }
        } else if (item.kind() == ActivityKind.PROCESS) {
            ProcessEntry process = processManager.get(item.id());
            if (process != null) {
                // The log IS the view. The streaming output fills the body so
                // opening a row means reading the live log, not staring at its
                // file path; metadata collapses into a footer beneath it.
                details.append("\n").append(processManager.readOutput(item.id(), 2000))
                        .append("\n");
                details.append("\n  command: ").append(process.getCommand()).append("\n");
                details.append("  pid: ").append(process.getPid());
                if (process.getOutputFile() != null) {
                    details.append(" · log: ").append(process.getOutputFile());
                }
                details.append("\n");
                if (process.getKind() == ProcessKind.SHARED) {
                    details.append("  owner: ").append(sharedOwnerLabel(process))
                            .append(" · mirrored from coordination state; inspect/logs only\n");
                }
            }
        } else if (item.kind() == ActivityKind.MONITOR) {
            ProcessMonitor monitor = processManager.getMonitor(item.id());
            if (monitor != null) {
                String wakeMessage = monitor.message();
                details.append("  wake: agent is woken when the watched process exits\n")
                        .append("  message: ")
                        .append(wakeMessage == null || wakeMessage.isBlank()
                                ? "(none)" : wakeMessage).append("\n")
                        .append("  armed for: ").append(FormatUtils.formatDuration(
                                java.time.Duration.between(monitor.createdAt(), Instant.now())))
                        .append("\n")
                        .append("  Del cancels the wake-up without touching the process\n");
            }
            ProcessEntry watched = processManager.get(item.id());
            if (watched != null) {
                details.append("  watched: ").append(watched.getId())
                        .append(" · ").append(watched.getState())
                        .append(" · command: ").append(watched.getCommand()).append("\n");
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
                String subagentId = subagentTargetForKey(item.key());
                if (!subagentId.isBlank()) {
                    details.append("  follow-ups: subagent ").append(subagentId).append("\n");
                }
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
        returnToMain("");
    }

    private void returnToMain(String message) {
        hideProjectActivity();
        viewedKey = MAIN_KEY;
        setPanelMessage(message);
        refresh();
    }

    private void hideProjectActivity() {
        ProjectActivityView project = projectActivityView;
        if (project != null && project.isVisible()) project.hide();
    }

    String killSelected() {
        ActivityItem item = selectedItem();
        if (item == null) {
            return "No activity selected";
        }
        if (!item.killable()) {
            setPanelMessage(switch (item.kind()) {
                case PROCESS -> item.active()
                        ? item.id() + " is a non-owned watcher; inspect/logs only"
                        : "process is no longer running: " + item.id();
                case SUBAGENT -> "subagent is no longer running: " + item.id();
                case REPL -> item.id() + " is an in-process supervisory chat; inspect only";
                default -> item.id() + " is informational; inspect/logs only";
            });
            refresh();
            return panelMessage;
        }

        if (item.kind() == ActivityKind.MONITOR) {
            boolean cancelled = processManager.removeMonitor(item.id());
            setPanelMessage(cancelled
                    ? "monitor cancelled for " + item.id()
                    : "no active monitor: " + item.id());
            refresh();
            return panelMessage;
        }

        boolean killed;
        if (item.kind() == ActivityKind.PROCESS) {
            killed = processManager.kill(item.id());
            setPanelMessage(killed
                    ? "kill requested for " + item.id()
                    : "process is no longer running: " + item.id());
        } else if (item.kind() == ActivityKind.SUBAGENT) {
            SubagentRunner runner = subagentRunner;
            killed = runner != null && runner.cancel(item.id());
            setPanelMessage(killed
                    ? "cancel requested for subagent " + item.id()
                    : "subagent is no longer running: " + item.id());
        } else {
            setPanelMessage(item.id() + " is informational; inspect/logs only");
        }
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
        if (!selectedKey.isBlank()) {
            // An asynchronously completed/evicted row must not transfer a pending
            // Delete action to whichever process now occupies the same index.
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
