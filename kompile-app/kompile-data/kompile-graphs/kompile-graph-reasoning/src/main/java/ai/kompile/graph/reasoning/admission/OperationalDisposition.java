/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ai.kompile.graph.reasoning.admission;

/**
 * Domain-operational disposition derived from graph evidence.
 *
 * <p>This is intentionally separate from {@link AdmissionDecision}: admission decides node identity
 * and staging, while an operational disposition decides whether a caller should act on the domain
 * object represented by that node.</p>
 */
public enum OperationalDisposition {
    /** The configured evidence policy permits the operation. */
    ALLOW,
    /** The configured evidence policy prohibits the operation. */
    DENY,
    /** Evidence is missing, degraded, or requires a non-automatic decision. */
    REVIEW
}
