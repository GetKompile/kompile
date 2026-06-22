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
package ai.kompile.crawl.graph;

import ai.kompile.knowledgegraph.confidence.KbConfig;
import ai.kompile.knowledgegraph.confidence.KbConfigManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Recognises free / personal email providers so that {@code person_belongs_to_org} structural
 * assertions are NOT drawn from a personal mailbox domain — {@code alice@gmail.com} does not
 * imply Alice belongs to an organisation "Gmail". Pillar 2 / Pillar 4 of the
 * confidence-evidence model.
 *
 * <p>The domain list is managed entirely via {@link KbConfig#personalEmailDomains} (loaded by
 * {@link KbConfigManager}). There are no hard-coded provider sets and no {@code @Value} bindings
 * here — edit the list through the kompile web UI or by updating {@code kb-confidence-config.json}
 * directly.</p>
 */
@Component
public class PersonalEmailDomains {

    @Autowired(required = false)
    KbConfigManager kbConfigManager;

    /** No-arg constructor for Spring. {@link #kbConfigManager} is field-injected after construction. */
    public PersonalEmailDomains() {
    }

    /** Explicit constructor for tests: delegates to the supplied manager. */
    public PersonalEmailDomains(KbConfigManager kbConfigManager) {
        this.kbConfigManager = kbConfigManager;
    }

    private KbConfig kbCfg() {
        return kbConfigManager != null ? kbConfigManager.current() : KbConfig.defaults();
    }

    /**
     * True if the domain is a known personal / free provider, in which case
     * {@code belongs_to_org} must NOT be asserted from it. A {@code null} or blank domain
     * also returns {@code true} (treat unknown / malformed input as non-assertable, never
     * fabricate an organisation).
     *
     * @param domain the bare domain (e.g. {@code "acme.com"}); case-insensitive
     */
    public boolean isPersonal(String domain) {
        if (domain == null || domain.isBlank()) {
            return true;
        }
        return kbCfg().getPersonalEmailDomains().contains(domain.trim().toLowerCase());
    }
}
