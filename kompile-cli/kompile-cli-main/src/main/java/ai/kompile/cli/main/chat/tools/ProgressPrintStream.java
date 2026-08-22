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

import ai.kompile.cli.main.codeindex.CodeIndexDiagnostics;

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
        String prefix = lineBuffer.toString();
        lineBuffer.setLength(0);
        forward(prefix + (x == null ? "null" : x), true);
    }

    @Override
    public void println(Object x) {
        println(String.valueOf(x));
    }

    @Override
    public void print(String s) {
        if (s == null) return;
        lineBuffer.append(s.replace('\r', '\n'));
        int newline;
        while ((newline = lineBuffer.indexOf("\n")) >= 0) {
            String line = lineBuffer.substring(0, newline);
            lineBuffer.delete(0, newline + 1);
            if (!line.isEmpty()) forward(line, true);
        }
    }

    @Override
    public void flush() {
        if (lineBuffer.length() > 0) {
            forward(lineBuffer.toString(), false);
            lineBuffer.setLength(0);
        }
        super.flush();
    }

    private void forward(String line, boolean newline) {
        if (line == null) return;
        if (CodeIndexDiagnostics.isAlertLine(line)
                && CodeIndexDiagnostics.hasAlertSink()) {
            CodeIndexDiagnostics.alert(line);
            return;
        }
        if (!CodeIndexDiagnostics.hasAlertSink()) {
            if (newline) super.println(line);
            else super.print(line);
        }
        if (consumer != null) consumer.accept(line);
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
