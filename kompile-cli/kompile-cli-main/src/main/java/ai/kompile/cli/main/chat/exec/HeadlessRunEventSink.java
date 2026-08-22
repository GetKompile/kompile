/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package ai.kompile.cli.main.chat.exec;

/** Receives ordered events from a headless run. Implementations should be non-blocking. */
@FunctionalInterface
public interface HeadlessRunEventSink {
    void accept(HeadlessRunEvent event);
}
