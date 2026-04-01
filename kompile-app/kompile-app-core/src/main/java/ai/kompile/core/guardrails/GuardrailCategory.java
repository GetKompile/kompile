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

package ai.kompile.core.guardrails;

/**
 * Categories of content that guardrails can detect and filter.
 */
public enum GuardrailCategory {
    /**
     * Prompt injection attempts.
     */
    PROMPT_INJECTION,

    /**
     * Jailbreak attempts.
     */
    JAILBREAK,

    /**
     * Toxic or hateful content.
     */
    TOXICITY,

    /**
     * Personally identifiable information.
     */
    PII,

    /**
     * Sensitive business data.
     */
    SENSITIVE_DATA,

    /**
     * Violence or harm.
     */
    VIOLENCE,

    /**
     * Sexual content.
     */
    SEXUAL,

    /**
     * Self-harm content.
     */
    SELF_HARM,

    /**
     * Illegal activities.
     */
    ILLEGAL,

    /**
     * Hallucinated or factually incorrect content.
     */
    HALLUCINATION,

    /**
     * Off-topic content.
     */
    OFF_TOPIC,

    /**
     * Invalid output format.
     */
    INVALID_FORMAT,

    /**
     * Content violating business rules.
     */
    BUSINESS_RULE,

    /**
     * Competitor mention or comparison.
     */
    COMPETITOR_MENTION,

    /**
     * Copyright or intellectual property violation.
     */
    COPYRIGHT,

    /**
     * Custom category defined by user.
     */
    CUSTOM
}
