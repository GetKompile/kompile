/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.cli.common.mcp;

import ai.kompile.cli.common.registry.InstanceInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstanceDiscoveryTest {

    @Test
    void onlyApplicationInstancesParticipateInAppDiscovery() {
        assertTrue(InstanceDiscovery.isAppInstance(instance("app")));
        assertTrue(InstanceDiscovery.isAppInstance(instance("kompile-app-main")));
        assertFalse(InstanceDiscovery.isAppInstance(instance("kompile-graph-service")));
        assertFalse(InstanceDiscovery.isAppInstance(instance("staging")));
        assertFalse(InstanceDiscovery.isAppInstance(instance(null)));
        assertFalse(InstanceDiscovery.isAppInstance(null));
    }

    private InstanceInfo instance(String type) {
        return InstanceInfo.builder().name("test").type(type).port(1).pid(1).build();
    }
}
