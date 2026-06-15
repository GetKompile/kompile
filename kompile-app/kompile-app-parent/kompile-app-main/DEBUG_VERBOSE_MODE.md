# Kompile Debug & Verbose Mode Configuration Guide

This document provides a complete overview of all debug and verbose mode toggles available in the Kompile RAG application. Use this guide to enable comprehensive debugging for memory tracking, lifecycle analysis, operation tracing, and logging.

---

## Quick Reference

### Enable Full Debug Mode (One Command)
```bash
# Enable all ND4J environment toggles
curl -X POST http://localhost:8080/api/models/nd4j/environment/enable-all

# Or use bulk update with custom settings
curl -X POST http://localhost:8080/api/models/nd4j/environment/bulk-update \
  -H "Content-Type: application/json" \
  -d '{
    "lifecycleTracking": true,
    "trackViews": true,
    "trackDeletions": true,
    "snapshotFiles": true,
    "trackOperations": true,
    "stackDepth": 64,
    "reportInterval": 30,
    "maxDeletionHistory": 5000
  }'
```

### Disable All Debug Mode (Production)
```bash
curl -X POST http://localhost:8080/api/models/nd4j/environment/disable-all
```

---

## 1. ND4J Environment Toggles

### API Endpoints (ModelDebugController: `/api/models`)

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/nd4j/environment` | Get current state of all toggles |
| `POST` | `/nd4j/environment/enable-all` | Enable ALL boolean toggles (verbose/debug mode) |
| `POST` | `/nd4j/environment/disable-all` | Disable ALL boolean toggles (production mode) |
| `POST` | `/nd4j/environment/toggle/{name}?enabled={bool}` | Set individual toggle |
| `POST` | `/nd4j/environment/config/{name}?value={int}` | Set integer configuration |
| `POST` | `/nd4j/environment/bulk-update` | Bulk update via JSON body |

### Boolean Toggles

| Toggle | Description | Overhead | Default |
|--------|-------------|----------|---------|
| `lifecycleTracking` | Master switch for all lifecycle tracking | Low | `false` |
| `trackViews` | Track NDArrays that share memory buffers | Medium | `false` |
| `trackDeletions` | Capture stack traces on deallocation | **HIGH** | `false` |
| `snapshotFiles` | Save periodic memory snapshots to files | Medium | `false` |
| `trackOperations` | Track which operations cause memory leaks | Medium | `false` |

### Integer Configurations

| Config | Description | Range | Default |
|--------|-------------|-------|---------|
| `stackDepth` | Number of stack frames to capture | 1-256 | 16 |
| `reportInterval` | Seconds between automatic reports | 1-3600 | 120 |
| `maxDeletionHistory` | Maximum deletion records to keep | 1-100000 | 1000 |

### Example Commands

```bash
# Get current configuration
curl http://localhost:8080/api/models/nd4j/environment

# Enable individual toggle
curl -X POST "http://localhost:8080/api/models/nd4j/environment/toggle/trackOperations?enabled=true"

# Set stack depth for detailed traces
curl -X POST "http://localhost:8080/api/models/nd4j/environment/config/stackDepth?value=64"

# Set report interval to 30 seconds
curl -X POST "http://localhost:8080/api/models/nd4j/environment/config/reportInterval?value=30"
```

---

## 2. Lifecycle Tracking (LifecycleTrackingController: `/api/lifecycle`)

### API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/config` | Get current lifecycle tracking configuration |
| `POST` | `/enable` | Enable tracking with balanced preset + shutdown hook |
| `POST` | `/disable` | Disable all tracking completely |
| `POST` | `/tracking?enabled={bool}` | Toggle master lifecycle tracking |
| `POST` | `/preset?preset={name}` | Apply preset (minimal, balanced, detailed) |

### Presets

| Preset | trackViews | trackDeletions | snapshotFiles | trackOperations | stackDepth | reportInterval |
|--------|------------|----------------|---------------|-----------------|------------|----------------|
| `minimal` | false | false | true | false | 8 | 300 |
| `balanced` | false | false | true | true | 16 | 120 |
| `detailed` | true | **true** | true | true | 64 | 30 |

### Example Commands

```bash
# Apply balanced preset (recommended for debugging)
curl -X POST "http://localhost:8080/api/lifecycle/preset?preset=balanced"

# Apply detailed preset (maximum verbosity, HIGH overhead)
curl -X POST "http://localhost:8080/api/lifecycle/preset?preset=detailed"

# Enable with periodic reporting every 60 seconds
curl -X POST "http://localhost:8080/api/lifecycle/enable-periodic-reporting?intervalSeconds=60&enableSnapshots=true"
```

---

## 3. Native C++ Trackers (via NativeOps)

These trackers operate at the C++ level for detailed memory analysis.

### Trackers Available

| Tracker | Enable Endpoint | Disable Endpoint | What It Tracks |
|---------|-----------------|------------------|----------------|
| DataBuffer | `enableDataBufferTracking()` | `disableDataBufferTracking()` | Raw memory buffer allocations |
| NDArray | `enableNDArrayTracking()` | `disableNDArrayTracking()` | NDArray object lifecycle |
| TADCache | `enableTADCacheTracking()` | `disableTADCacheTracking()` | TAD (Tensor Along Dimension) cache |
| ShapeCache | `enableShapeCacheTracking()` | `disableShapeCacheTracking()` | Shape buffer cache |
| OpContext | `enableOpContextTracking()` | `disableOpContextTracking()` | Operation execution contexts |

### Enabling via REST API

The `/api/lifecycle/enable` endpoint automatically enables ALL native trackers:

```bash
# This enables all 5 native trackers + Java-side settings
curl -X POST http://localhost:8080/api/lifecycle/enable
```

---

## 4. Cache Statistics & Inspection

### Cache Stats Endpoints (`/api/lifecycle/cache`)

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/cache/stats` | Combined stats for shape + TAD caches |
| `GET` | `/cache/shape/stats` | Shape cache statistics |
| `GET` | `/cache/tad/stats` | TAD cache statistics |
| `GET` | `/cache/shape/browse?maxDepth=10&maxEntries=100` | Browse shape cache trie |
| `GET` | `/cache/tad/browse?maxDepth=10&maxEntries=100` | Browse TAD cache trie |
| `POST` | `/cache/shape/clear` | Clear shape cache |
| `POST` | `/cache/tad/clear` | Clear TAD cache |
| `POST` | `/cache/clear-all` | Clear both caches |

### Example Commands

```bash
# Get all cache statistics
curl http://localhost:8080/api/lifecycle/cache/stats

# Browse shape cache with deep inspection
curl "http://localhost:8080/api/lifecycle/cache/shape/browse?maxDepth=20&maxEntries=500"

# Clear all caches (useful for memory profiling)
curl -X POST http://localhost:8080/api/lifecycle/cache/clear-all
```

---

## 5. Leak Reports & Analysis

### Manual Report Generation

```bash
# Trigger immediate leak check (outputs to stderr)
curl -X POST http://localhost:8080/api/lifecycle/print-lifecycle-report

# Generate comprehensive leak analysis to ./leak_reports/
# (Automatically generated on shutdown when tracking is enabled)
```

### Automatic Shutdown Reports

When lifecycle tracking is enabled via `/api/lifecycle/enable`, a shutdown hook is registered that automatically generates:
- Comprehensive leak analysis
- Memory allocation reports
- Stack traces for leaked objects

Reports are saved to: `./leak_reports/`

### Cleanup Configuration

```bash
# Get cleanup configuration
curl http://localhost:8080/api/lifecycle/cleanup/config

# Update cleanup settings
curl -X POST "http://localhost:8080/api/lifecycle/cleanup/config?maxAgeDays=3&maxFiles=50"

# Trigger manual cleanup
curl -X POST http://localhost:8080/api/lifecycle/cleanup/trigger
```

---

## 6. Application Logging Configuration

### application.properties Settings

```properties
# Root logging level
logging.level.root=INFO

# Kompile application (DEBUG for verbose output)
logging.level.ai.kompile=DEBUG

# Spring AI
logging.level.org.springframework.ai=INFO

# Spring Web (TRACE for maximum HTTP debugging)
logging.level.org.springframework.web=TRACE
logging.level.org.springframework.web.servlet.DispatcherServlet=TRACE

# Bean creation debugging
logging.level.org.springframework.beans.factory=DEBUG

# Enable auto-configuration report
debug=true
```

### Runtime Logging Changes

To change logging levels at runtime without restart, use Spring Boot Actuator (if enabled):

```bash
# Set ai.kompile to TRACE level
curl -X POST http://localhost:8080/actuator/loggers/ai.kompile \
  -H "Content-Type: application/json" \
  -d '{"configuredLevel": "TRACE"}'
```

---

## 7. Model Debugging Endpoints

### Model Inspection (`/api/models`)

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/list` | List all loaded models (SameDiff, DL4J, etc.) |
| `GET` | `/embeddings/info` | Detailed embedding model information |
| `GET` | `/samediff-embeddings/list` | List SameDiff-based embedding models |
| `GET` | `/samediff-embeddings/{index}/summary` | JSON summary of SameDiff model |
| `GET` | `/samediff-embeddings/{index}/summary/text` | Plain text model summary |
| `POST` | `/embeddings/test?text=hello&modelIndex=0` | Test embedding inference |

### Example Commands

```bash
# List all models
curl http://localhost:8080/api/models/list

# Get detailed SameDiff model summary
curl "http://localhost:8080/api/models/samediff-embeddings/0/summary?includeVariables=true&includeOperations=true"

# Test embedding model
curl -X POST "http://localhost:8080/api/models/embeddings/test?text=Hello%20world&modelIndex=0"
```

---

## 8. Comprehensive Diagnostics

### Full System Diagnostics (`/api/diagnostics`)

```bash
# Get comprehensive system diagnostics
curl http://localhost:8080/api/diagnostics/comprehensive
```

This returns:
- JVM memory usage
- ND4J lifecycle configuration
- Cache statistics
- Active allocation tracking info
- System properties

---

## 9. Debug Mode Profiles

### Production Mode (Minimal Overhead)
```bash
curl -X POST http://localhost:8080/api/models/nd4j/environment/disable-all
```

### Development Mode (Balanced)
```bash
curl -X POST http://localhost:8080/api/models/nd4j/environment/bulk-update \
  -H "Content-Type: application/json" \
  -d '{
    "lifecycleTracking": true,
    "trackViews": false,
    "trackDeletions": false,
    "snapshotFiles": true,
    "trackOperations": true,
    "stackDepth": 16,
    "reportInterval": 120,
    "maxDeletionHistory": 1000
  }'
```

### Debug Mode (Full Verbosity)
```bash
curl -X POST http://localhost:8080/api/models/nd4j/environment/bulk-update \
  -H "Content-Type: application/json" \
  -d '{
    "lifecycleTracking": true,
    "trackViews": true,
    "trackDeletions": true,
    "snapshotFiles": true,
    "trackOperations": true,
    "stackDepth": 64,
    "reportInterval": 30,
    "maxDeletionHistory": 5000
  }'
```

### Memory Leak Investigation Mode
```bash
# Enable all tracking with maximum detail
curl -X POST "http://localhost:8080/api/lifecycle/preset?preset=detailed"

# Also enable all native trackers
curl -X POST http://localhost:8080/api/lifecycle/enable
```

---

## 10. Troubleshooting Checklist

### Memory Leak Investigation

1. **Enable full tracking:**
   ```bash
   curl -X POST http://localhost:8080/api/lifecycle/enable
   curl -X POST http://localhost:8080/api/models/nd4j/environment/enable-all
   ```

2. **Run your workload**

3. **Check cache growth:**
   ```bash
   curl http://localhost:8080/api/lifecycle/cache/stats
   ```

4. **Trigger leak report:**
   ```bash
   curl -X POST http://localhost:8080/api/lifecycle/print-lifecycle-report
   ```

5. **Check leak_reports directory:**
   ```bash
   ls -la ./leak_reports/
   ```

### Performance Profiling

1. **Disable high-overhead tracking:**
   ```bash
   curl -X POST "http://localhost:8080/api/models/nd4j/environment/toggle/trackDeletions?enabled=false"
   ```

2. **Enable operation tracking only:**
   ```bash
   curl -X POST "http://localhost:8080/api/models/nd4j/environment/toggle/trackOperations?enabled=true"
   ```

3. **Set shorter report interval:**
   ```bash
   curl -X POST "http://localhost:8080/api/models/nd4j/environment/config/reportInterval?value=30"
   ```

### Reset to Clean State

```bash
# Disable all tracking
curl -X POST http://localhost:8080/api/models/nd4j/environment/disable-all
curl -X POST http://localhost:8080/api/lifecycle/disable

# Clear all caches
curl -X POST http://localhost:8080/api/lifecycle/cache/clear-all

# Trigger garbage collection (if GC endpoint available)
# System.gc() - not directly exposed, but caches are cleared
```

---

## Summary Table: All Debug Endpoints

| Category | Base Path | Key Endpoints |
|----------|-----------|---------------|
| ND4J Environment | `/api/models/nd4j/environment` | `GET /`, `POST /enable-all`, `POST /disable-all`, `POST /toggle/{name}`, `POST /config/{name}`, `POST /bulk-update` |
| Lifecycle Tracking | `/api/lifecycle` | `GET /config`, `POST /enable`, `POST /disable`, `POST /preset`, `POST /tracking` |
| Cache Management | `/api/lifecycle/cache` | `GET /stats`, `GET /shape/stats`, `GET /tad/stats`, `POST /clear-all` |
| Model Inspection | `/api/models` | `GET /list`, `GET /embeddings/info`, `POST /embeddings/test` |
| Diagnostics | `/api/diagnostics` | `GET /comprehensive` |
| Leak Reports | `/api/lifecycle` | `POST /print-lifecycle-report`, `POST /cleanup/trigger` |

---

## Environment Variables & JVM Options

For maximum debug verbosity at JVM level:

```bash
# Enable ND4J debug mode via environment
export ND4J_DEBUG=true

# Increase stack trace depth
export ND4J_STACK_DEPTH=64

# JVM options for detailed GC logging
java -Xlog:gc*:file=gc.log:time,uptime:filecount=5,filesize=10M \
     -XX:+HeapDumpOnOutOfMemoryError \
     -XX:HeapDumpPath=./heap_dumps \
     -jar kompile-app-main.jar
```
