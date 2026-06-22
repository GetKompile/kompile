/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.crawl.graph;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PersonalEmailDomains} — gates {@code person_belongs_to_org} so it is
 * never asserted from a free/personal mailbox domain.
 */
class PersonalEmailDomainsTest {

    private final PersonalEmailDomains domains = new PersonalEmailDomains();

    @Test
    void recognisesCommonFreeProviders() {
        assertThat(domains.isPersonal("gmail.com")).isTrue();
        assertThat(domains.isPersonal("outlook.com")).isTrue();
        assertThat(domains.isPersonal("yahoo.com")).isTrue();
        assertThat(domains.isPersonal("proton.me")).isTrue();
        assertThat(domains.isPersonal("icloud.com")).isTrue();
    }

    @Test
    void matchingIsCaseInsensitive() {
        assertThat(domains.isPersonal("GMAIL.COM")).isTrue();
        assertThat(domains.isPersonal("  Outlook.Com ")).isTrue();
    }

    @Test
    void corporateDomainsAreNotPersonal() {
        assertThat(domains.isPersonal("acme.com")).isFalse();
        assertThat(domains.isPersonal("kompile.ai")).isFalse();
        assertThat(domains.isPersonal("berian.io")).isFalse();
    }

    @Test
    void nullOrBlankIsTreatedAsPersonal_soNoOrgIsFabricated() {
        assertThat(domains.isPersonal(null)).isTrue();
        assertThat(domains.isPersonal("")).isTrue();
        assertThat(domains.isPersonal("   ")).isTrue();
    }

    @Test
    void explicitExtraDomainsAreHonoured() {
        PersonalEmailDomains withExtra =
                new PersonalEmailDomains(Set.of("internal-personal.example", "Shared.example"));
        assertThat(withExtra.isPersonal("internal-personal.example")).isTrue();
        assertThat(withExtra.isPersonal("shared.example")).isTrue();   // normalised to lowercase
        assertThat(withExtra.isPersonal("acme.com")).isFalse();        // still corporate
        assertThat(withExtra.isPersonal("gmail.com")).isTrue();        // built-ins retained
    }
}
