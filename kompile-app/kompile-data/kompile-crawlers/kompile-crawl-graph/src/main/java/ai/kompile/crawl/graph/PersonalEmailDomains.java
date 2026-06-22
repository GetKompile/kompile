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

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * Recognises free / personal email providers so that {@code person_belongs_to_org} structural
 * assertions are NOT drawn from a personal mailbox domain — {@code alice@gmail.com} does not
 * imply Alice belongs to an organisation "Gmail". Pillar 2 / Pillar 4 of the
 * confidence-evidence model.
 *
 * <p>Built-in providers are augmentable via the comma-separated property
 * {@code kompile.kb.personal-email-domains}. Matching is case-insensitive on the bare domain.</p>
 */
@Component
public class PersonalEmailDomains {

    private static final Set<String> BUILT_IN = Set.of(
            "gmail.com", "googlemail.com", "outlook.com", "hotmail.com", "live.com",
            "msn.com", "yahoo.com", "ymail.com", "rocketmail.com", "icloud.com",
            "me.com", "mac.com", "proton.me", "protonmail.com", "pm.me", "aol.com",
            "gmx.com", "gmx.net", "zoho.com", "mail.com", "yandex.com", "yandex.ru",
            "fastmail.com", "hey.com", "tutanota.com", "tuta.io", "qq.com", "163.com");

    private final Set<String> domains;

    /** Comma-separated extra personal domains; injected by Spring after construction. */
    @Value("${kompile.kb.personal-email-domains:}")
    private String extra;

    /** Default constructor — built-in providers only (Spring also field-injects {@link #extra}). */
    public PersonalEmailDomains() {
        this.domains = new HashSet<>(BUILT_IN);
    }

    /** Explicit constructor for tests: built-in providers plus the supplied extras. */
    public PersonalEmailDomains(Set<String> extraDomains) {
        this.domains = new HashSet<>(BUILT_IN);
        if (extraDomains != null) {
            for (String d : extraDomains) {
                if (d != null && !d.isBlank()) {
                    domains.add(d.trim().toLowerCase());
                }
            }
        }
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
        String d = domain.trim().toLowerCase();
        if (domains.contains(d)) {
            return true;
        }
        if (extra != null && !extra.isBlank()) {
            for (String e : extra.split(",")) {
                if (d.equals(e.trim().toLowerCase())) {
                    return true;
                }
            }
        }
        return false;
    }
}
