package ai.kompile.app.services.agent;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OwnedProcessTreeTest {
    private static final class Fixture {
        final Process child = mock(Process.class);
        final ProcessHandle handle = mock(ProcessHandle.class);
        final AtomicBoolean alive = new AtomicBoolean(true);
        final AtomicBoolean exitDuringInfo = new AtomicBoolean();
        final AtomicReference<Instant> identity = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        final KompileCliSessionWorker.OwnedProcessTree tree;
        Fixture() {
            when(child.toHandle()).thenReturn(handle);
            when(child.isAlive()).thenAnswer(call -> alive.get());
            when(handle.pid()).thenReturn(Long.MAX_VALUE);
            when(handle.isAlive()).thenAnswer(call -> alive.get());
            when(handle.info()).thenAnswer(call -> {
                if (exitDuringInfo.getAndSet(false)) alive.set(false);
                ProcessHandle.Info info = mock(ProcessHandle.Info.class);
                when(info.startInstant()).thenReturn(Optional.ofNullable(identity.get()));
                return info;
            });
            when(handle.descendants()).thenAnswer(call -> Stream.empty());
            tree = new KompileCliSessionWorker.OwnedProcessTree(child);
            assertFalse((boolean) tree.evidence().get("unknown"));
        }
    }

    @Test void knownExitBetweenLivenessAndIdentityDuringCloseDoesNotInventUncertainty() {
        Fixture fixture = new Fixture();
        fixture.tree.prepareShutdown();
        fixture.identity.set(null); fixture.exitDuringInfo.set(true);
        fixture.tree.capture();
        assertTrue(fixture.tree.allExited());
        assertFalse((boolean) fixture.tree.evidence().get("unknown"));
        verify(fixture.handle, never()).destroy();
        verify(fixture.handle, never()).destroyForcibly();
    }

    @Test void sameExitBeforeCloseRemainsUncertain() {
        Fixture fixture = new Fixture();
        fixture.identity.set(null); fixture.exitDuringInfo.set(true);
        fixture.tree.capture(); fixture.tree.prepareShutdown();
        assertFalse(fixture.tree.allExited());
        assertTrue((boolean) fixture.tree.evidence().get("unknown"));
    }

    @Test void missingLiveIdentityDuringCloseRemainsUncertain() {
        Fixture fixture = new Fixture();
        fixture.tree.prepareShutdown(); fixture.identity.set(null);
        fixture.tree.capture(); fixture.alive.set(false);
        assertFalse(fixture.tree.allExited());
        assertTrue((boolean) fixture.tree.evidence().get("unknown"));
    }

    @Test void changedIdentityIsNeverAcceptedAsCooperativeExit() {
        Fixture fixture = new Fixture();
        fixture.tree.prepareShutdown(); fixture.identity.set(Instant.parse("2026-01-02T00:00:00Z"));
        fixture.tree.capture(); fixture.alive.set(false);
        assertFalse(fixture.tree.allExited());
        assertTrue((boolean) fixture.tree.evidence().get("unknown"));
    }
}
