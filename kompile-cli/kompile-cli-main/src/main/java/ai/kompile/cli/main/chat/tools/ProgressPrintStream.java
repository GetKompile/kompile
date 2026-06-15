/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.tools;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.function.Consumer;

/**
 * A PrintStream adapter that forwards output to both a {@link Consumer} (for
 * progress logging via MCP hooks/activity log) and the original stderr.
 *
 * <p>Used by tools that pass a PrintStream to long-running operations
 * (e.g., LocalCodeIndexer) to bridge progress into the MCP activity log
 * and ToolContext outputConsumer pipeline.</p>
 */
public class ProgressPrintStream extends PrintStream {

    private final Consumer<String> consumer;
    private final StringBuilder lineBuffer = new StringBuilder();

    /**
     * Create a progress-forwarding PrintStream.
     *
     * @param consumer receives each complete line of output (for logging/hooks)
     */
    public ProgressPrintStream(Consumer<String> consumer) {
        super(System.err, true);
        this.consumer = consumer;
    }

    @Override
    public void println(String x) {
        super.println(x);
        if (consumer != null && x != null) {
            consumer.accept(x);
        }
    }

    @Override
    public void println(Object x) {
        String s = String.valueOf(x);
        super.println(s);
        if (consumer != null) {
            consumer.accept(s);
        }
    }

    @Override
    public void print(String s) {
        super.print(s);
        // Buffer partial lines (e.g., carriage-return progress bars)
        if (s != null && consumer != null) {
            if (s.contains("\r") || s.contains("\n")) {
                lineBuffer.append(s);
                String buffered = lineBuffer.toString();
                // Split on newlines, emit complete lines
                String[] lines = buffered.split("[\\r\\n]+");
                for (String line : lines) {
                    if (!line.isEmpty()) {
                        consumer.accept(line);
                    }
                }
                lineBuffer.setLength(0);
            } else {
                lineBuffer.append(s);
            }
        }
    }

    @Override
    public void flush() {
        super.flush();
        if (lineBuffer.length() > 0 && consumer != null) {
            consumer.accept(lineBuffer.toString());
            lineBuffer.setLength(0);
        }
    }

    /**
     * Create a ProgressPrintStream from a ToolContext. Falls back to raw System.err
     * if the context has no outputConsumer set.
     */
    public static PrintStream from(ToolContext context) {
        Consumer<String> oc = context != null ? context.getOutputConsumer() : null;
        if (oc == null) {
            return System.err;
        }
        return new ProgressPrintStream(oc);
    }
}
