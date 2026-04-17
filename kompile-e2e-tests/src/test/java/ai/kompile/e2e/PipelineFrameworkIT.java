package ai.kompile.e2e;

import ai.kompile.pipelines.framework.api.data.Data;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests pipeline framework Data/Configuration/StepConfig wiring.
 * These tests verify the core data container used across all pipeline steps.
 */
@Tag("pipeline")
@DisplayName("Pipeline Framework Integration Tests")
class PipelineFrameworkIT {

    @Test
    @DisplayName("Data.empty() creates empty container")
    void testDataEmpty() {
        Data data = Data.empty();
        assertNotNull(data);
        assertTrue(data.isEmpty());
        assertEquals(0, data.size());
        assertTrue(data.keySet().isEmpty());
    }

    @Test
    @DisplayName("Data stores and retrieves string values")
    void testDataStringValues() {
        Data data = Data.empty();
        data.put("query", "What is machine learning?");

        assertTrue(data.has("query"));
        assertEquals("What is machine learning?", data.getString("query"));
    }

    @Test
    @DisplayName("Data stores and retrieves numeric values")
    void testDataNumericValues() {
        Data data = Data.empty();
        data.put("count", 42L);
        data.put("score", 0.95);
        data.put("temperature", 0.7f);

        assertEquals(42L, data.getInt64("count"));
        assertEquals(0.95, data.getDouble("score"), 0.001);
    }

    @Test
    @DisplayName("Data stores and retrieves boolean values")
    void testDataBooleanValues() {
        Data data = Data.empty();
        data.put("enabled", true);
        data.put("verbose", false);

        assertTrue(data.getBoolean("enabled"));
        assertFalse(data.getBoolean("verbose"));
    }

    @Test
    @DisplayName("Data supports nested Data objects")
    void testNestedData() {
        Data inner = Data.empty();
        inner.put("field", "value");

        Data outer = Data.empty();
        outer.put("nested", inner);

        assertTrue(outer.has("nested"));
        Data retrieved = outer.getData("nested");
        assertNotNull(retrieved);
        assertEquals("value", retrieved.getString("field"));
    }

    @Test
    @DisplayName("Data.fromMap() creates from Java map")
    void testDataFromMap() {
        Map<String, Object> map = Map.of(
                "name", "test-pipeline",
                "version", "1.0"
        );

        Data data = Data.fromMap(map);
        assertNotNull(data);
        assertEquals("test-pipeline", data.getString("name"));
        assertEquals("1.0", data.getString("version"));
    }

    @Test
    @DisplayName("Data.singleton() creates single-entry container")
    void testDataSingleton() {
        Data data = Data.singleton("key", "value");
        assertEquals(1, data.size());
        assertEquals("value", data.getString("key"));
    }

    @Test
    @DisplayName("Data.dup() creates a copy")
    void testDataDup() {
        Data original = Data.empty();
        original.put("key", "original");

        Data copy = original.dup();
        copy.put("key", "modified");

        assertEquals("original", original.getString("key"));
        assertEquals("modified", copy.getString("key"));
    }

    @Test
    @DisplayName("Data remove() works correctly")
    void testDataRemove() {
        Data data = Data.empty();
        data.put("a", "1");
        data.put("b", "2");

        data.remove("a");

        assertFalse(data.has("a"));
        assertTrue(data.has("b"));
        assertEquals(1, data.size());
    }

    @Test
    @DisplayName("Data clear() empties container")
    void testDataClear() {
        Data data = Data.empty();
        data.put("a", "1");
        data.put("b", "2");

        data.clear();

        assertTrue(data.isEmpty());
        assertEquals(0, data.size());
    }

    @Test
    @DisplayName("Data keySet() returns all keys")
    void testDataKeySet() {
        Data data = Data.empty();
        data.put("alpha", "a");
        data.put("beta", "b");
        data.put("gamma", "c");

        Set<String> keys = data.keySet();
        assertEquals(3, keys.size());
        assertTrue(keys.contains("alpha"));
        assertTrue(keys.contains("beta"));
        assertTrue(keys.contains("gamma"));
    }

    @Test
    @DisplayName("Data.toMap() converts to Java map")
    void testDataToMap() {
        Data data = Data.empty();
        data.put("key", "value");

        Map<String, Object> map = data.toMap();
        assertNotNull(map);
        assertEquals("value", map.get("key"));
    }

    @Test
    @DisplayName("Data merge combines two Data objects")
    void testDataMerge() {
        Data data1 = Data.empty();
        data1.put("a", "1");

        Data data2 = Data.empty();
        data2.put("b", "2");

        data1.merge(data2);

        assertTrue(data1.has("a"));
        assertTrue(data1.has("b"));
        assertEquals(2, data1.size());
    }

    @Test
    @DisplayName("Data JSON round-trip preserves values")
    void testDataJsonRoundTrip() throws Exception {
        Data original = Data.empty();
        original.put("name", "test");
        original.put("count", 42L);
        original.put("enabled", true);

        String json = original.toJson();
        assertNotNull(json);

        Data restored = Data.fromJson(json);
        assertEquals("test", restored.getString("name"));
        assertTrue(restored.getBoolean("enabled"));
    }

    @Test
    @DisplayName("Data bytes serialization round-trip")
    void testDataBytesRoundTrip() throws Exception {
        Data original = Data.empty();
        original.put("field", "value");

        byte[] bytes = original.asBytes();
        assertNotNull(bytes);
        assertTrue(bytes.length > 0);

        Data restored = Data.fromBytes(bytes);
        assertEquals("value", restored.getString("field"));
    }

    @Test
    @DisplayName("Data getString with default returns default for missing key")
    void testGetStringDefault() {
        Data data = Data.empty();
        assertEquals("default", data.getString("missing", "default"));
    }
}
