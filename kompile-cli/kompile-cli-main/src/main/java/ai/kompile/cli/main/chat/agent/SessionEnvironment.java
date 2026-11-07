/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.agent;

import java.util.Map;
import java.util.concurrent.Callable;

/**
 * The environment a tool session runs under.
 *
 * <p>A delegated agent's MCP server used to be its own process, and learned who it
 * serves (subagent depth, agent name, parent session, workflow participant, enforcer
 * policy) from that process's environment. A subagent session served from inside an
 * already running server has no process of its own, so the identity it was launched
 * with is set here for the threads that work for it, and identity reads go through
 * {@link #get(String)}: the session's value when it has one, else this process's.</p>
 *
 * <p>The overlay is deliberately not inherited by new threads: a pool first created
 * while a subagent's call ran would otherwise keep that subagent's identity for every
 * later caller. Each entry point that works for a session sets it ({@link #call},
 * {@link #run}), and work handed to another thread carries it explicitly
 * ({@link #wrap(Runnable)}, {@link #wrap(Callable)}).</p>
 */
public final class SessionEnvironment {

    private static final ThreadLocal<Map<String, String>> OVERLAY = new ThreadLocal<>();

    private SessionEnvironment() {
    }

    /** The session's value of {@code name}, else this process's environment variable. */
    public static String get(String name) {
        Map<String, String> overlay = OVERLAY.get();
        if (overlay != null && overlay.containsKey(name)) {
            return overlay.get(name);
        }
        return System.getenv(name);
    }

    /** {@link #get(String)}, or {@code fallback} when neither the session nor the process sets it. */
    public static String getOrDefault(String name, String fallback) {
        String value = get(name);
        return value != null ? value : fallback;
    }

    /** Run {@code work} under {@code overlay}; {@code null} or empty runs it as this process. */
    public static <T> T call(Map<String, String> overlay, Callable<T> work) throws Exception {
        if (overlay == null || overlay.isEmpty()) {
            return work.call();
        }
        Map<String, String> previous = OVERLAY.get();
        OVERLAY.set(Map.copyOf(overlay));
        try {
            return work.call();
        } finally {
            if (previous == null) OVERLAY.remove();
            else OVERLAY.set(previous);
        }
    }

    /** {@link #call} for work that returns nothing and throws no checked exception. */
    public static void run(Map<String, String> overlay, Runnable work) {
        try {
            call(overlay, () -> {
                work.run();
                return null;
            });
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The overlay this thread works under; empty when it works as this process. */
    public static Map<String, String> current() {
        Map<String, String> overlay = OVERLAY.get();
        return overlay != null ? overlay : Map.of();
    }

    /** {@code work} bound to the calling thread's session, for running on another thread. */
    public static Runnable wrap(Runnable work) {
        Map<String, String> overlay = current();
        return overlay.isEmpty() ? work : () -> run(overlay, work);
    }

    /** {@code work} bound to the calling thread's session, for running on another thread. */
    public static <T> Callable<T> wrap(Callable<T> work) {
        Map<String, String> overlay = current();
        return overlay.isEmpty() ? work : () -> call(overlay, work);
    }
}
