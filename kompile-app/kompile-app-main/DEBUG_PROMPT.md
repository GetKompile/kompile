# Debug & Verbose Mode Quick Prompt

Use this prompt to quickly enable/disable debug modes in the Kompile application.

---

## ENABLE FULL DEBUG MODE

```bash
# Enable ALL ND4J environment toggles (verbose memory tracking)
curl -X POST http://localhost:8080/api/models/nd4j/environment/enable-all

# Apply detailed lifecycle preset with all native trackers
curl -X POST http://localhost:8080/api/lifecycle/enable
```

**What this enables:**
- `lifecycleTracking`: true (master switch)
- `trackViews`: true (buffer sharing)
- `trackDeletions`: true (deallocation traces - HIGH OVERHEAD)
- `snapshotFiles`: true (periodic snapshots)
- `trackOperations`: true (op leak tracking)
- `stackDepth`: 64 frames
- `reportInterval`: 30 seconds
- Native trackers: DataBuffer, NDArray, TADCache, ShapeCache, OpContext
- Shutdown hook for automatic leak reports

---

## DISABLE ALL DEBUG MODE (Production)

```bash
curl -X POST http://localhost:8080/api/models/nd4j/environment/disable-all
curl -X POST http://localhost:8080/api/lifecycle/disable
```

---

## CHECK CURRENT STATUS

```bash
# ND4J environment toggles
curl http://localhost:8080/api/models/nd4j/environment

# Lifecycle tracking config
curl http://localhost:8080/api/lifecycle/config

# Cache statistics
curl http://localhost:8080/api/lifecycle/cache/stats
```

---

## CUSTOM CONFIGURATION

```bash
# Bulk update with specific settings
curl -X POST http://localhost:8080/api/models/nd4j/environment/bulk-update \
  -H "Content-Type: application/json" \
  -d '{
    "lifecycleTracking": true,
    "trackViews": false,
    "trackDeletions": false,
    "snapshotFiles": true,
    "trackOperations": true,
    "stackDepth": 32,
    "reportInterval": 60,
    "maxDeletionHistory": 2000
  }'
```

---

## INDIVIDUAL TOGGLE COMMANDS

```bash
# Toggle specific setting
curl -X POST "http://localhost:8080/api/models/nd4j/environment/toggle/lifecycleTracking?enabled=true"
curl -X POST "http://localhost:8080/api/models/nd4j/environment/toggle/trackOperations?enabled=true"
curl -X POST "http://localhost:8080/api/models/nd4j/environment/toggle/trackDeletions?enabled=false"

# Set integer config
curl -X POST "http://localhost:8080/api/models/nd4j/environment/config/stackDepth?value=64"
curl -X POST "http://localhost:8080/api/models/nd4j/environment/config/reportInterval?value=30"
```

---

## PRESETS

```bash
# Minimal (low overhead)
curl -X POST "http://localhost:8080/api/lifecycle/preset?preset=minimal"

# Balanced (moderate overhead, good for development)
curl -X POST "http://localhost:8080/api/lifecycle/preset?preset=balanced"

# Detailed (maximum verbosity, HIGH overhead)
curl -X POST "http://localhost:8080/api/lifecycle/preset?preset=detailed"
```

---

## LEAK INVESTIGATION

```bash
# Trigger leak report (outputs to stderr)
curl -X POST http://localhost:8080/api/lifecycle/print-lifecycle-report

# Clear caches for baseline measurement
curl -X POST http://localhost:8080/api/lifecycle/cache/clear-all

# Check leak reports directory
ls -la ./leak_reports/
```

---

## AVAILABLE TOGGLES REFERENCE

| Toggle | Type | Range | Description |
|--------|------|-------|-------------|
| `lifecycleTracking` | bool | - | Master switch |
| `trackViews` | bool | - | Buffer sharing tracking |
| `trackDeletions` | bool | - | Deallocation stack traces (HIGH overhead) |
| `snapshotFiles` | bool | - | Periodic memory snapshots |
| `trackOperations` | bool | - | Operation leak tracking |
| `stackDepth` | int | 1-256 | Stack frames to capture |
| `reportInterval` | int | 1-3600 | Seconds between reports |
| `maxDeletionHistory` | int | 1-100000 | Max deletion records |

---

## ENDPOINTS SUMMARY

| Action | Endpoint |
|--------|----------|
| Get all toggles | `GET /api/models/nd4j/environment` |
| Enable all | `POST /api/models/nd4j/environment/enable-all` |
| Disable all | `POST /api/models/nd4j/environment/disable-all` |
| Set toggle | `POST /api/models/nd4j/environment/toggle/{name}?enabled={bool}` |
| Set config | `POST /api/models/nd4j/environment/config/{name}?value={int}` |
| Bulk update | `POST /api/models/nd4j/environment/bulk-update` |
| Apply preset | `POST /api/lifecycle/preset?preset={name}` |
| Full enable | `POST /api/lifecycle/enable` |
| Full disable | `POST /api/lifecycle/disable` |
| Cache stats | `GET /api/lifecycle/cache/stats` |
| Leak report | `POST /api/lifecycle/print-lifecycle-report` |
