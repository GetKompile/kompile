package ai.kompile.app.services.agent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Disposable JDK-only process. No installed CLI, MCP/provider, product service, or shell. */
public final class ProductWorkerProcessFixture {
    private static long sequence;

    private ProductWorkerProcessFixture() { }

    static Process start(String... arguments) throws java.io.IOException {
        var command = new java.util.ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
        command.add(ProductWorkerProcessFixture.class.getName());
        command.addAll(java.util.List.of(arguments));
        return new ProcessBuilder(command).start();
    }

    public static void main(String[] arguments) throws Exception {
        String mode = arguments[0];
        if (mode.equals("leaf") || mode.equals("stubborn")) {
            if (mode.equals("stubborn")) resistGracefulTermination();
            System.out.println("ready"); System.out.flush();
            new CountDownLatch(1).await();
            return;
        }
        Process descendant = null;
        if (mode.equals("tree") || mode.equals("transport-tree")) {
            // The parent's JVM process reaper stays alive until the worker has killed/reaped
            // the descendant, avoiding any dependency on the machine's PID-1 zombie handling.
            descendant = start(mode.equals("tree") ? "stubborn" : "leaf");
            try (var ready = new BufferedReader(new InputStreamReader(descendant.getInputStream(), StandardCharsets.UTF_8))) {
                if (!"ready".equals(ready.readLine())) throw new IllegalStateException("Descendant did not start");
            }
            if (mode.equals("tree")) resistGracefulTermination();
        }
        String connection = arguments.length > 1
                ? new String(Base64.getDecoder().decode(arguments[1]), StandardCharsets.UTF_8) : "{}";
        try (var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String frame;
            while ((frame = input.readLine()) != null) {
                String request = field(frame, "requestId");
                switch (field(frame, "action")) {
                    case "session_open" -> emit("session_ready", "\"version\":1,\"sessionId\":\"" + field(frame, "sessionId")
                            + "\",\"policyDigest\":\"" + field(frame, "policyDigest") + "\"");
                    case "read_capability" -> {
                        emit("capability_result", "\"requestId\":\"" + request + "\",\"capability\":\""
                                + field(frame, "capability") + "\",\"ok\":true,\"content\":\""
                                + connection.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
                        emit("session_idle", "");
                    }
                    case "turn_submit" -> emit("turn_started", "\"requestId\":\"" + request + "\",\"turnId\":\"" + request + "\"");
                    case "session_close" -> {
                        if (!mode.equals("tree")) {
                            if (descendant != null) {
                                descendant.destroy();
                                if (!descendant.waitFor(2, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture descendant did not exit");
                            }
                            emit("session_closed", "");
                            if (mode.equals("slow-cleanup")) Thread.sleep(3_000);
                            if (mode.equals("content-slow-cleanup")) Thread.sleep(12_500);
                            if (!mode.equals("missing-cleanup")) emit("session_cleanup", "\"confirmed\":" + !mode.equals("uncertain-cleanup")
                                    + (mode.equals("content-slow-cleanup") ? ",\"managedWorkCleanupConfirmed\":true" : ""));
                            return;
                        }
                        // Deliberately ignore graceful protocol close so the forced-tree path is real.
                    }
                    default -> { }
                }
            }
        }
    }

    private static void resistGracefulTermination() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { new CountDownLatch(1).await(30, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }));
    }

    private static String field(String json, String name) {
        Matcher match = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        if (!match.find()) throw new IllegalArgumentException("Fixture frame missing " + name);
        return match.group(1);
    }
    private static void emit(String type, String data) {
        System.out.println("{\"seq\":" + (++sequence) + ",\"type\":\"" + type + "\",\"data\":{" + data + "}}");
        System.out.flush();
    }
}
