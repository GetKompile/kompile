# Kompile releases on Azure

This directory runs complete Kompile platform builds on large Azure CPU VMs. It
uses the classifier matrix published by the sibling DL4J release stack, while
keeping Kompile's existing build contract in
`release/aws/build-platform.py`. Azure is responsible only for VM lifecycle,
managed identity, kill-switch control, and Blob transport.

Accelerator classifiers are compile-only workloads. CUDA, Vulkan, ZLUDA,
Hexagon, and TPU lanes therefore use large CPU machines; no Azure GPU quota is
required.

## What gets built

The Azure plan contains every non-macOS classifier currently present in
`../deeplearning4j/release/aws/release-plan.json` and
`../deeplearning4j/release/azure/release-plan.json`, including:

- Linux x86 CPU, AVX2, AVX512, oneDNN, compile, compile-AVX2,
  compile-AVX512, and compatibility variants.
- Linux ARM64 CPU, Arm Compute, oneDNN, and compile variants.
- Linux and Windows CUDA 12.6/12.9 base, cuDNN, and compile variants.
- Windows CPU/oneDNN/compile and Windows Vulkan.
- Android ARM64 CPU/Arm Compute/NNAPI/compile/Vulkan and Android x86_64.
- Linux Vulkan, Vulkan MLIR compile, Hexagon, TPU, and Linux/Windows ZLUDA.

A selected DL4J lane reuses one VM and builds its variants serially. Each
classifier runs `build-scripts/build-kompile-platform.sh` with the `full`
distribution, all native targets where the target permits native-image, an
isolated Maven repository, and optimized native-image settings. The plan also
contains dedicated `cli-only` distribution lanes for Linux x86_64, Linux
ARM64, and Windows x86_64. Selecting one DL4J classifier, CLI distribution
classifier, or parent lane creates the corresponding focused execution.

Both distribution shapes are Maven assemblies. Every build installs ZIP and
`tar.gz` artifacts under `ai.kompile:kompile-dist`: CLI-light uses
`cli-only-<platform>`, while complete builds use
`full-<dl4j-classifier>`. The Azure collector promotes those coordinates and
their checksums with the rest of the `ai/kompile` Maven tree.

Azure does not offer macOS VMs. Continue to build `macosx-arm64`/MPS on the
existing AWS dedicated-host lane.

## DL4J input modes

A DL4J branch or immutable commit is required for source-owned Java modules.
An optional Maven repository can supply the native snapshot/classifier inputs;
an Azure Blob Maven repository may instead supply its attested source commit.

### Build DL4J from source

Pass an immutable commit or a branch for both repositories. Each branch is
resolved through its exact remote `refs/heads/...` ref before provisioning.
The worker fetches that branch ref, verifies its tip still equals the resolved
40-character commit, and then builds the detached commit. The branch must be
pushed to the configured remote first.

```bash
KOMPILE_BRANCH=$(git branch --show-current)
release/azure/run.sh start \
  --location eastus \
  --version 0.1.0 \
  --branch "$KOMPILE_BRANCH" \
  --dl4j-branch master
```

Use `--repository` and `--dl4j-repository` to override the Kompile and DL4J
Git remotes. Source mode delegates each selected lane/variant to the release
driver at the verified DL4J branch commit; it does not silently fall back to
another checkout or Maven repository.

### Co-build DL4J Java modules with a Maven repository

Pass any credential-free HTTPS Maven 2 URL, including Sonatype Central
snapshots or the repository produced by the DL4J Azure collector. Kompile still
checks out the selected DL4J commit and runs DL4J's canonical cross-platform
Java reactor, installing owned modules such as `samediff-llm`, `samediff-vlm`,
and the SameDiff pipelines into the worker-local Maven repository. The remote
repository supplies native snapshot/classifier inputs; it never replaces owned
source modules. Runtime classifiers also need the matching per-lane SDK archive,
because DL4J publishes runtime ZIP/AAR payloads beside Maven rather than inside
Maven coordinates.

A Windows compile-classifier smoke run using Sonatype snapshots looks like:

```bash
KOMPILE_BRANCH=$(git branch --show-current)
release/azure/run.sh start \
  --location eastus2 \
  --version 0.1.0-SNAPSHOT \
  --branch "$KOMPILE_BRANCH" \
  --dl4j-branch ag_new_release_updates_2 \
  --shard windows-x86_64-compile \
  --dl4j-maven-repository-url \
    https://central.sonatype.com/repository/maven-snapshots/ \
  --dl4j-maven-repository-id sonatype-snapshots \
  --dl4j-sdk-assets-url \
    https://ACCOUNT.blob.core.windows.net/releases/deeplearning4j/releases/RUN_ID/{lane}--{variant}/sdk-assets.tar.gz
```

Supported SDK URL placeholders are `{lane}`, `{variant}`, `{platform}`,
`{version}`, and `{snapshotVersion}`. The SDK URL must be an HTTPS Azure
Blob URL. The downloader accepts either an adjacent `.sha256` sidecar or the
adjacent DL4J Azure `shard-manifest.json` attestation. Maven-only lanes such
as Vulkan, Hexagon, TPU, compatibility, and ZLUDA do not require an SDK URL.

For an Azure Blob Maven repository, the controller reads
`MAVEN_URL/.dl4j/complete.json` before provisioning and binds the Maven and
SDK inputs to the Azure run, immutable commit, and release version. Use
`--dl4j-maven-marker-url` only when that Azure collector exposes the marker
elsewhere. Sonatype has no DL4J Azure completion marker, so an explicit
`--dl4j-branch` or `--dl4j-commit` is required. It is recorded as the generic
`maven+source` input mode and the SDK archive is checksum-attested but not
identity-bound to the mutable snapshot repository.

## Machine sizing

The default x86 preference targets approximately 64 GiB of RAM without paying
for very high core counts:

```text
Standard_E8ds_v7
Standard_E8ds_v6
Standard_E8ds_v5
Standard_D16ds_v5
```

ARM64 prefers `Standard_D16ps_v5`. The controller queries regional SKU
restrictions and rejects machines with less than 64 GiB before provisioning.
Defaults are at most 16 vCPUs per VM, 64 aggregate concurrent vCPUs, a 1 TiB OS
disk, a 40 GiB Maven heap, and up to 16 build threads.

Use these controls when quota or regional availability differs:

```bash
release/azure/run.sh preflight \
  --location eastus \
  --max-cores 16 \
  --max-total-cores 64 \
  --lane-machine linux-x86_64-cpu=Standard_E8ds_v7

release/azure/run.sh start ... \
  --machine-type Standard_E8ds_v7 \
  --max-total-cores 128
```

The aggregate cap creates sequential batches; lanes inside a batch run in
parallel. `--shard` accepts a parent lane, an exact DL4J classifier, or a
published CLI classifier such as `cli-only-linux-x86_64`, and is repeatable.
`--exclude-shard` applies after selection.

Use `start --dry-run` to resolve commits, validate Blob inputs, select
regional SKUs, and print the exact batches without creating resources.

## Azure prerequisites

Install Azure CLI and Git, then authenticate:

```bash
az login
az account set --subscription SUBSCRIPTION_ID
```

The controller identity needs permission to create resource groups, VMs,
networks, user-assigned identities, role assignments, and Storage accounts. It
also needs Blob data access and permission to create a user-delegation key.

The controller creates:

- a retained base resource group and StorageV2 account;
- private `releases` artifact and `control` containers plus a public-read `maven` container;
- one reusable user-assigned worker identity with Blob Data Contributor on the private artifact container and Blob Data Reader on the control container;
- one compute resource group per run, containing its VNet and VMs.

Worker bootstrap uses a short-lived Entra user-delegation SAS. Workers use only
their user-assigned managed identity and AzCopy for artifact, log, status, and
kill-switch Blob access. Storage account keys are not embedded in workers.
Role-assignment propagation is handled by bounded AzCopy retries.

Windows administrator credentials are generated for the run unless
`AZURE_WINDOWS_ADMIN_PASSWORD` or `--windows-admin-password` is supplied.
The generated password is not printed or persisted by the controller.

## Operation

Validate the plan, regional SKU availability, and batching:

```bash
release/azure/run.sh preflight --location eastus
```

Run a small source-backed smoke build:

```bash
release/azure/run.sh start \
  --location eastus \
  --version 0.1.0-SNAPSHOT \
  --branch main \
  --dl4j-branch master \
  --shard linux-x86_64-compile-avx2
```

Inspect a run or worker log:

```bash
release/azure/run.sh status --location eastus --run-id RUN_ID
release/azure/run.sh logs --location eastus --run-id RUN_ID \
  --execution linux-x86_64-cpu--compile-avx2
```

A successful run automatically collects Maven artifacts unless
`--no-auto-collect` is set. Collection can be repeated:

```bash
release/azure/run.sh collect --location eastus --run-id RUN_ID
```

Emergency stop writes the global kill-switch first and then schedules deletion
of matching compute resource groups:

```bash
release/azure/run.sh stop --location eastus --run-id RUN_ID
release/azure/run.sh stop --location eastus --reason "operator emergency stop"
```

The Custom Script extension only installs and detaches the worker, avoiding the
extension execution limit on full builds. The controller then watches the
durable Blob marker and VM power state. Workers fail closed when the private
kill-switch Blob is unreadable. Each worker
uploads `build.log`, its Maven and SDK archives, and its shard manifest before
uploading `status.json`; the controller treats that final status object as the
durable completion marker and reports a stopped VM without it as a failure.

## Blob layout

Run output is retained under:

```text
releases/
  kompile/releases/RUN_ID/run.json
  kompile/releases/RUN_ID/EXECUTION_ID/build.log
  kompile/releases/RUN_ID/EXECUTION_ID/maven-repository.tar.gz
  kompile/releases/RUN_ID/EXECUTION_ID/sdk-assets.tar.gz
  kompile/releases/RUN_ID/EXECUTION_ID/shard-manifest.json
  kompile/releases/RUN_ID/EXECUTION_ID/status.json
```

The stable anonymous-read Maven repository is:

```text
https://ACCOUNT.blob.core.windows.net/maven/kompile/releases/maven-repository/
```

For example, a CLI-light release publishes both:

```text
ai/kompile/kompile-dist/VERSION/kompile-dist-VERSION-cli-only-linux-x86_64.zip
ai/kompile/kompile-dist/VERSION/kompile-dist-VERSION-cli-only-linux-x86_64.tar.gz
```

A complete classifier build uses the same paths with a classifier such as
`full-linux-x86_64-avx2`. Maven checksum sidecars are generated for both
archive formats.

`.kompile/complete.json` is written last. Consumers should require
`ready: true` and match the expected release/run identity before using a newly
published repository.

Compute resource groups are deleted after completion by default. Use
`--keep-resources` only for diagnosis and remove them with `stop` afterward.
The base Storage account, worker identity, run artifacts, and stable Maven
repository are intentionally retained.
