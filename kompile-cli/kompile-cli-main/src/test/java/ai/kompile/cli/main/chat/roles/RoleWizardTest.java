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
package ai.kompile.cli.main.chat.roles;

import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import org.jline.reader.LineReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The role wizard's assign-role action ({@code /roles} menu choice 5) must switch the live
 * chat's running agent through {@link RoleWizard}'s activation callback exactly like
 * {@code /role} does, instead of only updating {@link RoleManager} bookkeeping while
 * claiming the agent changed. When no chat is attached (no callback wired — every
 * non-interactive-chat launch of the wizard), it must say so honestly rather than claim a
 * switch that never reaches any agent loop.
 */
@TemporaryUserHome
@ResourceLock(Resources.SYSTEM_OUT)
class RoleWizardTest {
    private static final String ROLE_PROMPT = "ROLE_MARKER_9f3a: answer as the marker role.";

    @TempDir Path project;

    @Test
    void assigningARoleNotifiesTheWiredCallbackAndSaysNextTurn() throws Exception {
        RoleManager roles = new RoleManager(project);
        roles.createRole("marker-role", "Marker", "Carries a marker prompt", "testing", ROLE_PROMPT);

        List<RoleConfig> activated = new ArrayList<>();
        RoleWizard wizard = new RoleWizard(roles, activated::add);

        LineReader reader = mock(LineReader.class);
        when(reader.readLine(anyString())).thenReturn("marker-role");

        String output = printed(() -> invokeAssignRole(wizard, reader));

        assertEquals(1, activated.size(), output);
        assertEquals("marker-role", activated.get(0).getName());
        assertEquals("marker-role", roles.getActiveRoleName());
        assertTrue(output.contains("Role activated: marker-role"), output);
        assertTrue(output.contains("starting next turn"), output);
    }

    @Test
    void assigningARoleWithNoCallbackWiredSaysNoChatIsAttached() throws Exception {
        RoleManager roles = new RoleManager(project);
        roles.createRole("marker-role", "Marker", "Carries a marker prompt", "testing", ROLE_PROMPT);

        // No activation callback — this is every non-interactive-chat launch of the
        // wizard (there is no such launch site today, but the constructor overload
        // without a callback must still degrade honestly rather than throw).
        RoleWizard wizard = new RoleWizard(roles);

        LineReader reader = mock(LineReader.class);
        when(reader.readLine(anyString())).thenReturn("marker-role");

        String output = printed(() -> invokeAssignRole(wizard, reader));

        assertEquals("marker-role", roles.getActiveRoleName(),
                "the wizard still activates the role in RoleManager even without a chat attached");
        assertTrue(output.contains("No active chat session is attached"), output);
        assertFalse(output.contains("starting next turn"), output);
    }

    /** Drives the wizard's private assign-role action the same way menu choice "5" does. */
    private static void invokeAssignRole(RoleWizard wizard, LineReader reader) {
        try {
            Method assignRole = RoleWizard.class.getDeclaredMethod("assignRole", LineReader.class);
            assignRole.setAccessible(true);
            assignRole.invoke(wizard, reader);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** What the wizard printed while {@code action} ran. */
    private static String printed(Runnable action) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
