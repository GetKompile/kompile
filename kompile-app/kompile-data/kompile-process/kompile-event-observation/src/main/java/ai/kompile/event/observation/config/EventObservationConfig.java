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
package ai.kompile.event.observation.config;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON-file-backed configuration for event observation and empirical priors. Loaded from
 * {@code ~/.kompile/config/event-observation-config.json} (kompile uses JSON config files, never
 * Spring properties). Unknown keys are preserved via {@link JsonAnySetter} so a shared config file
 * is never clobbered.
 *
 * <p>Public fields are nullable for Jackson; the typed accessors apply sane defaults.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class EventObservationConfig {

    /** Master switch — when false, the listeners, scanner and empirical-prior bridge are inert. */
    public Boolean enabled;

    /** PRESENCE | RELATIVE_FREQUENCY | DECAYED_RATE — how the Beta-Binomial denominator is computed. */
    public String opportunityModel;

    /** Exponential-decay half-life in days; older evidence relaxes back toward the prior. */
    public Double halfLifeDays;

    /** Prior pseudo-counts (the value alpha/beta revert toward on decay). */
    public Double priorAlpha;
    public Double priorBeta;

    /** Blend strength k: empirical weight = evidence / (evidence + k) when mixing with structural priors. */
    public Double priorBlendK;

    /** Minimum evidence (alpha+beta beyond the prior) before an empirical prior is exposed at all. */
    public Double minEvidenceForPrior;

    /** Which storage backends to write priors to: any of "jpa", "vector". */
    public List<String> storageBackends;

    /** Apply time-decay to all priors at the start of each crawl ingest. */
    public Boolean decayOnEachCrawl;

    /** Per-event-type switches. */
    public Boolean entityEventsEnabled;
    public Boolean connectionEventsEnabled;
    public Boolean processStepEventsEnabled;

    /**
     * Apply per-mutation online updates from NodeMutationEvent/EdgeMutationEvent. Additive to the
     * coarse crawl scan; off by default so the two paths don't double-count during a full build.
     */
    public Boolean fineGrainedMutationsEnabled;

    private final Map<String, Object> additionalProperties = new LinkedHashMap<>();

    @JsonAnyGetter
    public Map<String, Object> getAdditionalProperties() {
        return additionalProperties;
    }

    @JsonAnySetter
    public void setAdditionalProperty(String key, Object value) {
        additionalProperties.put(key, value);
    }

    public static EventObservationConfig defaults() {
        EventObservationConfig c = new EventObservationConfig();
        c.enabled = true;
        c.opportunityModel = "PRESENCE";
        c.halfLifeDays = 30.0;
        c.priorAlpha = 1.0;
        c.priorBeta = 1.0;
        c.priorBlendK = 5.0;
        c.minEvidenceForPrior = 3.0;
        c.storageBackends = new ArrayList<>(List.of("jpa", "vector"));
        c.decayOnEachCrawl = true;
        c.entityEventsEnabled = true;
        c.connectionEventsEnabled = true;
        c.processStepEventsEnabled = true;
        c.fineGrainedMutationsEnabled = false;
        return c;
    }

    public EventObservationConfig copy() {
        EventObservationConfig c = new EventObservationConfig();
        c.enabled = enabled;
        c.opportunityModel = opportunityModel;
        c.halfLifeDays = halfLifeDays;
        c.priorAlpha = priorAlpha;
        c.priorBeta = priorBeta;
        c.priorBlendK = priorBlendK;
        c.minEvidenceForPrior = minEvidenceForPrior;
        c.storageBackends = storageBackends == null ? null : new ArrayList<>(storageBackends);
        c.decayOnEachCrawl = decayOnEachCrawl;
        c.entityEventsEnabled = entityEventsEnabled;
        c.connectionEventsEnabled = connectionEventsEnabled;
        c.processStepEventsEnabled = processStepEventsEnabled;
        c.fineGrainedMutationsEnabled = fineGrainedMutationsEnabled;
        c.additionalProperties.putAll(additionalProperties);
        return c;
    }

    // ── Typed accessors with defaults ───────────────────────────────────────────

    public boolean enabled() {
        return enabled == null || enabled;
    }

    public OpportunityModel opportunityModel() {
        return OpportunityModel.fromString(opportunityModel);
    }

    public double halfLifeDays() {
        return halfLifeDays == null || halfLifeDays <= 0 ? 30.0 : halfLifeDays;
    }

    public double priorAlpha() {
        return priorAlpha == null || priorAlpha <= 0 ? 1.0 : priorAlpha;
    }

    public double priorBeta() {
        return priorBeta == null || priorBeta <= 0 ? 1.0 : priorBeta;
    }

    public double priorBlendK() {
        return priorBlendK == null || priorBlendK < 0 ? 5.0 : priorBlendK;
    }

    public double minEvidenceForPrior() {
        return minEvidenceForPrior == null || minEvidenceForPrior < 0 ? 3.0 : minEvidenceForPrior;
    }

    public List<String> storageBackends() {
        return storageBackends == null || storageBackends.isEmpty() ? List.of("jpa", "vector") : storageBackends;
    }

    public boolean decayOnEachCrawl() {
        return decayOnEachCrawl == null || decayOnEachCrawl;
    }

    public boolean entityEventsEnabled() {
        return entityEventsEnabled == null || entityEventsEnabled;
    }

    public boolean connectionEventsEnabled() {
        return connectionEventsEnabled == null || connectionEventsEnabled;
    }

    public boolean processStepEventsEnabled() {
        return processStepEventsEnabled == null || processStepEventsEnabled;
    }

    public boolean fineGrainedMutationsEnabled() {
        return fineGrainedMutationsEnabled != null && fineGrainedMutationsEnabled;
    }
}
