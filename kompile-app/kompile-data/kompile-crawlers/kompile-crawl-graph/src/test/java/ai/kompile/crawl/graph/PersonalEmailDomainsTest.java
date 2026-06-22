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

import ai.kompile.knowledgegraph.confidence.KbConfigManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

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
    void configDrivenDomainsReplaceDefaultList(@TempDir Path tempDir) throws Exception {
        Path cfgPath = tempDir.resolve("kb-confidence-config.json");
        Files.writeString(cfgPath,
                "{\"kbPersonalEmailDomains\":[\"acme-internal.example\",\"shared.example\"]}");
        PersonalEmailDomains p = new PersonalEmailDomains(new KbConfigManager(cfgPath));
        assertThat(p.isPersonal("acme-internal.example")).isTrue();
        assertThat(p.isPersonal("shared.example")).isTrue();
        // Config REPLACES the default list — gmail.com is no longer personal
        assertThat(p.isPersonal("gmail.com")).isFalse();
    }
}
