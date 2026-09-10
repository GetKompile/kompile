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
`cpu-intel`, `cpu-arm`, `cuda`, `amd-zluda`. The ZLUDA variant defaults to ROCm 7.2.4;
after the candidate artifacts are published, select ROCm 10 explicitly with
`--variant amd-zluda --backend-profile zluda-rocm-10.0.0`.

## Update an installed distribution

```bash
kompile update --check                 # inspect the newest compatible release
kompile update                         # download, verify, and install it
kompile update --version 0.2.0         # select an exact release
kompile update --prerelease --check    # opt in to prerelease candidates
kompile update --version 0.2.0 \
  --url file:///srv/kompile/releases   # custom mirror or local build output
```

`kompile update` reads `.dist-info.json` from the active installation root and preserves its exact
variant, platform, backend profile, and distribution classifier. It never falls back from CUDA/ZLUDA
to CPU or from `full` to `cli-only`. Update archives require both the external SHA-256 sidecar and a
valid internal `manifest.sha256`; mutable state under `config/`, `data/`, `models/`, `components/`,
`fact-sheets/`, `archives/`, and `anserini/` is preserved. Stable releases are selected by default,
implicit updates never downgrade, and `--prerelease` is opt-in. Use `--force` only to replace a
locally modified managed payload or reinstall the current version.

Windows currently supports `kompile update --check` only. Close Kompile and run the installer with
the reported version/variant to apply an update; the command fails rather than passing native Windows
paths through an unsafe MSYS replacement flow.

The command updates complete distributions installed by this script or manually extracted from a
release archive. Other loading mechanisms retain their own lifecycle:

| How Kompile is loaded | Update action |
|---|---|
| GitHub archive / install script | `kompile update` |
| Custom HTTPS mirror or local `file:` archive directory | `kompile update --version <v> --url <base>` |
| JBang stable-JAR alias | Refresh the catalog/version reference and relaunch the alias |
| Source checkout, Maven execution, or `install.sh --dev` | Rebuild, then rerun the development install |
| Docker image | Pull the desired image/tag and recreate the container |
| Individually installed component | Reinstall that component with `kompile install ... --force` |

A CLI without managed `.dist-info.json` fails closed and prints this guidance rather than guessing
which distribution lane to install.

## Manual download

Download the archive for your platform from
[GitHub Releases](https://github.com/GetKompile/kompile/releases). Archives are named:

```
kompile-dist-<version>-<variant>-<release-lane>.<ext>
```

The release lane begins with `<os>-<arch>` and retains backend qualifiers when present;
for example, ROCm 10 uses
`kompile-dist-0.1.0-amd-zluda-linux-x86_64-cuda-12.9-zluda-rocm-10.0.0.tar.gz`.
Here `os` ∈ {`linux`, `macosx`, `windows`}, `arch` ∈ {`x86_64`, `arm64`}, and `ext` is
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
