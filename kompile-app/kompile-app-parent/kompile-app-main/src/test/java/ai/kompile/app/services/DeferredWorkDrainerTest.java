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

package ai.kompile.app.services;

import ai.kompile.core.crawl.graph.DeferredWorkSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DeferredWorkDrainer#drainAll()} — the governor-driven loop's contract:
 * only sources reporting capacity are drained, drained counts sum, and a throwing source doesn't
 * abort the others.
 */
class DeferredWorkDrainerTest {

    private static DeferredWorkSource source(String name, boolean hasCapacity, int drained) {
        DeferredWorkSource s = mock(DeferredWorkSource.class);
        when(s.name()).thenReturn(name);
        when(s.hasCapacity()).thenReturn(hasCapacity);
        when(s.drainAvailable()).thenReturn(drained);
        return s;
    }

    private static DeferredWorkDrainer drainerWith(DeferredWorkSource... sources) {
        DeferredWorkDrainer drainer = new DeferredWorkDrainer();
        drainer.sources = List.of(sources);
        return drainer;
    }

    @Test
    void drainsOnlySourcesWithCapacityAndSumsCounts() {
        DeferredWorkSource ready = source("embedding", true, 3);
        DeferredWorkSource constrained = source("kge-training", false, 5);
        DeferredWorkDrainer drainer = drainerWith(ready, constrained);

        assertEquals(3, drainer.drainAll());
        verify(ready).drainAvailable();
        verify(constrained, never()).drainAvailable(); // gated out by hasCapacity()==false
    }

    @Test
    void oneFailingSourceDoesNotAbortTheRest() {
        DeferredWorkSource boom = mock(DeferredWorkSource.class);
        when(boom.name()).thenReturn("boom");
        when(boom.hasCapacity()).thenReturn(true);
        when(boom.drainAvailable()).thenThrow(new RuntimeException("drain failed"));
        DeferredWorkSource ok = source("embedding", true, 2);

        DeferredWorkDrainer drainer = drainerWith(boom, ok);

        assertEquals(2, drainer.drainAll()); // boom swallowed, ok still drained
        verify(ok).drainAvailable();
    }

    @Test
    void noSourcesDrainsNothing() {
        assertEquals(0, drainerWith().drainAll());
    }
}
