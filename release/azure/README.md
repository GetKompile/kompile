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

### Consume published DL4J Maven artifacts

Pass any credential-free HTTPS Maven 2 URL, including the published Sonatype
Maven snapshot repository or the repository produced by the DL4J Azure collector.
`--version` is the Kompile distribution version; `--snapshot-version` is the
DL4J/ND4J snapshot version resolved from that repository and defaults to
`1.0.0-SNAPSHOT`. In repository-only mode Kompile does not check out DL4J or
rebuild owned Java modules such as `samediff-llm`; it consumes the published
coordinates and, for runtime classifiers, the matching per-lane SDK archive.

A Windows compile-classifier smoke run using Sonatype snapshots looks like:

```bash
KOMPILE_BRANCH=$(git branch --show-current)
release/azure/run.sh start \
  --location eastus2 \
  --version 0.1.0-SNAPSHOT \
  --snapshot-version 1.0.0-SNAPSHOT \
  --branch "$KOMPILE_BRANCH" \
  --shard windows-x86_64-compile \
  --dl4j-maven-repository-url \
    https://central.sonatype.com/repository/maven-snapshots/ \
  --dl4j-maven-repository-id sonatype-snapshots \
  --dl4j-sdk-assets-url \
    https://ACCOUNT.blob.core.windows.net/releases/deeplearning4j/releases/RUN_ID/{lane}--{variant}/sdk-assets.tar.gz
```

Add `--dl4j-branch` or `--dl4j-commit` only when a source-backed DL4J
co-build is explicitly wanted; that selects the `maven+source` input mode.
Supported SDK URL placeholders are `{lane}`, `{variant}`, `{platform}`,
`{version}`, and `{snapshotVersion}`. The SDK URL must be an HTTPS Azure
Blob URL. The downloader accepts either an adjacent `.sha256` sidecar or the
adjacent DL4J Azure `shard-manifest.json` attestation. Maven-only lanes such
as Vulkan, Hexagon, TPU, compatibility, and ZLUDA do not require an SDK URL.

For an Azure Blob Maven repository, the controller reads
`MAVEN_URL/.dl4j/complete.json` before provisioning and binds the Maven and
SDK inputs to the Azure run, immutable commit, and release version. Use
`--dl4j-maven-marker-url` only when that Azure collector exposes the marker
elsewhere. Without a source ref the input mode is `azure-blob-maven`; a source
ref makes it `azure-blob-maven+source`.

Collected Kompile coordinates are published into the established public DL4J
Maven tree at
`https://ACCOUNT.blob.core.windows.net/releases/deeplearning4j/releases/maven-repository/`.
Only `ai/kompile` is promoted there; per-worker archives and logs remain in the
private `kompile-artifacts` container.

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
also needs Blob data access and permission to read storage account keys for the
existing DL4J cache account so it can mint the short-lived SAS values.

The controller creates:

- a retained base resource group and StorageV2 account;
- private `kompile-artifacts` and `control` containers plus the public-read
  `releases` container that already holds the canonical DL4J Maven tree;
- one reusable user-assigned worker identity with Blob Data Contributor on the
  private artifact container, Blob Data Reader on the control container, and
  Blob Data Contributor on the existing DL4J `releases` cache container;
- one compute resource group per run, containing its VNet and VMs.

Worker bootstrap uses a short-lived account-key SAS. Workers use only
their user-assigned managed identity and AzCopy for artifact, log, status, and
kill-switch Blob access. The controller creates a separate short-lived
read/write/create SAS for DL4J's existing `deeplearning4j/releases/compiler-cache/v1`
namespace; it is passed only through the temporary delegated DL4J lane config,
never written to `run.json` or the worker source. DL4J's own `cloud-io.py` and
`dependency-cache.py` helpers are embedded in both worker variants so sccache
toolchain snapshots use the managed identity. Storage account keys are not
embedded in workers. Role-assignment propagation is handled by bounded AzCopy
retries.

Kompile's content-addressed Graal native-image cache is separate from DL4J's
compiler cache. Azure workers use the managed identity to restore and publish
entries under `kompile/native-image-cache/v1/<shard>/<aot-fingerprint>/`; the
existing local cache remains the first-level cache, and the receipt's SHA-256
checksum is verified before an image is restored. A new VM can therefore reuse
completed CLI/app/service images without rerunning Graal, while a changed source,
Maven dependency, Graal version, or native-image argument naturally gets a new
fingerprint.

The cache account defaults to the canonical DL4J account derived from the Azure
subscription and location (`dl4jrel…`). Override it with
`--dl4j-cache-storage-account` or `DL4J_AZURE_STORAGE_ACCOUNT` when using an
existing account with a different name.

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
