# Running Kompile via JBang (JAR fallback)

The normal distribution can use native binaries in `bin/`, while a `--jars-only`
distribution uses the shaded CLI and executable service JARs in `lib/`. The
`bin/kompile` wrapper prefers JBang and falls back to the bundled/system Java runtime.
The dedicated document-model/VLM worker remains native-only and is omitted from the JAR tier.

## Install JBang

```bash
curl -Ls https://sh.jbang.dev | bash -s - app setup
```

Or via SDKMan: `sdk install jbang`

## Run from this unpacked distribution

```bash
# From the extracted dist directory:
./bin/kompile --help
jbang ./lib/kompile-cli.jar --help
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
`build-dist.sh --jars-only` switches the CLI, delegated CLI commands, server, staging,
and local serving components to their shaded/exec JARs and skips native-image compilation.
The document-model/VLM worker has no executable-JAR counterpart yet, so it is not included
in that mode.
