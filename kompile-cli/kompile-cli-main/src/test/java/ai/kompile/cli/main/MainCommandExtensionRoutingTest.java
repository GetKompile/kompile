package ai.kompile.cli.main;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MainCommandExtensionRoutingTest {
    @Test
    void extensionManagementCanRecoverWithoutLoadingSelectedProcessPackages() {
        assertTrue(MainCommand.isExtensionManagementRequest(new String[]{"extension", "remove", "broken"}));
        assertTrue(MainCommand.isExtensionManagementRequest(new String[]{"extension", "install", "package"}));
        assertFalse(MainCommand.isExtensionManagementRequest(new String[]{"chat", "extension"}));
        assertFalse(MainCommand.isExtensionManagementRequest(new String[]{"demo-command"}));
        assertFalse(MainCommand.isExtensionManagementRequest(new String[0]));
        assertFalse(MainCommand.isExtensionManagementRequest(null));
    }
}
