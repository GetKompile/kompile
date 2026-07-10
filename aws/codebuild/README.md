# AWS CodeBuild: DL4J → kompile build & distribution matrix

Builds deeplearning4j from source at any ref, then the kompile distribution,
for every non-release platform workflow under
`../deeplearning4j/.github/workflows` — 33 targets mapped in `targets.yml`.
Artifacts publish to S3 always, and to GitHub Releases when configured.

## One-go quickstart

```bash
cp aws/codebuild/parameters.env.example ~/.config/kompile-codebuild.env
# fill the REQUIRED block: region, clone URLs, refs, GraalVM/JDK archive URLs

# private repos only: store a GitHub PAT and register it with CodeBuild
printf '%s' "$TOKEN" | aws/codebuild/scripts/aws/set-github-token.sh \
  ~/.config/kompile-codebuild.env kompile-github-token

aws/codebuild/scripts/aws/provision.sh ~/.config/kompile-codebuild.env build

aws/codebuild/scripts/aws/start-all.sh ~/.config/kompile-codebuild.env build
```

`provision.sh` runs: config resolution (account id, ECR registry, buckets,
image URIs) → preflight (reports every problem at once) → account bootstrap
(buckets, ECR repository, fleet service role) → source credentials → container
images → reserved fleets → one CloudFormation stack per target. Derived values
append to `generated-codebuild.env` beside your config. Rerunning is safe:
every step is idempotent and unchanged stacks are no-ops.

Targets the current config cannot host (no macOS fleet, no Windows AMI, no
AMD GPU fleet) are **skipped with a reason**, never fatal. The deploy summary
lists deployed/skipped/failed.

**Important:** CodeBuild clones `SOURCE_LOCATION` at `KOMPILE_REF` — this
`aws/codebuild` tree must be committed and pushed to that ref before builds
can run (preflight warns if it is not).

## Starting builds

```bash
# everything of a kind, optionally overriding refs and tagging a release
scripts/aws/start-all.sh CONFIG build [KOMPILE_REF] [DL4J_REF] [RELEASE_TAG]

# one target
scripts/aws/start-build.sh CONFIG linux-cuda-12.9 [KOMPILE_REF] [DL4J_REF] [RELEASE_TAG]
```

kompile and DL4J refs are independent per build. `ENABLED_TARGETS` in the
config restricts both deploys and batch starts to an allowlist.

## What each target produces

`targets.yml` gives every target a `variant` (kompile distribution flavor)
and `kompile` (whether to build kompile after DL4J):

- `linux-x86_64`/`-compat` → `cpu-intel`; `linux-arm64` → `cpu-arm`;
  `linux-cuda-12.6`/`12.9` → `cuda`; `linux-zluda` → `amd-zluda`;
  `macos-arm64` → `cli-only`.
- Android, cross-platform tokenizers, TPU, Hexagon, Windows, and validation
  targets build DL4J only (`kompile: false`) — their installed Maven
  artifacts still ship as `<target>-maven-artifacts.tgz` in dist.
- Validation targets first **build the checked-out backend from source**,
  then run the ported test workflows (so tests exercise your ref, not
  published snapshots).

Edit `targets.yml` to change variants; redeploy with
`scripts/aws/deploy-target.sh CONFIG <target>`.

## Publishing

Every successful build uploads `dist/` twice:

1. **S3 (always)**: `s3://$ARTIFACT_BUCKET/$RELEASE_PREFIX/<RELEASE_TAG or
   short commit>/<target>/` — plus CodeBuild's per-build zipped artifact.
2. **GitHub Release (opt-in)**: set `GITHUB_RELEASE_REPO=owner/repo` and
   `GITHUB_RELEASE_TOKEN_SECRET` (a Secrets Manager secret with a
   `contents: write` token), then start builds with a `RELEASE_TAG`. Assets
   from all platforms attach to one release, prefixed with the target name.
   If the tag does not exist yet, GitHub creates it on the default branch —
   pre-create the tag/release to pin a commit.

## Build lanes and what enables them

**Linux container lanes (work out of the box):** linux-x86_64 (+compat,
cross, cpu validation), CUDA 12.6/12.9 builds (compilation needs no GPU),
ZLUDA build, TPU build, and GPU validation on on-demand
`LINUX_GPU_CONTAINER` (`GPU_COMPUTE_TYPE`, default SMALL = 1× A10G).
Optional inputs unlock more: arm64 GraalVM/JDK archive URLs → `linux-arm64`
(image cross-built with buildx/QEMU); Android cmdline-tools URL + NDK version
→ android targets; a licensed `HEXAGON_SDK_ARCHIVE_URL` → hexagon build.

**Reserved fleet lanes (billed while provisioned — see Costs):**

- **macOS** (`MAC_ARM`): set `MACOS_FLEET_COMPUTE=BUILD_GENERAL1_MEDIUM`
  (M2 24 GB) or `_LARGE` (M2 32 GB). Regions: us-east-1/2, us-west-2,
  ap-southeast-2 (+ eu-central-1 MEDIUM). For a custom AMI run
  `images/macos/bootstrap.sh` on a mac2 dedicated host and set
  `MACOS_AMI_ID`; otherwise the curated macOS image is used.
- **Windows / Windows CUDA** (`WINDOWS_EC2`): bake a toolchain AMI —
  `scripts/aws/bake-ami.sh CONFIG windows` (add `--cuda` with
  `WINDOWS_CUDA_INSTALLER_URLS` set) — then set `WINDOWS_INSTANCE_TYPE` +
  `WINDOWS_AMI_ID` (and the `WINDOWS_CUDA_*` pair) and re-provision.
  Alternatively supply a container image built on a Windows Docker host via
  `WINDOWS_IMAGE` (Windows containers cannot be built from Linux).
- **AMD GPU smokes** (`LINUX_EC2`): bake a driver AMI on a GPU instance —
  `scripts/aws/bake-ami.sh CONFIG linux-accelerator --base-ami <ubuntu-ami>
  --instance-type g4ad.xlarge --sdk-script my-rocm-install.sh --kind amd-rocm`
  — then set `AMD_GPU_INSTANCE_TYPE` + `AMD_GPU_AMI_ID`. Custom AMIs are only
  supported on MAC_ARM/LINUX_EC2/ARM_EC2/WINDOWS_EC2 fleets; bake-ami grants
  the CodeBuild organization launch permission automatically.
- **TPU / Hexagon smokes**: need hardware AWS does not sell; they stay
  skipped unless you point `TPU_HW_FLEET_ARN`/`HEXAGON_HW_FLEET_ARN` at
  self-managed fleets. The *build* targets for both run fine on Linux.

## Costs

On-demand lanes bill per build minute only. Reserved fleets bill for
provisioned capacity **continuously**, and macOS capacity has a 24-hour
minimum — enable fleet lanes only while you use them and remove them with:

```bash
scripts/aws/teardown.sh CONFIG --fleets
```

`teardown.sh CONFIG` deletes stacks; `--images`, `--buckets --yes`, `--iam`,
`--all` remove the rest. GraalVM native-image is why Linux defaults to
`BUILD_GENERAL1_2XLARGE` (144 GiB / 72 vCPU) — trim `LINUX_COMPUTE_TYPE` for
cheaper DL4J-only lanes.

## Development

- `scripts/aws/dryrun-test.sh` — offline regression check: fakes the aws CLI
  and asserts all 33 targets deploy or skip cleanly with correct parameters.
  Run it after touching `targets.yml`, `hosts.sh`, or deploy scripts.
- `targets.yml` is parsed by `scripts/aws/targets-lib.sh` and must stay in
  strict one-line inline-map form.
- `run-build.ps1`/`publish-dist.ps1` have not been parsed locally (no pwsh
  on this machine); validate on first Windows run.
- Windows kompile dists default off (`kompile: false`): `build-dist.sh`
  under git-bash on Windows is unproven. Flip per target once verified.
