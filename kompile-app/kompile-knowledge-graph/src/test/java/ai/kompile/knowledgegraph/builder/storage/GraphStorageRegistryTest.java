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

package ai.kompile.knowledgegraph.builder.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link GraphStorageRegistry} — strategy lookup, fallback chain,
 * availability checks, storage info, and edge cases.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class GraphStorageRegistryTest {

    private GraphStorageStrategy jpaStrategy;
    private GraphStorageStrategy neo4jStrategy;
    private GraphStorageRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        jpaStrategy = mock(GraphStorageStrategy.class);
        when(jpaStrategy.getStorageType()).thenReturn("jpa");
        when(jpaStrategy.isAvailable()).thenReturn(true);

        neo4jStrategy = mock(GraphStorageStrategy.class);
        when(neo4jStrategy.getStorageType()).thenReturn("neo4j");
        when(neo4jStrategy.isAvailable()).thenReturn(true);

        registry = new GraphStorageRegistry(List.of(jpaStrategy, neo4jStrategy));
        setDefaultStorageType("jpa");
        registry.init();
    }

    private void setDefaultStorageType(String type) throws Exception {
        Field field = GraphStorageRegistry.class.getDeclaredField("defaultStorageType");
        field.setAccessible(true);
        field.set(registry, type);
    }

    // ─── getStrategy ───────────────────────────────────────────────────

    @Test
    void getStrategy_byExactType() {
        Optional<GraphStorageStrategy> result = registry.getStrategy("jpa");
        assertTrue(result.isPresent());
        assertEquals("jpa", result.get().getStorageType());
    }

    @Test
    void getStrategy_caseInsensitive() {
        Optional<GraphStorageStrategy> result = registry.getStrategy("JPA");
        assertTrue(result.isPresent());
    }

    @Test
    void getStrategy_nullType_returnsDefault() {
        Optional<GraphStorageStrategy> result = registry.getStrategy(null);
        assertTrue(result.isPresent());
        assertEquals("jpa", result.get().getStorageType());
    }

    @Test
    void getStrategy_emptyType_returnsDefault() {
        Optional<GraphStorageStrategy> result = registry.getStrategy("");
        assertTrue(result.isPresent());
        assertEquals("jpa", result.get().getStorageType());
    }

    @Test
    void getStrategy_unknownType_returnsEmpty() {
        Optional<GraphStorageStrategy> result = registry.getStrategy("redis");
        assertFalse(result.isPresent());
    }

    // ─── getStrategyWithFallback ───────────────────────────────────────

    @Test
    void getStrategyWithFallback_requestedAvailable() {
        GraphStorageStrategy result = registry.getStrategyWithFallback("neo4j");
        assertEquals("neo4j", result.getStorageType());
    }

    @Test
    void getStrategyWithFallback_requestedUnavailable_fallsToDefault() {
        when(neo4jStrategy.isAvailable()).thenReturn(false);

        GraphStorageStrategy result = registry.getStrategyWithFallback("neo4j");
        assertEquals("jpa", result.getStorageType());
    }

    @Test
    void getStrategyWithFallback_unknownType_fallsToDefault() {
        GraphStorageStrategy result = registry.getStrategyWithFallback("redis");
        assertEquals("jpa", result.getStorageType());
    }

    @Test
    void getStrategyWithFallback_null_returnsDefault() {
        GraphStorageStrategy result = registry.getStrategyWithFallback(null);
        assertEquals("jpa", result.getStorageType());
    }

    @Test
    void getStrategyWithFallback_defaultAlsoUnavailable_findsAnyAvailable() {
        when(jpaStrategy.isAvailable()).thenReturn(false);

        GraphStorageStrategy result = registry.getStrategyWithFallback(null);
        assertEquals("neo4j", result.getStorageType());
    }

    @Test
    void getStrategyWithFallback_noneAvailable_throws() {
        when(jpaStrategy.isAvailable()).thenReturn(false);
        when(neo4jStrategy.isAvailable()).thenReturn(false);

        assertThrows(IllegalStateException.class,
                () -> registry.getStrategyWithFallback(null));
    }

    // ─── getAvailableStorageTypes ──────────────────────────────────────

    @Test
    void getAvailableStorageTypes_onlyAvailable() {
        when(neo4jStrategy.isAvailable()).thenReturn(false);

        List<String> types = registry.getAvailableStorageTypes();

        assertEquals(1, types.size());
        assertTrue(types.contains("jpa"));
        assertFalse(types.contains("neo4j"));
    }

    @Test
    void getAvailableStorageTypes_all() {
        List<String> types = registry.getAvailableStorageTypes();
        assertEquals(2, types.size());
    }

    // ─── getAllStorageTypes ─────────────────────────────────────────────

    @Test
    void getAllStorageTypes_includesUnavailable() {
        when(neo4jStrategy.isAvailable()).thenReturn(false);

        List<String> all = registry.getAllStorageTypes();

        assertEquals(2, all.size());
    }

    // ─── isStorageAvailable ────────────────────────────────────────────

    @Test
    void isStorageAvailable_true() {
        assertTrue(registry.isStorageAvailable("jpa"));
    }

    @Test
    void isStorageAvailable_unavailable() {
        when(neo4jStrategy.isAvailable()).thenReturn(false);
        assertFalse(registry.isStorageAvailable("neo4j"));
    }

    @Test
    void isStorageAvailable_unknown_returnsFalse() {
        assertFalse(registry.isStorageAvailable("redis"));
    }

    // ─── getDefaultStorageType ─────────────────────────────────────────

    @Test
    void getDefaultStorageType_returnsConfiguredDefault() {
        assertEquals("jpa", registry.getDefaultStorageType());
    }

    // ─── getStorageInfo ────────────────────────────────────────────────

    @Test
    void getStorageInfo_includesAllStrategies() {
        List<GraphStorageRegistry.StorageInfo> info = registry.getStorageInfo();

        assertEquals(2, info.size());

        GraphStorageRegistry.StorageInfo jpaInfo = info.stream()
                .filter(i -> "jpa".equals(i.storageType())).findFirst().orElseThrow();
        assertTrue(jpaInfo.available());
        assertTrue(jpaInfo.isDefault());

        GraphStorageRegistry.StorageInfo neo4jInfo = info.stream()
                .filter(i -> "neo4j".equals(i.storageType())).findFirst().orElseThrow();
        assertTrue(neo4jInfo.available());
        assertFalse(neo4jInfo.isDefault());
    }

    // ─── Empty registry ────────────────────────────────────────────────

    @Test
    void emptyRegistry_getAvailableStorageTypes_empty() throws Exception {
        GraphStorageRegistry empty = new GraphStorageRegistry(List.of());
        setField(empty, "defaultStorageType", "jpa");
        empty.init();

        assertTrue(empty.getAvailableStorageTypes().isEmpty());
    }

    @Test
    void emptyRegistry_getStrategyWithFallback_throws() throws Exception {
        GraphStorageRegistry empty = new GraphStorageRegistry(List.of());
        setField(empty, "defaultStorageType", "jpa");
        empty.init();

        assertThrows(IllegalStateException.class,
                () -> empty.getStrategyWithFallback(null));
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
