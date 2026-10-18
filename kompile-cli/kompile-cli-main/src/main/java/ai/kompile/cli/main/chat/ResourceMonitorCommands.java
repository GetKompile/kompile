package ai.kompile.cli.main.chat;

import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Model-free, per-chat live resource page. Sampling never enforces watchdog limits. */
public final class ResourceMonitorCommands implements AutoCloseable {
    public static final String VIEW_KEY = "resource-monitor";
    public static final String TITLE = "Resources · live · /resource-monitor stop returns to chat";
    public static final String HELP = "Usage: /resource-monitor [show|status|stop|help]\n"
            + "  /resource-monitor kill <process-id|watchdog:id> confirm\n"
            + "  /resource-monitor embeddings stop confirm\n"
            + "Kill targets this chat's tracked processes or this JVM's managed children; never arbitrary PIDs.\n"
            + "Embeddings stop releases an idle folder-local runtime; next query reloads it.\n"
            + "Pooled runtimes may be shared by this JVM's chats/crawls; stopping interrupts their work.";

    public interface View {
        boolean available();
        boolean selected();
        void show(String content);
        void update(String content);
        void hide();
    }
    @FunctionalInterface public interface Control { String execute(String action, String id) throws Exception; }
    private final Supplier<String> sample;
    private final View view;
    private final Control control;
    private ScheduledExecutorService worker;
    private boolean following;
    private boolean closed;
    private volatile String latest = "Waiting for first sample…";
    private String lastAction = "";

    public ResourceMonitorCommands(Supplier<String> sample, View view, Control control) {
        this.sample = sample;
        this.view = view;
        this.control = control;
    }

    public synchronized String command(String arguments) throws Exception {
        String input = arguments == null ? "" : arguments.strip();
        String[] words = input.isEmpty() ? new String[0] : input.split("\\s+");
        String action = words.length == 0 ? "show" : words[0].toLowerCase(Locale.ROOT);
        if (closed) return "Resource monitor is closed.";
        if (words.length <= 1) {
            switch (action) {
                case "help":
                    if (view.selected()) { stopFollowing(); view.hide(); }
                    return HELP;
                case "stop", "hide", "off":
                    stopFollowing();
                    if (view.selected()) view.hide();
                    return "Resource monitor stopped (subprocesses keep running).";
                case "status":
                    if (view.selected()) { stopFollowing(); view.hide(); }
                    return sample.get();
                case "show", "on":
                    if (!view.available()) return sample.get() + "\nLive updates require an interactive CLI terminal.";
                    view.show(present(latest));
                    if (!following) {
                        following = true;
                        worker = Executors.newSingleThreadScheduledExecutor(r -> {
                            Thread thread = new Thread(r, "chat-resource-monitor");
                            thread.setDaemon(true);
                            return thread;
                        });
                        worker.scheduleWithFixedDelay(this::refresh, 0, 1, TimeUnit.SECONDS);
                    }
                    return "";
                default: return HELP;
            }
        }
        if (words.length == 3 && action.equals("kill") && words[2].equalsIgnoreCase("confirm"))
            return reportAction(control.execute("kill", words[1]));
        if (words.length == 3 && action.equals("embeddings") && words[1].equalsIgnoreCase("stop")
                && words[2].equalsIgnoreCase("confirm")) return reportAction(control.execute("embeddings", ""));
        return reportAction(HELP + "\nNo action taken. Destructive actions require the literal confirmation word 'confirm'.");
    }

    void refresh() {
        synchronized (this) {
            if (closed || !following) return;
            if (!view.selected()) { stopFollowing(); return; }
        }
        String content;
        try { content = sample.get(); }
        catch (RuntimeException failure) { content = "Resource sample unavailable: " + failure.getClass().getSimpleName(); }
        synchronized (this) {
            if (closed || !following || !view.selected()) return;
            latest = content;
            view.update(present(content));
        }
    }

    private String reportAction(String result) {
        lastAction = result.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", " ");
        if (view.selected()) view.update(present(latest));
        return result;
    }

    private String present(String content) {
        return lastAction.isBlank() ? content : "Last action: " + lastAction + "\n\n" + content;
    }

    private void stopFollowing() {
        following = false;
        if (worker != null) { worker.shutdownNow(); worker = null; }
    }

    @Override public synchronized void close() {
        closed = true;
        stopFollowing();
    }
}
