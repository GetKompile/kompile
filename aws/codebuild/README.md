# AWS CodeBuild: DL4J → kompile build & distribution matrix

Builds deeplearning4j from source at any ref, then the kompile distribution,
for every non-release platform workflow under
`../deeplearning4j/.github/workflows` — 33 targets mapped in `targets.yml`.
Artifacts publish to S3 always, and to GitHub Releases when configured.

Both repositories are public: **anyone can fork/clone this and stand up the
full matrix in their own AWS account** with one wizard run — and optionally
hand provisioning itself to AWS (see "Fully in-cloud" below) so no local
machine is involved after setup.

## One-go quickstart

```bash
aws/codebuild/scripts/aws/setup-wizard.sh
```

The wizard walks through everything: AWS credentials (offers `aws configure`
if none work), region, kompile/DL4J clone URLs and refs (defaulted from your
git remotes), GraalVM 21 + JDK 11 archive URLs (working defaults offered and
HEAD-validated), GitHub tokens for private repos and release publishing
(pasted hidden, stored in **Secrets Manager only** — never in the config
file), target selection, compute size, and the optional macOS lane. It then
offers to run provisioning and start builds immediately. Rerun it any time:
existing config values become the prompt defaults and secrets can be rotated.

The manual equivalent:

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

## Fully in-cloud: the seed stack

The wizard's last step can deploy `seed.yml`: a private config bucket plus a
`kompile-provisioner` CodeBuild project (curated image, Docker enabled) that
runs `provision.sh` **inside AWS** against the uploaded config
(`buildspec-provision.yml`). The wizard uploads your config there; after
that, provisioning no longer needs your machine:

- Update config → rerun the wizard (it re-uploads), or
  `aws s3 cp <config> s3://<seed-bucket>/parameters.env` and start the
  `kompile-provisioner` project.
- With the seed webhook enabled, any push touching `aws/codebuild/` on your
  branch re-provisions automatically — GitOps for the build farm. Webhook
  creation requires GitHub credentials with `admin:repo_hook` on the source
  repo (your fork), imported via `set-github-token.sh`; without them, start
  the provisioner manually.
- The provisioner role is powerful by design — it creates IAM roles, stacks,
  fleets, images, and buckets, scoped to `kompile-*` resource names. Review
  `seed.yml` before deploying into a shared account. Custom bucket/secret
  names outside the prefix need policy edits.
- AMI baking (Windows/AMD lanes) stays operator-run: the seed role
  deliberately has no EC2 permissions.
- Remove with `scripts/aws/teardown.sh CONFIG --seed`.

## Auto-starting builds

Set `WEBHOOK_BRANCH=<branch>` in the config to put a push webhook on every
deployed target project (same `admin:repo_hook` requirement — works on your
fork). Preflight warns if it is set without GitHub credentials. Note: DL4J is
a secondary in-build clone, so DL4J pushes cannot trigger these projects
directly — use a schedule or a small GitHub Action in your DL4J fork that
calls `start-build` with the pushed ref.

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
  **TPU hardware smokes have a real home in `gcp/cloudbuild/`** — a Cloud
  Build + Cloud TPU VM lane that reuses this kit's builder image and
  in-build scripts.

## Simulate before you spend

Three dry-run layers, cheapest to highest fidelity:

1. **Offline logic check** — `scripts/aws/dryrun-test.sh`: fake aws CLI, no
   credentials, validates the whole deploy/teardown/wizard wiring. Also runs
   `cfn-lint` on both templates when installed (`pip install cfn-lint`) —
   real CloudFormation spec validation of every property and enum.
2. **Plan mode against your real account (read-only)** —
   `scripts/aws/provision.sh CONFIG build --plan`: validates both templates
   server-side, reports exists / WOULD-CREATE for every account resource
   (buckets, ECR, roles, secrets, images, fleets), then creates —
   **but never executes** — a CloudFormation change set per target, so AWS
   itself validates all 34+ parameters. Transient change sets and new-stack
   review shells are cleaned up; nothing is created.
3. **Simulate the actual build locally** —
   `scripts/simulate-build.sh CONFIG TARGET [DL4J_REF]`: runs the SAME
   builder image and the SAME `run-build.sh` Docker-side against the
   committed tree — the real DL4J + kompile build, artifacts in a local
   `dist/`, zero cloud minutes. Both clouds funnel through `run-build.sh`,
   so a passing simulation is meaningful for GCP too. Linux-container lanes
   only; GPU validation targets get `--gpus all` automatically when the host
   has NVIDIA container support. Maven/ccache state persists in the
   `kompile-sim-m2`/`kompile-sim-ccache` Docker volumes; `--shell` drops you
   into the container for debugging; `--rebuild-image` refreshes the local
   `kompile-sim:<family>` image.

## Costs

On-demand lanes bill per build minute only. Reserved fleets bill for
provisioned capacity **continuously**, and macOS capacity has a 24-hour
minimum — enable fleet lanes only while you use them and remove them with
`scripts/aws/teardown.sh CONFIG --fleets`. GraalVM native-image is why Linux
defaults to `BUILD_GENERAL1_2XLARGE` (144 GiB / 72 vCPU) — trim
`LINUX_COMPUTE_TYPE` for cheaper DL4J-only lanes.

## Teardown

Interactive (mirrors setup — a checklist with confirmations):

```bash
aws/codebuild/scripts/aws/setup-wizard.sh --teardown [CONFIG]
```

Scripted: `teardown.sh CONFIG [flags]`, default `--stacks`. Deletions wait
for completion.

- `--stacks` all target project stacks · `--fleets` reserved fleets ·
  `--seed` the in-cloud provisioner stack + its config bucket ·
  `--amis` baked `kompile-windows-*`/`kompile-linux-accelerator-*` AMIs and
  snapshots · `--images` the ECR repository · `--secrets` token secrets
  (30-day recovery window; immediate with `--yes`) · `--buckets` artifact +
  cache buckets (requires `--yes`; deletes all releases) · `--iam` fleet
  service role + AMI baker role.
- `--all` = everything above. It deliberately does **not** include
  `--source-credentials` (the account-wide CodeBuild GitHub credential —
  other projects in the account may depend on it); pass that flag explicitly.

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
