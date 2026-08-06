# Kompile external AWS release builders

This is the non-GitHub-hosted equivalent of Kompile's release and native build workflows. It uses the standard boto3 credential/region chain, resolves both Kompile and deeplearning4j branches to immutable commits before provisioning, and runs one reusable platform/toolchain lane at a time. Every lane runs its classifiers serially on the same host and Maven/ccache directories. Live controller, EC2 health, console, phase, and build events are streamed while the job runs; verified outputs are staged in private S3. GitHub credentials never leave GitHub Actions.

## Scope

`release-plan.json` covers every canonical `build-dist.sh` variant (`cli-only`, `full`, `hosted`, `cpu-intel`, `cpu-arm`, `cuda`, and `amd-zluda`), the real seven CPU classifiers on Linux and Windows, Linux ARM64, CUDA 12.6 and 12.9 on Linux and Windows, all five Kompile GraalVM native CLI targets, one consolidated macOS ARM64 lane, and the Android debug APK built by the active GitHub workflow. Native SDK work delegates to the selected deeplearning4j commit's own `release/aws/build-platform.py`; it does not reimplement those Maven commands. Distribution builds resolve GraalVM Community JDK 21 from the official `graalvm-ce-builds` tag family used by `release.yml`; the five standalone native CLI builds use Oracle GraalVM JDK 21 from the same official `download.oracle.com` `latest` channel used by the native GitHub workflows. Both downloads verify the publisher's SHA-256 before extraction.

CUDA and ZLUDA artifacts are compiled on CPU-only `c7i` instances using toolchain containers/installers; no GPU instance is requested. macOS uses one `mac2-m2pro.metal` dedicated host because EC2 macOS requires Apple hardware, not because the Metal accelerator is required. The EC2 Mac 24-hour minimum allocation applies. Kompile's mobile Vulkan/Hexagon/Tensor profiles are offline developer tooling and are not release artifacts in the current GitHub Actions workflow; this external path intentionally builds the same debug APK rather than inventing a different release matrix.

## Local setup

Use Python 3.11+ and install boto3:

```bash
python3 -m pip install --user boto3
```

Set normal AWS SDK variables. There are no Kompile-specific credential variables:

```bash
export AWS_ACCESS_KEY_ID=...        # AWS user access key ID
export AWS_SECRET_ACCESS_KEY=...    # AWS user secret access key
export AWS_SESSION_TOKEN=...        # only for temporary credentials
export AWS_REGION=us-east-1
export AWS_DEFAULT_REGION=us-east-1
```

A valid standard SDK profile/role/SSO chain also works. When configuration is missing in an interactive terminal, `configure`, `preflight`, and `start` prompt once for the exact values of `AWS_REGION`/`AWS_DEFAULT_REGION`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, and optional `AWS_SESSION_TOKEN`. Rejected values exit with an actionable error; they do not loop. Use global `--no-wizard` in CI.

AWS's console label “Global” is not an EC2 region. Pick a real region that offers every selected type, especially `mac2-m2pro.metal`; `preflight` verifies instance types, regional offerings, independently verified AMIs, quotas, credentials, the serial schedule, and network/AZ compatibility without launching anything.

## Commands

Run from the Kompile repository root:

```bash
python3 release/aws/release.py configure
python3 release/aws/release.py preflight --max-cores 96
python3 release/aws/release.py start --branch main --dl4j-branch master \
  --version 1.2.3 --max-cores 96 --reset-kill-switch
python3 release/aws/release.py start --commit <kompile-sha> --dl4j-commit <dl4j-sha> \
  --version 1.2.4-SNAPSHOT --max-cores 96 --reset-kill-switch
python3 release/aws/release.py status --run-id <run-id>
python3 release/aws/release.py logs --run-id <run-id> --follow
python3 release/aws/release.py logs --run-id <run-id> --shard native-linux-x86_64--base
```

`--max-cores` greedily chooses the largest compatible instance in each lane's family that is offered in the region and fits the constraint, then caps build threads and heap from the actual vCPU/memory values. If a constraint cannot be satisfied, preflight fails before any mutable AWS operation. `--instance-type` and `--build-threads` are explicit smoke-test overrides.

`start` creates or reuses an encrypted, private, versioned bucket named `kompile-release-<account>-<region>`, a least-privilege instance role/profile, the CloudWatch log group, and the global SSM kill switch. It prints a run event with exact status, log, and shutdown commands, launches one lane, streams readiness/build events until its status object is available, terminates it, and only then launches the next lane. Variants inside a lane share Maven and ccache state.

To build Kompile without compiling DL4J, provide the anonymous-read Maven
repository and the matching DL4J SDK asset shard:

```bash
python3 release/aws/release.py start --branch main --dl4j-commit <dl4j-sha> \
  --version 0.1.0-SNAPSHOT --snapshot-version 1.0.0-SNAPSHOT \
  --dl4j-maven-repository-url https://repo.example/maven2 \
  --dl4j-sdk-assets-url 'https://downloads.example/sdk-assets-{lane}.tar.gz' \
  --reset-kill-switch
```

The SDK URL supports `{lane}`, `{variant}`, `{platform}`, `{version}`,
and `{snapshotVersion}`. Every archive must have an adjacent
`<url>.sha256` sidecar. Backend distributions fail before assembly unless
the verified shard contains both runtime ZIP/AAR packages and `jars/*.jar`.
The completed Kompile ZIP is then staged both as a direct GitHub release asset
and as a classified `ai.kompile:kompile-dist` artifact in the same Maven
publication repository.

Repeat `--shard` with a parent lane or an exact `parent--variant` selector:

```bash
python3 release/aws/release.py preflight --shard distribution-linux-x86_64
python3 release/aws/release.py start --branch feature/x --dl4j-branch release/x \
  --version 1.2.3-SNAPSHOT --shard native-linux-x86_64--avx2 \
  --shard android-chat-local --max-cores 32 --reset-kill-switch
```

## Emergency stop and cleanup

This command is deliberately global: it first flips the SSM kill switch, then terminates every tagged Kompile builder and releases every tagged EC2 Mac dedicated host. Workers poll every 15 seconds and kill the complete build process tree, so it works mid-build.

```bash
python3 release/aws/release.py stop-everything --wait
python3 release/aws/release.py stop-everything --wait --purge-storage --purge-logs
```

Logs have 30-day CloudWatch retention and can be permanently deleted independently. Deletion covers CloudWatch streams plus every S3 version and delete marker for the matching `build.log` objects:

```bash
python3 release/aws/release.py delete-logs --run-id <run-id> --yes
python3 release/aws/release.py delete-logs --run-id <run-id> --shard native-linux-x86_64--base --yes
python3 release/aws/release.py delete-logs --all-runs --yes
```

`--purge-storage` permanently removes all versions from the managed staging bucket after builders are stopped. `--purge-logs` permanently deletes all Kompile release CloudWatch streams and staged S3 build-log versions. The kill switch remains enabled until the next explicit `start --reset-kill-switch`.

## GitHub handoff

Workers never receive `GITHUB_TOKEN`, Maven credentials, signing keys, or release permissions. The canonical `release.yml` workflow remains the only authority that creates a GitHub release. The reusable native/SDK/distribution actions only emit workflow artifacts; `publish-release.yml` and `Attach external AWS release assets` may add supplemental assets only after the canonical release exists.

For the external handoff, configure the protected `release` environment with secrets named exactly `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, and optional `AWS_SESSION_TOKEN`. The workflow maps those standard SDK variables plus the selected region, and uses GitHub's injected `github.token` as `GH_TOKEN`. It verifies S3 checksums and identities before attaching assets.

The collector never creates, edits, publishes, or overwrites a release. Uploads are idempotent: an existing asset is accepted only when its SHA-256 is identical; a conflicting name fails. Each run gets uniquely named manifest/checksum assets. `build.log` stays in the purgeable S3/CloudWatch logging path and is never copied into GitHub Release assets.

S3 layout is:

```
s3://<bucket>/kompile/releases/<run-id>/<shard>/
  build.log
  status.json
  shard-manifest.json
  maven-repository.tar.gz
  sdk-assets.tar.gz
```

Native SDK lanes contain a normal Maven repository tree under `maven-repository.tar.gz`; distribution/native executable/APK and SDX runtime SDK outputs are under `sdk-assets.tar.gz`. The collector verifies each worker's `artifacts.json`, surfaces every ZIP and checksum as a direct release asset, and rejects a distribution shard without a `kompile-dist-*.zip`. Collection also materializes a valid exploded Maven 2 test repository at `s3://<bucket>/kompile/releases/<run-id>/maven-repository/` and writes `.kompile/complete.json` last as its readiness marker. S3 is staging and test storage, not a public release channel.
