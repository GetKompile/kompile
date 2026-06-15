# Installation

## Pre-built binaries (recommended)

Download the archive for your platform from [GitHub Releases](https://github.com/GetKompile/kompile/releases):

| Platform | Archive |
|----------|---------|
| Linux x86\_64 (CUDA) | `kompile-<version>-linux-x86_64-cuda12.9.tar.gz` |
| Linux x86\_64 (CPU) | `kompile-<version>-linux-x86_64-cpu.tar.gz` |
| Linux ARM64 | `kompile-<version>-linux-arm64-cpu.tar.gz` |
| macOS Apple Silicon | `kompile-<version>-macosx-arm64-cpu.tar.gz` |
| Windows x86\_64 | `kompile-<version>-windows-x86_64-cuda12.9.zip` |

Extract and add `bin/` to your PATH:

```bash
tar xzf kompile-*-linux-x86_64-cuda12.9.tar.gz
export PATH=$PWD/kompile-*/bin:$PATH

# Verify
kompile --version
```

Native libraries auto-resolve from the adjacent `lib/` directory. No environment variables or setup needed.

## Bootstrap (from source checkout)

If you are building from source, the CLI can install its own dependencies:

```bash
# Initialize Kompile directory (~/.kompile)
kompile bootstrap

# Install all dependencies (GraalVM, Maven, Anaconda)
kompile install all

# Install specific components
kompile install graalvm
kompile install python
```

## Docker

```bash
# Run CLI in container
docker run --rm -it konduitai/kompile

# Run interactively with mounted volume
docker run --ulimit nofile=98304:98304 \
  --rm -it \
  -v $(pwd):/mnt/:Z \
  --entrypoint /bin/bash konduitai/kompile
```

## First-time configuration

After installation, initialize the config directory and run the interactive wizard:

```bash
kompile configure init          # Creates ~/.kompile/ and default config files
kompile configure app           # Interactive 9-section config wizard
```

Or configure individual areas:

```bash
kompile configure chat          # Chat session mode, LLM provider, agent preferences
kompile configure mcp           # MCP profile and schema level
kompile configure enforcer      # Per-project policy rules
```
