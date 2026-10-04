# Project-backed curated spin distributions

Status: proposed design; no runtime implementation or build validation performed.

## Confirmed product contract

A spin is a distributable Kompile product: a harness, curated models, pipelines, application infrastructure, and selected chip/backend payloads.

- Include **everything declared in the curated spin** by default, not the entire model/backend catalog.
- Support all declared functions, not only default LLM chat. Do not impose an arbitrary one-chip limit.
- Deliver GraalVM **native-image executables**; shipping a GraalVM development JDK is not the requirement.
- Include a clickable web-chat UI, with local/loopback access by default.
- Provide explicit inclusion/provisioning choices without changing the product's declared features.
- Normally acquire prebuilt backend Java/native JARs. Building DL4J from source is a separate, explicit build choice.
- Reuse the folder-local project abstraction. Keep user state and domain installation roots isolated from the global Kompile installation.

This document specifies intended behavior, not currently accepted schema or commands.

## 1. Ownership: project composition, spin release envelope

`kompile.project.json` is the composition authority. Its existing models, pipelines, crawl profiles, components, scripts, and workflows describe what the product contains. Harness roles, skills, MCP configuration, tool policies, prompts, and application resources remain project-owned files.

The spin envelope retains release identity, version, command name, publisher/signature, project-template location, and archive/install metadata. It references the project composition; it must not maintain another editable model inventory.

The existing `.kspin` archive, hashing/signing, staged installation, immutable releases, and stable workspace are reused. Do not create another package registry or installer framework.

### Small schema addition

Add one typed, optional `distribution` object to the project manifest. Conceptual shape:

```json
{
  "distribution": {
    "delivery": "native",
    "materialization": "bundle",
    "targets": [
      {
        "id": "desktop-cpu",
        "os": "linux",
        "architecture": "x86_64",
        "backend": "<pinned backend artifact selection>"
      }
    ],
    "software": [
      {
        "id": "chat",
        "component": "<application component requirement>",
        "version": "<pinned version>"
      }
    ],
    "web": { "enabled": true, "bind": "127.0.0.1" }
  }
}
```

The example is schematic, not an executable configuration. Exact component/model identifiers come from the existing registries or explicit pinned artifact definitions.

- `models` and `pipelines` stay in their current project collections. Pipeline bindings reference those model IDs.
- `software` identifies software/toolchain requirements and optional existing storage-component destinations. It does not turn every storage registration into an installer.
- Targets describe OS, architecture, and backend artifacts. Device placement and memory ceilings remain runtime configuration, not artifact coordinates.
- Default web entry point uses the declared chat component and spin harness configuration.
- Default materialization applies to the whole declared composition. Per-requirement overrides may choose provision or external supply. External mode is explicitly not self-contained.

Introduce a versioned project-backed spin schema alongside legacy v1, with explicit reader compatibility checks. Do not silently treat unrecognized delivery, target, or provisioning fields as legacy defaults. Preserve legacy spin behavior; use embedded/native defaults for new project-backed spins.

## 2. Resolve once, produce a release lock

Build planning resolves the project into a generated lock included in the signed archive. This is an immutable release receipt, not a second editable registry.

For each asset record:

- originating project requirement/model/pipeline ID;
- pinned release/repository revision and artifact source;
- relative release destination, checksum, size, and redistribution notice;
- target applicability and complete runtime dependency closure;
- bundle/provision/external disposition;
- build provenance and any conversion output identity;
- qualification evidence or explicit unqualified status.

Local/source-built assets are hashed and record source/build provenance. Never resolve “newest installed” artifacts to satisfy a locked release. Credentials and private source credentials are not included in the archive or receipt.

Reject conflicting versions of shared runtime dependencies within one worker closure. Multiple targets can have separate closures. Validate reference integrity, traversal/symlink boundaries, distribution paths, and required notices before materialization.

## 3. Build tiers and materialization are separate

| Tier | Work performed |
| --- | --- |
| Assemble | Acquire or reuse pinned, prebuilt application executables/JARs, backend artifacts, and model packages; package without compilation. |
| Java | Build the declared Kompile/domain Java components, using prebuilt backend artifacts. |
| Native image | Produce declared GraalVM executables from the Java components. |
| Backend source | Explicitly build required DL4J/native artifacts from an identified source checkout, then feed those artifacts into assembly or application compilation. |

These are composable stages, not four unrelated distribution variants. Source-building a backend does not mandate source-building all application components. Native delivery may be assembled from prebuilt native releases without invoking GraalVM on the recipient's machine.

A build plan states which components use which stages. Reuse existing distro recipes and project workflows. Do not make normal install/launch perform Maven or native compilation.

For local development SNAPSHOTs, the local DL4J checkout and installed artifacts remain authoritative. Source builds must be explicitly requested and must not switch branches, reset repositories, or refresh remote snapshots implicitly. Use current `org.eclipse.deeplearning4j` coordinates and concrete backend dependencies, not end-user aggregate dependencies.

Materialization choices:

- **Bundle (default):** include all required runtime assets. Installation and ordinary launch require no acquisition network access.
- **Provision:** include the same pinned requirements; installation obtains/verifies missing assets into a separate spin-owned installation-assets staging area before activation, leaving the signed release untouched.
- **External:** explicitly depend on an operator-supplied runtime/artifact; validate it and report the distribution as non-self-contained.

Build toolchains belong in the bundle only when declared as product requirements. Native executables do not imply shipping a development JDK. JBang is an optional JAR-tier launcher/provisioner, not an offline guarantee or a replacement for native delivery.

## 4. Chip/backend payloads and native execution

Packaging supports multiple declared targets. Native executables are OS/architecture-specific; chip support requires the appropriate backend/native dependency closure and runtime qualification.

Keep target payloads separate. Select a worker's closure before launching it; do not put all backend JARs into a shared classpath. Use existing managed subprocess contracts and bundle-first resolution rather than bespoke domain launchers.

There are two valid execution forms to represent explicitly:

1. A qualified native worker containing its supported backend. Backend JARs cannot be dynamically substituted into a compiled native image.
2. A packaged JVM worker with the selected backend JAR/native closure. When this form is declared, bundle or provision its Java runtime as part of the same composition; never rely implicitly on host Java.

A native CLI and native web entry point may therefore coexist with declared JVM workers. Report this as mixed worker delivery, not as a fully native backend. A target requesting an unsupported native worker must fail planning or require an explicit JVM-worker choice, not silently degrade.

Separate artifact inclusion from device availability. Installed files do not prove usable drivers, accelerator access, operator coverage, or model correctness. Target selection must show what was requested, what was selected, and why. Require explicit fallback policy; do not silently call a CPU fallback successful accelerator execution.

Do not infer support from a backend profile, enum, descriptor, or filename. Each advertised target/function pair needs execution evidence. No kernel changes or cross-platform capability claims are part of this design.

## 5. Model and pipeline integration

Use existing project model registration and acquisition/conversion lifecycles. Keep inference resolution read-only.

- Materialize declared weights, tokenizers, processor/config files, and auxiliary assets.
- Resolve local imports into release-relative paths; an external absolute path must not escape into a self-contained release.
- Provision runtime-form artifacts where required. A downloaded source artifact is not equivalent to a usable runtime model.
- Extend existing pinned acquisition definitions when the curated composition includes a model not covered by the current managed catalog. Do not restrict the product to that catalog's current entries or invent a second downloader.
- Register the complete model set in the installed project inventory and preserve pipeline bindings.
- Make chat default/model selection reference the project inventory. Explicit model selection must win over a default model-path environment variable.
- Eliminate the project-backed path's orphan `spin-models.json` inventory; retain a legacy import/adapter only for older spins.
- Initialize from the curated project template without injecting hardware-tier generic model defaults.

All declared functions must have their required assets and bindings. Depending on the composition this can include chat, embeddings, reranking, document/image processing, graph learning, and arbitrary registered pipelines. Do not equate “all functions” with hardcoded LLM/VLM presets.

Offline qualification concerns local declared functions. An explicitly declared external provider remains an external dependency and cannot be advertised as offline.

## 6. Release layout and installation

Conceptual layout (preserve existing canonical runtime filenames):

```text
release/
  spin envelope + signed archive manifest
  project/                    curated project template and harness resources
  runtime/                    canonical Kompile executables/application assets
  targets/<target-id>/         separate worker/backend dependency closures
  models/                     locked model packages
  notices/
  distribution lock + qualification receipts
installation-assets/<release-id>/  provisioned software/backend assets + install receipt
workspace/
  kompile.project.json         instantiated project inventory
  config/                     user overrides and local secrets
  data/                       chats, documents, graph and other mutable state
  logs/
```

Exact model placement must satisfy project-root containment. The baseline is verified regular files beneath `workspace/data/models/<release-id>/`, with project-relative inventory paths. Stage that directory and the inventory before activation; retain the previous release's files for rollback. Use filesystem copy-on-write copies when available, with ordinary copies as the portable fallback. Do not use outside-root symlinks or writable hardlinks to signed payloads, or weaken inference containment checks. This baseline costs extra disk space; safe immutable reuse is an optimization, not an installation prerequisite.

Installation flow:

1. Verify archive hashes, signatures/trust policy, schema, and target compatibility.
2. Stage the immutable release; acquire only assets explicitly marked provision into separate installation-assets staging.
3. Materialize the project workspace references and generate runtime inventory from the locked template.
4. Validate required assets and executable contracts; collect runtime qualification status without confusing it with file presence.
5. Publish the release only after required installation checks pass. Keep the previous release available if staging fails.

The current archive verifier rejects unlisted files, so provisioned artifacts and installation receipts must not be added inside the signed release or its checksum manifest rewritten. Verify their complete inventory against the signed distribution lock, then publish a separate release-keyed installation-assets directory; runtime resolution receives those pinned roots explicitly. Provisioned model files are materialized under the project workspace as described above.

Workspace changes need staging/rollback as well as release activation; failure must not leave a partially migrated inventory. Never overwrite user credentials, campaign data, chats, or graph state during upgrades. Define owned/generated configuration separately from user overrides. Existing signed payloads remain immutable; activated provisioned assets are verified against the signed lock and a separate installation receipt.

Global Kompile binaries, Maven repositories, Hugging Face caches, and host Java must not be hidden requirements of bundle mode. An embedded spin uses its declared runtime; environment overrides require an explicit external/development mode and are visible in diagnostics.

## 7. Local web-chat launch

The default product launcher starts its declared CHAT persona, checks readiness, and opens or prints its local URL. It passes the stable project workspace and harness context through the existing chat context/managed launch boundary.

- Bind to loopback, not the current all-interface CHAT default. Public binding is an explicit operator choice.
- Preserve the persona classpath boundary. Packaging admin/crawl infrastructure does not mount its controllers inside CHAT or automatically expose every service.
- Start only services needed by the entry-point workflow. Use a spin-owned web-launch workflow rather than the generated generic `start-services` workflow, whose current project upgrader restores the all-service sequence. Folder-local crawl remains MCP-host local execution, not a reason to boot the crawl-manager/admin web processes.
- Keep logs, model caches, subprocess configuration, and data in spin-owned paths.
- Launch reports readiness failure and child logs; it does not silently fall back to a global installation.
- Support clean shutdown and bounded model-worker resource limits.

Native CHAT web handoff is currently rejected by `ChatInstanceBootstrap`. Bridge the same project/harness context into native CHAT before advertising native web delivery. A bundled CHAT JAR can be an explicit compatibility form, but must not be presented as completion of the native requirement.

Local access is a deployment default, not a security exemption. Bind auxiliary listeners locally, avoid exposing admin APIs, and preserve existing tool/MCP authorization boundaries. Do not add network multi-user/auth infrastructure to this scope.

## 8. Compatibility and concrete implementation boundaries

| Existing area | Intended change |
| --- | --- |
| `KompileProjectManifest` / project-store | Typed distribution requirements, schema compatibility, validation. Existing storage components remain storage registrations. |
| `SpinDefinition`, `SpinWorkspace` | Project-backed envelope and template; complete model inventory; no generic model insertion. Legacy v1 adapter. |
| `SpinArchive`, `SpinInstallation`, `SpinSignature` | Reuse release lifecycle; lock all materialized/provisioned assets; validate relocation and transactional workspace updates. |
| `SpinLauncher` | Local web default, spin-owned runtime/config/cache/log selection, explicit target selection and external override policy. |
| Local-project acquisition/bootstrap | Reuse acquisition/conversion and read-only inference; release-relative registrations and broad declared model support. |
| `KompileLocalServingBootstrap`, model discovery | Project inventory as authority; explicit selection before default overrides. |
| `ChatInstanceBootstrap` / web context | Native context handoff, loopback default for spins, spin-owned logs. |
| `ManagedSubprocessLauncher` / `SubprocessBackendResolver` | Select pinned target closures; generalize the CPU/CUDA-shaped interface without equating descriptors to tested support. |
| `build-dist.sh` and existing build workflows | Materialize declared target set and component build stages; avoid an independent spin build system. |

## 9. Delivery sequence and acceptance gates

1. **Composition/compatibility:** add schema and resolved-plan validation; map project-backed spins into existing archive/install lifecycle. Test old spins unchanged and new unsupported schema rejected.
2. **Materialization:** complete models/software/backend closure, lock/receipt, bundle/provision/external paths, safe relocation and upgrade staging. Test missing assets, digest failures, conflicting closures, path escapes, and failed upgrades.
3. **Native local web launch:** implement native context handoff and spin-root resolution; test actual native CLI + CHAT startup, loopback binding, harness/model selection, and shutdown.
4. **Target/workload qualification:** test every advertised target against its declared functions. Source-build workflows are explicit optional stages, not an excuse to narrow feature support.

A complete bundle must pass on a fresh machine/test environment without global Kompile, host Java, Maven, shared model caches, or acquisition network access. Use actual executables and representative model workloads, not only fake launcher fixtures. Verify declared asset/function coverage, correct backend selection, resource limits, project isolation, and preservation of mutable state after upgrade.

Package integrity tests and runtime tests produce different receipts. An archive can be structurally valid yet unqualified on hardware. Unavailable hardware must be recorded as untested, never as passed or universal backend support.

## Evidence anchors from research

- `KompileProjectManifest.java:25-40`: existing composition collections.
- `KompileProjectComponent.java:25-35`, `KompileProjectStore.java:532-537`: storage registration, not software acquisition; project-store `:205-259` refreshes the generic all-service lifecycle.
- `LocalProjectModelAcquisition.java:81-120`: pinned acquisition and runtime registration; `:169-175`: relative paths and source/runtime distinction.
- `LocalProjectModelBootstrap.java:74-122,149-173`: read-only inference and explicit artifact-versus-runtime readiness.
- `SpinDefinition.java:144-166`: current thin/embedded delivery and model/agent envelope; `SpinArchive.java:208-235` rejects unlisted or mismatched release files.
- `SpinWorkspace.java:211-238`: separate spin model inventory/default chat configuration.
- `KompileLocalServingBootstrap.java:1204-1298`: current model-path resolution precedence.
- `ProjectCommand.java:374-388`: generic provisioning plan currently applied during project initialization.
- `ChatInstanceBootstrap.java:144-145,151,168-169`: JAR-only web handoff, global handoff log root, all-interface default.
- `SubprocessBackendResolver.java:199-269,294-301`: CPU/CUDA-shaped selection and first-root dependency discovery.
- `build-dist.sh`: selected backend profile and existing native/JAR distribution recipe.
- `../campaign-builder/elthoria-mcp-server`: reusable domain harness/versioned upgrade pattern, currently requiring external Kompile.

Line references describe inspected source and may shift. This design has not executed native builds, provisioned artifacts, or established additional hardware/model support.
