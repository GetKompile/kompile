/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.enforcer.EnforcerDiagnostics;
import ai.kompile.cli.main.codeindex.CodeIndexDiagnostics;
import org.jline.reader.EndOfFileException;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;

import java.io.IOError;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * Opt-in, in-process standard-chat host. Exactly one caller reads the shared terminal;
 * background work belongs to retained REPLs, not to terminal focus. No child run() loops.
 * Numbers are stable for the lifetime of the host and are never reused.
 *
 * <p>/sessions lists live sessions; /session new creates one; /session N switches;
 * /session close, /quit, /exit, Ctrl-C and EOF close only the selected session.
 * Closing the last session exits the host. /clear replaces only the selected session.
 * Project loops have a stable first-session target and are NOT retargeted on focus change.
 * Closing that target makes subsequent project fires fail explicitly until host restart.
 * Session loops remain session-owned. All sessions use the same project directory.</p>
 */
public final class MultiChatSessionHost implements AutoCloseable {
    static final String HELP = "Multi-session: /sessions | /session new | /session <number> | /session close\n"
            + "/quit, /exit, Ctrl-C and EOF close this session; closing the last exits. /clear replaces this session.\n"
            + "Project loops target the first session (restart the host if that session is closed).\n"
            + "Direct standard chat only; no passthrough, nested resume/menu/setup/restart, or directory changes.\n"
            + "Scheduled loops accept chat prompts only in this mode (no slash commands).";

    /** The production adapter below uses the real ChatRepl lifecycle. Injectable for ownership tests. */
    interface Session extends AutoCloseable {
        String id();
        void start() throws Exception;
        void attach() throws Exception;
        boolean isAttached();
        void detach() throws Exception;
        String read() throws Exception;
        boolean submit(String line) throws Exception;
        boolean ownsInput();
        boolean replacementRequested();
        void message(String text) throws Exception;
        default void diagnostic(String text) throws Exception { message(text); }
        void finish(boolean normal, IOException failure) throws Exception;
        @Override void close() throws Exception;
    }

    @FunctionalInterface
    interface SessionFactory {
        Session create(String id, boolean resume) throws Exception;
    }

    private final Terminal terminal;
    private final SessionFactory factory;
    private final ConcurrentSkipListMap<Integer, Session> sessions = new ConcurrentSkipListMap<>();
    private int nextNumber = 1;
    private volatile int selected;
    private boolean closed;

    MultiChatSessionHost(Terminal terminal, SessionFactory factory) {
        this.terminal = Objects.requireNonNull(terminal);
        this.factory = Objects.requireNonNull(factory);
    }

    /** Installs one stream router and one diagnostic route before constructing any isolated REPL. */
    public static void run(ChatConfig config, Path cwd, String initialId, boolean resume,
                           String agent, boolean memory, boolean skipPermissions, String role) throws Exception {
        if (!supports(config)) throw new IllegalArgumentException("--multi-session requires a direct standard-chat provider");
        // JLine must capture the physical output streams, not the session router.
        try (Terminal terminal = ChatCompleter.buildSystemTerminal();
             SessionOutputRouter router = SessionOutputRouter.install()) {
            if (terminal.getType() == null || terminal.getType().startsWith("dumb")
                    || terminal.getHeight() < 12 || terminal.getWidth() < 20) {
                throw new IllegalArgumentException("--multi-session requires an interactive ANSI terminal at least 20x12");
            }
            ProductionFactory factory = new ProductionFactory(terminal, router.sessionErr(),
                    config.copy(), cwd.toAbsolutePath().normalize(), agent, memory, skipPermissions, role);
            // The host owns terminal closure. Its close method must not be called a second time by a REPL.
            try (factory; MultiChatSessionHost host = new MultiChatSessionHost(terminal, factory)) {
                // A closed fixed target cannot display its own dispatch failure. Report
                // through the host without changing which session receives project work.
                factory.failureOutput = host::reportDiagnostic;
                Consumer<String> diagnostic = text -> {
                    // Bound diagnostics keep their origin. Unbound global diagnostics follow
                    // foreground focus through one host route, not a stack of session sinks.
                    if (!ChatUiSession.current().usesLegacyOutput()) ChatCompleter.showAlert(text);
                    else host.reportDiagnostic(text);
                };
                Runnable codeCleanup = CodeIndexDiagnostics.installAlertSink(diagnostic);
                Runnable enforcerCleanup = EnforcerDiagnostics.installAlertSink(diagnostic);
                try {
                    host.run(initialId, resume);
                } finally {
                    enforcerCleanup.run();
                    codeCleanup.run();
                }
            }
        }
    }

    static boolean supports(ChatConfig config) {
        return config != null && "standard".equalsIgnoreCase(config.getChatMode())
                && config.getProvider() != null && !config.getProvider().isBlank()
                && !config.isKompileServer() && !config.isKompileLocalServing();
    }

    static boolean unsupportedCommand(String line) {
        String[] parts = line.strip().toLowerCase(Locale.ROOT).split("\\s+", 2);
        return switch (parts[0]) {
            case "/passthrough", "/resume", "/resume-all", "/menu", "/setup", "/forward",
                    "/restart", "/reset", "/reset-all" -> true;
            case "/judge", "/enforcer", "/enforce" -> parts.length == 2 && interactiveJudgeRoute(parts[1]);
            case "/mode" -> parts.length == 2 && !List.of("standard", "local", "plan").contains(parts[1]);
            default -> false;
        };
    }

    private static boolean interactiveJudgeRoute(String arguments) {
        String[] parts = arguments.split("\\s+", 3);
        String action = "policy".equals(parts[0]) && parts.length > 1 ? parts[1] : parts[0];
        return "init".equals(action) || "setup".equals(action);
    }

    private void requireTerminalSize() {
        if (terminal.getWidth() < 20 || terminal.getHeight() < 12) {
            throw new IllegalStateException("--multi-session requires a terminal at least 20x12; resize and retry");
        }
    }

    private static void requireAttached(Session session) {
        if (!session.isAttached()) throw new IllegalStateException("Session terminal attachment did not succeed");
    }

    private static void restore(Session previous, Throwable failure) {
        if (previous == null) return;
        try {
            previous.attach();
            requireAttached(previous);
        } catch (Exception cleanup) {
            failure.addSuppressed(cleanup);
        }
    }

    void run(String initialId, boolean resume) throws Exception {
        create(initialId, resume);
        foreground().message(HELP);
        while (!sessions.isEmpty()) {
            Session session = foreground();
            try {
                String line = session.read();
                if (!session.ownsInput() && control(line)) continue;
                if (!session.submit(line)) {
                    boolean replace = session.replacementRequested();
                    closeSelected(!replace);
                    if (replace) createFromControl();
                }
            } catch (UserInterruptException | EndOfFileException end) {
                closeSelected();
            } catch (IOError error) {
                IOException failure = ChatRepl.terminalReadFailure(error);
                session.finish(false, failure);
                throw failure;
            }
        }
    }

    /** Only host commands are consumed; ordinary slash commands stay with ChatCommandRouter. */
    boolean control(String line) throws Exception {
        String value = line == null ? "" : line.strip();
        if (value.equalsIgnoreCase("/sessions")) {
            for (var entry : sessions.entrySet()) {
                foreground().message((entry.getKey() == selected ? "* " : "  ")
                        + entry.getKey() + "  " + entry.getValue().id());
            }
            return true;
        }
        String[] parts = value.split("\\s+", 2);
        if (!parts[0].equalsIgnoreCase("/session")) return false;
        String argument = parts.length == 2 ? parts[1].strip().toLowerCase(Locale.ROOT) : "";
        switch (argument) {
            case "new" -> createFromControl();
            case "close" -> closeSelected();
            default -> {
                int number;
                try {
                    number = Integer.parseInt(argument);
                } catch (NumberFormatException invalid) {
                    foreground().message(HELP);
                    return true;
                }
                if (!sessions.containsKey(number)) foreground().message("No live session " + number);
                else {
                    try {
                        select(number);
                    } catch (Exception failure) {
                        if (foreground() == null || !foreground().isAttached()) throw failure;
                        foreground().message("Could not select session " + number + ": " + failure.getMessage());
                    }
                }
            }
        }
        return true;
    }

    private void createFromControl() throws Exception {
        try {
            create(UUID.randomUUID().toString(), false);
        } catch (Exception failure) {
            if (foreground() == null || !foreground().isAttached()) throw failure;
            foreground().message("Could not create session: " + failure.getMessage());
        }
    }

    int create(String id, boolean resume) throws Exception {
        if (closed) throw new IllegalStateException("Host is closed");
        requireTerminalSize();
        Session previous = foreground();
        int previousNumber = selected;
        if (previous != null) previous.detach();
        Session created = null;
        try {
            created = factory.create(id, resume);
            created.start();
            requireAttached(created);
            int number = nextNumber++;
            sessions.put(number, created);
            selected = number;
            created.message("Session " + number + " — " + id);
            return number;
        } catch (Exception | Error failure) {
            if (created != null) {
                try { created.detach(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                try { created.close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                sessions.values().remove(created);
            }
            selected = previousNumber;
            restore(previous, failure);
            throw failure;
        }
    }

    void select(int number) throws Exception {
        Session target = sessions.get(number);
        if (target == null) throw new IllegalArgumentException("No live session " + number);
        if (selected == number) return;
        requireTerminalSize();
        Session previous = foreground();
        if (previous != null) previous.detach();
        try {
            target.attach();
            requireAttached(target);
            selected = number;
        } catch (Exception | Error failure) {
            // A failed attach may have acquired part of the terminal surface.
            try { target.detach(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            restore(previous, failure);
            throw failure;
        }
    }

    private Session foreground() { return sessions.get(selected); }

    private void reportDiagnostic(String text) {
        Session target = foreground();
        if (target != null) {
            try { target.diagnostic(text); }
            catch (Exception failure) {
                // The host owns this fallback stream; never use routed System.out recursively.
                terminal.writer().println(text);
                terminal.writer().flush();
            }
        } else {
            terminal.writer().println(text);
            terminal.writer().flush();
        }
    }

    void closeSelected() throws Exception { closeSelected(true); }

    private void closeSelected(boolean activateSibling) throws Exception {
        Session session = sessions.remove(selected);
        if (session == null) return;
        // Detach before finalization; hidden close must never attach or clear a sibling.
        try {
            session.detach();
            session.finish(true, null);
        } finally {
            session.close();
        }
        if (!sessions.isEmpty()) {
            selected = sessions.keySet().iterator().next();
            if (activateSibling) foreground().attach();
        } else if (activateSibling) {
            terminal.writer().println("All chat sessions closed.");
            terminal.writer().println("Resume: kompile chat --resume " + session.id() + " --mode standard");
            terminal.writer().flush();
        }
    }

    @Override public void close() throws Exception {
        if (closed) return;
        closed = true;
        Exception failure = null;
        for (Session session : List.copyOf(sessions.values())) {
            try {
                try { session.detach(); } finally { session.close(); }
            } catch (Exception problem) {
                if (failure == null) failure = problem; else failure.addSuppressed(problem);
            }
        }
        sessions.clear();
        // Terminal is closed by run's outer try-with-resources, not by retained sessions.
        if (failure != null) throw failure;
    }

    private static final class ProductionFactory implements SessionFactory, AutoCloseable {
        private final Terminal terminal;
        private final PrintStream errorRoute;
        private final ChatConfig config;
        private final Path cwd;
        private final String agent;
        private final boolean memory;
        private final boolean skipPermissions;
        private final String role;
        private ScheduledLoopManager projectLoops;
        private RetainedSession first;
        private Consumer<String> failureOutput;

        private ProductionFactory(Terminal terminal, PrintStream errorRoute, ChatConfig config, Path cwd,
                                  String agent, boolean memory, boolean skipPermissions, String role) {
            this.terminal = terminal;
            this.errorRoute = errorRoute;
            this.failureOutput = errorRoute::println;
            this.config = config;
            this.cwd = cwd;
            this.agent = agent;
            this.memory = memory;
            this.skipPermissions = skipPermissions;
            this.role = role;
        }

        @Override public Session create(String id, boolean resume) throws Exception {
            ChatUiSession ui = new ChatUiSession();
            TranscriptLogScope log = TranscriptLogScope.openIsolated(id, cwd, resume, errorRoute);
            ChatRepl repl = null;
            try (ChatUiSession.Binding ignored = ui.bind(); TranscriptLogScope.Binding ignoredLog = log.bind()) {
                List<String> startup = java.util.Collections.synchronizedList(new ArrayList<>());
                ChatCompleter.setContentOutput(startup::add);
                // The first session has already had explicit CLI overrides applied.
                ChatConfig sessionConfig = resume && first != null ? ChatConfig.loadSession(id) : null;
                repl = new ChatRepl(null, null, id, false, agent, memory,
                        sessionConfig == null ? config.copy() : sessionConfig, cwd);
                repl.installRetainedOutput();
                synchronized (startup) { startup.forEach(repl::showHostMessage); }
                repl.setDangerouslySkipPermissions(skipPermissions);
                if (role != null && !role.isBlank()) repl.assignRoleAtStartup(role);
                RetainedSession session = new RetainedSession(id, repl, ui, log, terminal);
                if (first == null) {
                    first = session;
                    projectLoops = new ScheduledLoopManager(session.context.wrapConsumer(session::dispatchProjectLoop),
                            ScheduledLoopManager.stateFileForProject(cwd),
                            (loop, failure) -> failureOutput.accept("Project loop " + loop.getId()
                                    + " failed: " + failure.getMessage()));
                }
                repl.configureHost(projectLoops);
                return session;
            } catch (Exception | Error failure) {
                if (repl != null) {
                    try (ChatUiSession.Binding ignored = ui.bind(); TranscriptLogScope.Binding ignoredLog = log.bind()) {
                        repl.close();
                    } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                }
                ui.close();
                log.close();
                throw failure;
            }
        }

        @Override public void close() {
            if (projectLoops != null) projectLoops.shutdown();
        }
    }

    private static final class RetainedSession implements Session {
        private final String id;
        private final ChatRepl repl;
        private final ChatUiSession ui;
        private final TranscriptLogScope log;
        private final Terminal terminal;
        private final ChatSessionContext context = ChatSessionContext.current();
        private volatile boolean closed;
        private boolean ready;
        private final List<String> pendingProjectPrompts = new ArrayList<>();

        private void dispatchProjectLoop(String prompt) {
            synchronized (this) {
                if (closed) throw new IllegalStateException("Project loop target (first session) is closed; restart the host");
                if (!ready) {
                    pendingProjectPrompts.add(prompt);
                    return;
                }
            }
            // A provider turn must never hold the session's lifecycle monitor.
            repl.dispatchHostProjectLoop(prompt);
        }

        private RetainedSession(String id, ChatRepl repl, ChatUiSession ui, TranscriptLogScope log, Terminal terminal) {
            this.id = id;
            this.repl = repl;
            this.ui = ui;
            this.log = log;
            this.terminal = terminal;
        }

        private <T> T call(Callable<T> work) throws Exception { return context.wrapCallable(work).call(); }
        @Override public String id() { return id; }
        @Override public void start() throws Exception {
            call(() -> {
                repl.initializeInteractive(terminal);
                List<String> pending;
                synchronized (this) {
                    ready = true;
                    pending = List.copyOf(pendingProjectPrompts);
                    pendingProjectPrompts.clear();
                }
                pending.forEach(repl::dispatchHostProjectLoop);
                return null;
            });
        }
        @Override public void attach() { context.wrap(repl::attachInteractive).run(); }
        @Override public boolean isAttached() { return repl.getTui().isTerminalAttached(); }
        @Override public void detach() { context.wrap(repl::detachInteractive).run(); }
        @Override public String read() throws Exception { return call(repl::readInteractiveLine); }
        @Override public boolean submit(String line) throws Exception { return call(() -> repl.submitInteractiveLine(line)); }
        @Override public boolean ownsInput() { return repl.ownsInteractiveInput(); }
        @Override public boolean replacementRequested() { return repl.isNewConversationRequested(); }
        @Override public void message(String text) { context.wrap(() -> repl.showHostMessage(text)).run(); }
        @Override public void diagnostic(String text) { context.wrap(() -> repl.getTui().showAlert(text)).run(); }
        @Override public void finish(boolean normal, IOException failure) {
            context.wrap(() -> repl.finishInteractive(normal, failure)).run();
        }
        @Override public void close() {
            synchronized (this) {
                if (closed) return;
                closed = true;
                pendingProjectPrompts.clear();
            }
            try { context.wrap(repl::close).run(); }
            finally { ui.close(); log.close(); }
        }
    }
}
