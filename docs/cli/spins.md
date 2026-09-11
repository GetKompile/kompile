# Kompile Spins

A **spin** is an installable Kompile persona with its own system prompt, role/tool
policy, MCP sidecars, optional local models, and persistent project workspace.
It reuses the normal Kompile chat/runtime rather than rebuilding a native image for
every prompt.

The implementation is available through `kompile spin` when invoked from the main
CLI and as `kompile-agent spin` / `kompile agent spin` through the delegated agent
CLI.

## Quick start

```bash
kompile spin init my-assistant
cd my-assistant

# Edit prompt.md, instructions.md, agent.yaml, and spin.yaml.
kompile spin validate .

# Keep publisher keys outside the source so the private key cannot be bundled.
kompile spin keygen --private-key ../publisher-private.pem \
  --public-key ../publisher-public.pem
kompile spin build . -o ../my-assistant-0.1.0.kspin \
  --signing-key ../publisher-private.pem
kompile spin validate ../my-assistant-0.1.0.kspin \
  --trusted-key ../publisher-public.pem
kompile spin install ../my-assistant-0.1.0.kspin \
  --trusted-key ../publisher-public.pem

~/.my-assistant/bin/my-assistant
~/.my-assistant/bin/my-assistant exec "Perform one task"
~/.my-assistant/bin/my-assistant doctor
```

A spin installs into a dedicated `~/.<spin-id>` home by default. Immutable payloads
are checksum-verified and stored under
`components/<spin-id>/releases/<version>-<digest>/`; `workspace/` remains stable
across upgrades. Model and tool payloads stay in the immutable release, and generated
workspace configuration points to them, avoiding a multi-gigabyte copy per run.
New spins disable global conversation-memory injection by default so the packaged
persona starts from its own prompt and workspace. Pass `--memory` to a launch/exec
command when deliberately opting into the user's Kompile memory.

## Files

A spin source is also a valid agent-bundle directory:

```text
my-assistant/
├── spin.yaml
├── agent.yaml
├── prompt.md
├── instructions.md
├── tools/
│   └── my-mcp-server
├── models/
│   ├── model.gguf
│   └── tokenizer.json
├── skills/
│   └── lookup/SKILL.md
└── workspace/
    └── data/input_documents/
```

`spin.yaml` owns distribution metadata:

```yaml
schemaVersion: '1'
metadata:
  id: my-assistant
  version: 1.0.0
  displayName: My Assistant
  command: my-assistant
runtime:
  delivery: thin # thin or embedded
```

`agent.yaml` owns runtime behavior:

```yaml
schemaVersion: '1'
metadata:
  name: my-assistant
  description: A packaged assistant
engine: cli-loop
systemPrompt:
  path: prompt.md
instructions:
  - instructions.md
skills:
  - path: skills/lookup/SKILL.md
    name: lookup
role:
  name: my-assistant
  canSpawn: false
  tools:
    - mcp_tool_search
    - mcp_tool_call
models:
  - id: bundled-model
    path: models/model.gguf
    tokenizer: models/tokenizer.json
    provider: kompile-local
    default: true
mcp:
  servers:
    - id: assistant-tools
      command: tools/my-mcp-server
      bundled: true
      executable: true
      required: true
      includeTools: [lookup]
```

## Bundled MCP tools

A bundled MCP server must use the stdio protocol and declare a bundle-relative
`command` with `bundled: true`. During installation the command remains inside the
versioned release, is made executable, and its generated `.mcp.json` entry receives
an absolute path. The normal MCP fields—including `required`, `timeoutSeconds`,
`includeTools`, `excludeTools`, `args`, `cwd`, and `env`—are preserved.

MCP `env` entries whose names indicate credentials must not contain literal secrets.
They accept references such as `${API_TOKEN}` or `%API_TOKEN%`; the credential value
is supplied by the user at launch time. The placeholders `${SPIN_ROOT}`,
`${SPIN_HOME}`, and `${SPIN_WORKSPACE}` are resolved by the installer.

## Bundled models

Any number of model assets may be listed. One may be marked `default`. A default
`kompile-local` model automatically configures project-local chat and exports
`KOMPILE_CHAT_MODEL_PATH` plus `KOMPILE_CHAT_TOKENIZER_PATH` to Kompile. GGUF and
SDZ models therefore use the distribution's normal model-serving subprocess. Validation
fails closed when an embedded default local model is packaged without
`kompile-model-serving`; thin spins perform the same preflight against the selected
external Kompile installation. Raw GGUF/ONNX/SafeTensors conversions are cached under
the persistent `workspace/.kompile/model-cache` directory, never beside the source in
the immutable release.

A per-model `sha256` may be declared in `agent.yaml`. The complete `.kspin` also has
a required `manifest.sha256`, so tools, prompts, models, and an embedded runtime are
all verified before an installation is published.

## Publisher signatures

`manifest.sha256` proves internal integrity but is not publisher authentication. Public
releases can add a detached Ed25519 signature over a domain-separated SHA-256 digest
that covers every byte of the `.kspin` archive. Digest signing keeps memory bounded for
multi-gigabyte model bundles:

```bash
# Generate once. Store the private key securely and publish the public key separately.
kompile spin keygen \
  --private-key /secure/publisher-private.pem \
  --public-key publisher-public.pem

# Build and sign in one operation; this writes release.kspin.sig by default.
kompile spin build . -o release.kspin \
  --signing-key /secure/publisher-private.pem

# Consumers obtain publisher-public.pem through an independently trusted channel.
kompile spin validate release.kspin --trusted-key publisher-public.pem
kompile spin install release.kspin --trusted-key publisher-public.pem
```

`--trusted-key` makes the signature mandatory and defaults its location to
`<archive>.sig`; use `--signature PATH` for another sidecar name. A signature without
an explicitly trusted public key is rejected. Existing private or internal workflows
may still build and install unsigned spins, but should not treat checksums as publisher
identity. `kompile spin sign ARCHIVE --private-key KEY` can sign an already-built
archive. Build rejects signing keys inside the source tree to prevent accidentally
shipping private key material. Validation and installation stream the source into a
private temporary archive while verifying, then parse and extract only that exact
authenticated copy so a path replacement cannot race signature verification.

## Thin and embedded delivery

- `thin` resolves a compatible `kompile`/`kompile-agent` from `KOMPILE_CLI`,
  `KOMPILE_INSTALL_DIR`, `~/.kompile`, or `PATH`.
- `embedded` carries an extracted canonical Kompile distribution under `runtime/`.
  Build it with `--runtime-dir /path/to/extracted/kompile-dist`; the installed
  launcher prefers this pinned runtime. It must contain `kompile-agent`; a spin whose
  default model uses `kompile-local` must also contain `kompile-model-serving`.

Set `runtime.delivery: embedded` in `spin.yaml`, then build:

```bash
kompile spin build . --runtime-dir /opt/build/kompile-dist -o my-assistant.kspin
```

Credentials are never copied from the base distribution into a spin. User auth and
provider configuration remain external unless the spin selects a bundled local model.
Checksums detect corruption or payload changes; public spin archives should use the
detached publisher-signature flow above.
