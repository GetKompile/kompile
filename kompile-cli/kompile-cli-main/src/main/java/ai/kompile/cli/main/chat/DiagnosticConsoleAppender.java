/* Copyright 2026 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.cli.main.chat;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import ch.qos.logback.core.encoder.Encoder;

/**
 * CLI diagnostics follow the current stderr route rather than retaining the
 * pre-chat stdout descriptor. Transcript scopes persist them before showing a
 * transient notice; headless commands keep ordinary stderr logging.
 */
public final class DiagnosticConsoleAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {
    private Encoder<ILoggingEvent> encoder;

    public void setEncoder(Encoder<ILoggingEvent> encoder) { this.encoder = encoder; }
    public Encoder<ILoggingEvent> getEncoder() { return encoder; }

    @Override public void start() {
        if (encoder == null) {
            addError("Diagnostic console appender requires an encoder");
            return;
        }
        super.start();
    }

    @Override protected void append(ILoggingEvent event) {
        byte[] bytes = encoder.encode(event);
        if (bytes != null) System.err.write(bytes, 0, bytes.length);
    }
}
