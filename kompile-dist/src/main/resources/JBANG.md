# Running Kompile via JBang (JAR fallback)

The native binaries in `bin/` are the primary way to run Kompile. This `jbang-catalog.json`
and selected product JARs in `lib/` provide a JVM path for the CLI and server personas.
Local MCP model workers are native-only and side-load their backend libraries from `lib/`.

## Install JBang

```bash
curl -Ls https://sh.jbang.dev | bash -s - app setup
```

Or via SDKMan: `sdk install jbang`

## Run from this unpacked distribution

```bash
# From the extracted dist directory:
jbang --catalog ./jbang-catalog.json kompile-server
jbang --catalog ./jbang-catalog.json kompile
```

## Run directly from GitHub Releases (no download required)

```bash
jbang kompile-server@getkompile/kompile
jbang kompile@getkompile/kompile
```

## Common configuration

```bash
# Set heap size (default: JVM default, typically 1/4 of RAM)
export JBANG_JAVA_OPTIONS="-Xmx20g"

# Set data directory (where Kompile stores its index, models, config)
jbang --catalog ./jbang-catalog.json kompile-server --kompile.data.dir=/path/to/data

# Set server port (default: 8080)
jbang --catalog ./jbang-catalog.json kompile-server --server.port=9090

# Force CPU-only (disable CUDA detection, useful on machines with CUDA libs but no GPU)
CUDA_VISIBLE_DEVICES=-1 jbang --catalog ./jbang-catalog.json kompile-server
```

## Subprocess modes

The server JAR boots any subprocess type via `--subprocess=<type>`:

```bash
jbang --catalog ./jbang-catalog.json kompile-ingest
jbang --catalog ./jbang-catalog.json kompile-embedding
jbang --catalog ./jbang-catalog.json kompile-graph
jbang --catalog ./jbang-catalog.json kompile-learning
jbang --catalog ./jbang-catalog.json kompile-serving
jbang --catalog ./jbang-catalog.json kompile-vector
jbang --catalog ./jbang-catalog.json kompile-model-init
```

## Note on native binaries vs JARs

Native binaries in `bin/` start faster (no JVM warmup) and use less resident memory.
The JBang aliases cover only the listed JVM product surfaces. Model staging, model serving,
pipeline serving, and document-model execution require their native binaries and matching
side-loaded `lib/` payload; they do not cross into a JAR fallback.
