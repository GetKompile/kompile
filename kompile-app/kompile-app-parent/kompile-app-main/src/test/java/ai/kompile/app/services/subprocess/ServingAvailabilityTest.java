/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.app.services.subprocess;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ServingSubprocessLauncher#isModelLoaded()} TTL cache behavior
 * and {@link ServingSubprocessBackend#isAvailable()} model-awareness.
 *
 * <p>Uses reflection to set up the internal state (running flag, cached values) without
 * actually launching a subprocess or making HTTP calls.</p>
 */
class ServingAvailabilityTest {

    // ── isModelLoaded: not-running → false immediately ────────────────────────

    @Test
    void isModelLoaded_returnsFalse_whenNotRunning() {
        ServingSubprocessLauncher launcher = new ServingSubprocessLauncher();
        // running is false by default; isRunning() returns false
        assertFalse(launcher.isModelLoaded(), "Should be false when subprocess is not running");
    }

    // ── isModelLoaded: TTL cache serves stale result until expired ─────────────

    @Test
    void isModelLoaded_cacheHit_returnsStaleResult() throws Exception {
        ServingSubprocessLauncher launcher = new ServingSubprocessLauncher();
        // Force running = true so the TTL check is reached
        java.util.concurrent.atomic.AtomicBoolean running =
                (java.util.concurrent.atomic.AtomicBoolean) ReflectionTestUtils.getField(launcher, "running");
        assertNotNull(running);
        running.set(true);
        // Simulate a live process handle
        ReflectionTestUtils.setField(launcher, "process",
                createFakeLiveProcess());

        // Prime the cache: cachedModelLoaded=true, timestamp=now
        ReflectionTestUtils.setField(launcher, "cachedModelLoaded", true);
        ReflectionTestUtils.setField(launcher, "modelLoadedCacheTimeNs", System.nanoTime());

        // Without any HTTP call the cache should serve true
        assertTrue(launcher.isModelLoaded(), "Cache hit should return cached=true");
    }

    // ── isModelLoaded: expired TTL triggers a re-poll ──────────────────────────

    @Test
    void isModelLoaded_expiredCache_repollsAndReturnsFalse() throws Exception {
        ServingSubprocessLauncher launcher = new ServingSubprocessLauncher();
        java.util.concurrent.atomic.AtomicBoolean running =
                (java.util.concurrent.atomic.AtomicBoolean) ReflectionTestUtils.getField(launcher, "running");
        assertNotNull(running);
        running.set(true);
        // Simulate a live process handle
        ReflectionTestUtils.setField(launcher, "process", createFakeLiveProcess());

        // Expire the cache: set cacheTimeNs to far in the past
        ReflectionTestUtils.setField(launcher, "cachedModelLoaded", true);
        ReflectionTestUtils.setField(launcher, "modelLoadedCacheTimeNs", 0L); // effectively expired

        // No real HTTP client — the getJson() will fail with connection refused.
        // The expected behavior is that the exception is caught, cached=false is stored, false returned.
        boolean result = launcher.isModelLoaded();
        assertFalse(result, "After expired TTL with no subprocess running, should return false");
        // Cache should now hold false
        Boolean cached = (Boolean) ReflectionTestUtils.getField(launcher, "cachedModelLoaded");
        assertFalse(cached, "Cached value should be updated to false after failed re-poll");
    }

    // ── invalidateModelLoadedCache resets timestamp ───────────────────────────

    @Test
    void invalidateModelLoadedCache_resetsTimestamp() {
        ServingSubprocessLauncher launcher = new ServingSubprocessLauncher();
        // Prime a non-zero timestamp
        ReflectionTestUtils.setField(launcher, "modelLoadedCacheTimeNs", System.nanoTime());
        launcher.invalidateModelLoadedCache();
        long ts = (long) ReflectionTestUtils.getField(launcher, "modelLoadedCacheTimeNs");
        assertEquals(0L, ts, "invalidateModelLoadedCache should reset cacheTimeNs to 0");
    }

    // ── ServingSubprocessBackend.isAvailable requires model loaded ─────────────

    @Test
    void backendIsAvailable_falseWhenLauncherNull() {
        ServingSubprocessBackend backend = new ServingSubprocessBackend();
        // launcher not set → should return false
        assertFalse(backend.isAvailable(), "Should be false when launcher is null");
    }

    @Test
    void backendIsAvailable_falseWhenRunningButNoModelLoaded() throws Exception {
        ServingSubprocessLauncher launcher = new ServingSubprocessLauncher();
        java.util.concurrent.atomic.AtomicBoolean running =
                (java.util.concurrent.atomic.AtomicBoolean) ReflectionTestUtils.getField(launcher, "running");
        assertNotNull(running);
        running.set(true);
        ReflectionTestUtils.setField(launcher, "process", createFakeLiveProcess());
        // Expired cache + no subprocess → re-poll will fail → cached=false
        ReflectionTestUtils.setField(launcher, "modelLoadedCacheTimeNs", 0L);
        ReflectionTestUtils.setField(launcher, "cachedModelLoaded", false);

        ServingSubprocessBackend backend = new ServingSubprocessBackend();
        ReflectionTestUtils.setField(backend, "launcher", launcher);

        assertFalse(backend.isAvailable(),
                "isAvailable should be false when subprocess running but no model loaded");
    }

    @Test
    void backendIsAvailable_trueWhenRunningAndModelLoaded() throws Exception {
        ServingSubprocessLauncher launcher = new ServingSubprocessLauncher();
        java.util.concurrent.atomic.AtomicBoolean running =
                (java.util.concurrent.atomic.AtomicBoolean) ReflectionTestUtils.getField(launcher, "running");
        assertNotNull(running);
        running.set(true);
        ReflectionTestUtils.setField(launcher, "process", createFakeLiveProcess());
        // Set a fresh cache hit with loaded=true
        ReflectionTestUtils.setField(launcher, "cachedModelLoaded", true);
        ReflectionTestUtils.setField(launcher, "modelLoadedCacheTimeNs", System.nanoTime());

        ServingSubprocessBackend backend = new ServingSubprocessBackend();
        ReflectionTestUtils.setField(backend, "launcher", launcher);

        assertTrue(backend.isAvailable(),
                "isAvailable should be true when running + model loaded (cache hit)");
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    /**
     * Creates a Process stub whose {@code isAlive()} returns true, allowing the
     * {@code isRunning()} check to pass without a real subprocess.
     */
    private Process createFakeLiveProcess() throws Exception {
        // Use a ProcessBuilder that starts a trivially short-lived process as a stand-in;
        // but for tests we just need isAlive()=true, so mock via subclass.
        return new Process() {
            @Override public java.io.OutputStream getOutputStream() { return null; }
            @Override public java.io.InputStream getInputStream() { return java.io.InputStream.nullInputStream(); }
            @Override public java.io.InputStream getErrorStream() { return java.io.InputStream.nullInputStream(); }
            @Override public int waitFor() { return 0; }
            @Override public int exitValue() { return 0; }
            @Override public void destroy() { }
            @Override public boolean isAlive() { return true; }
        };
    }
}
