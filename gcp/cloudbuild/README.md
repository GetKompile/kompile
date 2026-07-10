# GCP Cloud Build: DL4J TPU lanes

The GCP twin of `aws/codebuild/`, scoped to what AWS cannot host: **real TPU
hardware**. Two lanes:

- **build** — compiles DL4J's `nd4j-tpu` backend from source on Cloud Build
  (plus the kompile `cli-only` distribution), reusing the AWS in-build
  scripts (`aws/codebuild/scripts/run-build.sh`) and the same builder
  Dockerfile (`aws/codebuild/images/tpu`) via CodeBuild-compatible env shims,
  so the two clouds cannot drift.
- **smoke** — creates a real Cloud TPU VM (`v5litepod-1` by default), builds
  and runs the `nd4j-tpu` platform tests ON it against libtpu/PJRT, uploads
  the log to GCS, and **always deletes the TPU VM** (trap on exit — TPU time
  is the expensive part). This backfills the AWS kit's permanently-skipped
  `tpu-smoke-linux` target.

## Quickstart

```bash
gcp/cloudbuild/scripts/setup-wizard.sh
```

Walks through gcloud auth (offers `gcloud auth login`), project/region, TPU
zone + accelerator, DL4J source, toolchain archives (HEAD-validated working
defaults), optional GitHub Release publishing (token → Secret Manager only),
then offers to provision and submit builds. Manual equivalent:

```bash
cp gcp/cloudbuild/parameters.env.example ~/.config/kompile-cloudbuild-gcp.env
# fill the REQUIRED block
gcp/cloudbuild/scripts/provision.sh ~/.config/kompile-cloudbuild-gcp.env
gcp/cloudbuild/scripts/start-build.sh ~/.config/kompile-cloudbuild-gcp.env build [DL4J_REF] [RELEASE_TAG]
gcp/cloudbuild/scripts/start-build.sh ~/.config/kompile-cloudbuild-gcp.env smoke [DL4J_REF] [RELEASE_TAG]
```

`provision.sh` enables the APIs, creates the Artifact Registry repository,
artifact bucket, and build service account (TPU admin + bucket-scoped
storage + AR read + secret access), and builds the builder image. Everything
is idempotent; derived values land in `generated-cloudbuild-gcp.env` beside
your config.

Builds are submitted **from your working tree** (`gcloud builds submit`), so
no GitHub App connection or webhook setup is required — anyone with a clone
and a GCP project can run this. `.gitignore`d paths are excluded from the
upload.

## Publishing

- **GCS (always)**: `gs://<artifact-bucket>/releases/<tag-or-sha>/…` for the
  build lane; smoke logs land under `…/tpu-smoke/`.
- **GitHub Releases (opt-in)**: same mechanism as the AWS kit
  (`publish-dist.sh` — cloud-agnostic curl/python half); set
  `GITHUB_RELEASE_REPO` + `GITHUB_RELEASE_TOKEN_SECRET` and submit with a
  `RELEASE_TAG`.

## TPU specifics and costs

- Set `TPU_ZONE` to a zone that actually offers your accelerator:
  `gcloud compute tpus accelerator-types list --zone <zone>` — preflight
  warns if the type is not describable there. TPU quota is per zone and
  often requires a quota request.
- The smoke lane bills TPU time from create to delete; `TPU_SMOKE_TIMEOUT`
  (default 2h) hard-caps a run, and the VM is deleted even on failure.
  If a Cloud Build worker is killed outright, the trap cannot run — that is
  why teardown's **default** action is the TPU janitor:

```bash
gcp/cloudbuild/scripts/teardown.sh CONFIG            # delete stray kompile-tpu-* VMs
gcp/cloudbuild/scripts/setup-wizard.sh --teardown    # interactive checklist
```

- Other flags: `--images` (AR repo), `--buckets --yes` (all releases),
  `--secrets --yes` (GCP secrets have NO recovery window), `--iam` (service
  account), `--pool`, `--all`.
- The build lane defaults to `E2_HIGHCPU_32` (32 vCPU / 32 GB). GraalVM
  native-image with more RAM needs a private worker pool: create one and set
  `WORKER_POOL=projects/<p>/locations/<region>/workerPools/<name>`.

## Development

- `scripts/dryrun-test.sh` — offline regression check with a fake gcloud:
  provision/start/teardown/wizard flows, TPU janitor included.
- `start-build.sh` injects `serviceAccount`, machine type / worker pool, and
  the optional release secret into the checked-in Cloud Build configs at
  submit time (python3 + PyYAML required on the operator machine).
- Not yet exercised against a real GCP project; the first live
  `provision.sh` + smoke run is the remaining test. libtpu discovery on the
  TPU VM tries the preinstalled paths first, then falls back to the
  `libtpu` pip package.
