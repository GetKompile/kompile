# Installation

## Install script (recommended)

```bash
curl -fsSL https://get.kompile.ai/install.sh | bash
```

This detects your OS/arch, downloads the matching archive from
[GitHub Releases](https://github.com/GetKompile/kompile/releases), verifies its checksum,
extracts it to `~/.kompile`, and prints how to add `bin/` to your `PATH`. Override defaults with:

```bash
# specific version / variant / install dir
curl -fsSL https://get.kompile.ai/install.sh | bash -s -- \
  --version 0.1.0 --variant cli-only --dir ~/.kompile
```

**Variants:** `cli-only` (the cross-platform `kompile` CLI — the backend is fetched on demand via
`kompile install kompile-app`), `hosted` (CLI **plus** the bundled app-main server),
`cpu-intel`, `cpu-arm`, `cuda`, `amd-zluda`.

## Manual download

Download the archive for your platform from
[GitHub Releases](https://github.com/GetKompile/kompile/releases). Archives are named:

```
kompile-dist-<version>-<variant>-<os>-<arch>.<ext>
```

where `os` ∈ {`linux`, `macosx`, `windows`}, `arch` ∈ {`x86_64`, `arm64`}, and `ext` is
`tar.gz` (Linux/macOS) or `zip` (Windows). For example:

| Platform | Example archive |
|----------|-----------------|
| Linux x86\_64 | `kompile-dist-0.1.0-cli-only-linux-x86_64.tar.gz` |
| Linux ARM64 | `kompile-dist-0.1.0-cli-only-linux-arm64.tar.gz` |
| macOS Apple Silicon | `kompile-dist-0.1.0-cli-only-macosx-arm64.tar.gz` |
| Windows x86\_64 | `kompile-dist-0.1.0-cli-only-windows-x86_64.zip` |

Extract and add `bin/` to your PATH:

```bash
tar xzf kompile-dist-*-linux-x86_64.tar.gz
export PATH="$PWD/kompile-*/bin:$PATH"

# Verify
kompile --version
```

Each archive ships a matching `.sha256`; verify with `sha256sum -c <archive>.sha256`.
For the `hosted` variant, native libraries auto-resolve from the adjacent `lib/` directory —
no environment variables or setup needed.

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
