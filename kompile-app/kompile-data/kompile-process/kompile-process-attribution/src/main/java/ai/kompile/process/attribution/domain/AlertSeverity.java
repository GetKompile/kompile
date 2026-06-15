/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.process.attribution.domain;

/**
 * Severity levels for process event alerts.
 */
public enum AlertSeverity {
    /** Imminent or actual failure requiring immediate action. */
    CRITICAL,
    /** High-probability risk that will likely cause problems. */
    HIGH,
    /** Moderate risk that warrants monitoring. */
    MEDIUM,
    /** Low-probability risk for informational purposes. */
    LOW,
    /** Informational — no risk, just context. */
    INFO
}
