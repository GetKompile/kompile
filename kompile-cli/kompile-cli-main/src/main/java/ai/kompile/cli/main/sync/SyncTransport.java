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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.cli.main.sync;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Byte transport for one sync session with a peer. Implementations establish
 * the channel (local subprocess or SSH) and hand back the stdin/stdout pair on
 * which the JSON-lines protocol runs.
 */
public interface SyncTransport extends AutoCloseable {

    static String shellQuote(String value) {
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid sync endpoint argument.");
        }
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    /** Starts the peer endpoint and returns its stdin for writing. */
    java.io.OutputStream stdin() throws IOException;

    /** Returns the peer endpoint's stdout for reading. */
    java.io.InputStream stdout() throws IOException;

    /** Human-readable description of the connected peer. */
    String describe();

    /**
     * A transport backed by a local subprocess of this same kompile binary.
     * Used for tests and for {@code --local} sync between two kompile homes on
     * the same machine.
     */
    static SyncTransport localSubprocess(java.nio.file.Path kompileBinary,
                                         java.nio.file.Path remoteHome,
                                         Map<String, String> extraEnv) {
        return localSubprocess(kompileBinary, remoteHome, extraEnv, List.of());
    }

    static SyncTransport localSubprocess(java.nio.file.Path kompileBinary,
                                         java.nio.file.Path remoteHome,
                                         Map<String, String> extraEnv, List<String> endpointOptions) {
        return new SyncTransport() {
            private Process process;

            private Process ensure() throws IOException {
                if (process == null) {
                    List<String> command = new java.util.ArrayList<>();
                    command.add(kompileBinary.toAbsolutePath().toString());
                    command.add("sync");
                    command.add("serve");
                    command.add("--home");
                    command.add(SyncPaths.expandHome(remoteHome).toAbsolutePath().toString());
                    command.addAll(endpointOptions);
                    ProcessBuilder builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT);
                    builder.environment().putAll(extraEnv);
                    process = builder.start();
                }
                return process;
            }

            @Override
            public java.io.OutputStream stdin() throws IOException {
                return ensure().getOutputStream();
            }

            @Override
            public java.io.InputStream stdout() throws IOException {
                return ensure().getInputStream();
            }

            @Override
            public String describe() {
                return "local:" + remoteHome;
            }

            @Override
            public void close() {
                if (process != null && process.isAlive()) {
                    process.destroy();
                    try {
                        if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                            process.destroyForcibly();
                        }
                    } catch (InterruptedException e) {
                        process.destroyForcibly();
                        Thread.currentThread().interrupt();
                    }
                }
            }
        };
    }

    /**
     * SSH transport: runs {@code <ssh> <user@host> kompile sync serve} remotely.
     * Kompile must already be on the remote PATH; host authenticity, keys, and
     * agent forwarding remain the user's existing SSH configuration.
     */
    static SyncTransport ssh(String sshTarget,
                             int port,
                             java.nio.file.Path sshExecutable,
                             Map<String, String> extraEnv) {
        return ssh(sshTarget, port, sshExecutable, extraEnv, List.of());
    }

    static SyncTransport ssh(String sshTarget, int port, java.nio.file.Path sshExecutable,
                             Map<String, String> extraEnv, List<String> endpointOptions) {
        return new SyncTransport() {
            private Process process;

            private Process ensure() throws IOException {
                if (process == null) {
                    List<String> command = new java.util.ArrayList<>();
                    command.add(sshExecutable.toString());
                    if (port > 0) {
                        command.add("-p");
                        command.add(String.valueOf(port));
                    }
                    command.add("-o");
                    command.add("BatchMode=yes");
                    command.add("--");
                    command.add(sshTarget);
                    List<String> remoteCommand = new java.util.ArrayList<>(List.of("kompile", "sync", "serve"));
                    remoteCommand.addAll(endpointOptions);
                    command.add(remoteCommand.stream().map(SyncTransport::shellQuote)
                            .collect(java.util.stream.Collectors.joining(" ")));
                    ProcessBuilder builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT);
                    builder.environment().putAll(extraEnv);
                    process = builder.start();
                }
                return process;
            }

            @Override
            public java.io.OutputStream stdin() throws IOException {
                return ensure().getOutputStream();
            }

            @Override
            public java.io.InputStream stdout() throws IOException {
                return ensure().getInputStream();
            }

            @Override
            public String describe() {
                return "ssh:" + sshTarget + (port > 0 ? ":" + port : "");
            }

            @Override
            public void close() {
                if (process != null && process.isAlive()) {
                    process.destroy();
                    try {
                        if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                            process.destroyForcibly();
                        }
                    } catch (InterruptedException e) {
                        process.destroyForcibly();
                        Thread.currentThread().interrupt();
                    }
                }
            }
        };
    }
}
