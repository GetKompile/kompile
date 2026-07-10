#!/usr/bin/env bash
# Bake a reserved-fleet AMI end to end: launch an SSM-managed instance from a
# base AMI, run the appropriate bootstrap payload, snapshot, grant CodeBuild's
# organization launch permission, terminate. Prints the AMI id and the config
# key to set.
#
#   bake-ami.sh CONFIG windows            [--cuda] [--base-ami ami-...] [--instance-type c5.2xlarge]
#   bake-ami.sh CONFIG linux-accelerator  --base-ami ami-... [--sdk-script path] [--kind amd-rocm|zluda|nvidia|tpu-pjrt|hexagon]
#                                         [--instance-type g4ad.xlarge] [--volume-gb 200]
#                                         [--subnet-id subnet-...] [--security-group-id sg-...]
#
# windows: installs the toolchain via images/windows/bootstrap.ps1 (choco,
#   GraalVM/JDK zips, optional WINDOWS_CUDA_INSTALLER_URLS with --cuda).
# linux-accelerator: installs the toolchain via images/linux-ec2/bootstrap.sh,
#   then your --sdk-script (vendor drivers/SDKs), then validates with
#   images/accelerators/bootstrap.sh when --kind is given.
# macOS AMIs are not baked here: mac2 instances need a dedicated host with a
# 24h minimum; run images/macos/bootstrap.sh on one manually instead.
set -euo pipefail
root="$(cd "$(dirname "$0")/../../../.." && pwd)"
config="${1:?Usage: bake-ami.sh CONFIG windows|linux-accelerator [options]}"
mode="${2:?Mode required: windows | linux-accelerator}"
shift 2
# shellcheck disable=SC1090
source "$config"
: "${AWS_REGION:?}" "${GRAALVM_ARCHIVE_URL:?}" "${JDK11_ARCHIVE_URL:?}"

base_ami="" instance_type="" volume_gb=200 sdk_script="" kind="" with_cuda=0
subnet_id="" security_group_id="" name_suffix=""
while [ $# -gt 0 ]; do
  case "$1" in
    --base-ami) base_ami="$2"; shift 2 ;;
    --instance-type) instance_type="$2"; shift 2 ;;
    --volume-gb) volume_gb="$2"; shift 2 ;;
    --sdk-script) sdk_script="$2"; shift 2 ;;
    --kind) kind="$2"; shift 2 ;;
    --cuda) with_cuda=1; shift ;;
    --subnet-id) subnet_id="$2"; shift 2 ;;
    --security-group-id) security_group_id="$2"; shift 2 ;;
    --name) name_suffix="-$2"; shift 2 ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done

case "$mode" in
  windows)
    instance_type="${instance_type:-c5.2xlarge}"
    if [ -z "$base_ami" ]; then
      base_ami="$(aws ssm get-parameter --region "$AWS_REGION" \
        --name /aws/service/ami-windows-latest/Windows_Server-2022-English-Full-Base \
        --query Parameter.Value --output text)"
    fi
    ssm_doc=AWS-RunPowerShellScript ;;
  linux-accelerator)
    instance_type="${instance_type:-c5.4xlarge}"
    [ -n "$base_ami" ] || { echo "--base-ami is required for linux-accelerator" >&2; exit 2; }
    ssm_doc=AWS-RunShellScript ;;
  *) echo "Unknown mode: $mode" >&2; exit 2 ;;
esac

prefix="${PROJECT_PREFIX:-kompile}"
log() { echo "bake-ami: $*" >&2; }

# --- IAM instance profile with SSM access (idempotent) ---------------------
baker="$prefix-ami-baker"
if ! aws iam get-role --role-name "$baker" >/dev/null 2>&1; then
  log "creating baker role/instance-profile $baker"
  aws iam create-role --role-name "$baker" --assume-role-policy-document '{
    "Version": "2012-10-17",
    "Statement": [{"Effect": "Allow", "Principal": {"Service": "ec2.amazonaws.com"},
                   "Action": "sts:AssumeRole"}]}' >/dev/null
  aws iam attach-role-policy --role-name "$baker" \
    --policy-arn arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore
  aws iam create-instance-profile --instance-profile-name "$baker" >/dev/null
  aws iam add-role-to-instance-profile --instance-profile-name "$baker" --role-name "$baker"
  sleep 12  # IAM propagation before run-instances accepts the profile
fi

# --- Launch -----------------------------------------------------------------
root_device="$(aws ec2 describe-images --region "$AWS_REGION" --image-ids "$base_ami" \
  --query 'Images[0].RootDeviceName' --output text)"
run_args=(--region "$AWS_REGION" --image-id "$base_ami" --instance-type "$instance_type"
  --iam-instance-profile "Name=$baker" --count 1
  --block-device-mappings "[{\"DeviceName\":\"$root_device\",\"Ebs\":{\"VolumeSize\":$volume_gb,\"VolumeType\":\"gp3\"}}]"
  --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=$baker-$mode}]")
[ -n "$subnet_id" ] && run_args+=(--subnet-id "$subnet_id")
[ -n "$security_group_id" ] && run_args+=(--security-group-ids "$security_group_id")
instance_id="$(aws ec2 run-instances "${run_args[@]}" --query 'Instances[0].InstanceId' --output text)"
log "launched $instance_id ($instance_type from $base_ami)"
trap 'echo "bake-ami: FAILED — instance $instance_id left running for debugging; terminate it manually." >&2' ERR

aws ec2 wait instance-running --region "$AWS_REGION" --instance-ids "$instance_id"
log "waiting for SSM agent (Windows first boot can take ~10 minutes)"
for _ in $(seq 1 80); do
  ping="$(aws ssm describe-instance-information --region "$AWS_REGION" \
    --filters "Key=InstanceIds,Values=$instance_id" \
    --query 'InstanceInformationList[0].PingStatus' --output text 2>/dev/null || true)"
  [ "$ping" = Online ] && break
  sleep 15
done
[ "$ping" = Online ] || { echo "SSM agent never came online on $instance_id" >&2; exit 1; }

# --- Compose + send the bootstrap payload -----------------------------------
payload="$(mktemp)"
if [ "$mode" = windows ]; then
  {
    printf '$env:GRAALVM_ARCHIVE_URL = "%s"\n' "${WINDOWS_GRAALVM_ARCHIVE_URL:-$GRAALVM_ARCHIVE_URL}"
    printf '$env:JDK11_ARCHIVE_URL = "%s"\n' "${WINDOWS_JDK11_ARCHIVE_URL:-$JDK11_ARCHIVE_URL}"
    if [ "$with_cuda" = 1 ]; then
      printf '$env:WINDOWS_CUDA_INSTALLER_URLS = "%s"\n' "${WINDOWS_CUDA_INSTALLER_URLS:?--cuda needs WINDOWS_CUDA_INSTALLER_URLS in the config}"
    fi
    cat "$root/aws/codebuild/images/windows/bootstrap.ps1"
  } > "$payload"
else
  {
    printf 'export GRAALVM_ARCHIVE_URL=%q JDK11_ARCHIVE_URL=%q\n' "$GRAALVM_ARCHIVE_URL" "$JDK11_ARCHIVE_URL"
    printf 'export ROCM_HOME=%q ZLUDA_HOME=%q PJRT_LIBRARY_PATH=%q HEXAGON_SDK_ROOT=%q\n' \
      "${ROCM_HOME:-/opt/rocm}" "${ZLUDA_HOME:-/opt/zluda}" "${PJRT_LIBRARY_PATH:-}" "${HEXAGON_SDK_ROOT:-/opt/hexagon}"
    printf 'export JAVA11_HOME=/opt/jdk11 GRAALVM_HOME=/opt/graalvm\n'
    cat "$root/aws/codebuild/images/linux-ec2/bootstrap.sh"
    if [ -n "$sdk_script" ]; then cat "$sdk_script"; fi
    if [ -n "$kind" ]; then
      printf 'export ACCELERATOR_KIND=%q\n' "$kind"
      cat "$root/aws/codebuild/images/accelerators/bootstrap.sh"
    fi
  } > "$payload"
fi
params="$(mktemp)"
python3 - "$payload" > "$params" <<'PY'
import json, sys
lines = open(sys.argv[1]).read().splitlines()
print(json.dumps({"commands": lines, "executionTimeout": ["10800"]}))
PY
command_id="$(aws ssm send-command --region "$AWS_REGION" --document-name "$ssm_doc" \
  --instance-ids "$instance_id" --parameters "file://$params" \
  --query 'Command.CommandId' --output text)"
log "bootstrap running via SSM command $command_id"
status=Pending
for _ in $(seq 1 540); do
  status="$(aws ssm get-command-invocation --region "$AWS_REGION" \
    --command-id "$command_id" --instance-id "$instance_id" \
    --query Status --output text 2>/dev/null || echo Pending)"
  case "$status" in Success|Failed|TimedOut|Cancelled) break ;; esac
  sleep 20
done
if [ "$status" != Success ]; then
  aws ssm get-command-invocation --region "$AWS_REGION" --command-id "$command_id" \
    --instance-id "$instance_id" --query StandardErrorContent --output text >&2 || true
  echo "bake-ami: bootstrap ended with status $status" >&2
  exit 1
fi
rm -f "$payload" "$params"

# --- Snapshot ----------------------------------------------------------------
aws ec2 stop-instances --region "$AWS_REGION" --instance-ids "$instance_id" >/dev/null
aws ec2 wait instance-stopped --region "$AWS_REGION" --instance-ids "$instance_id"
ami_name="$prefix-$mode$name_suffix-$(date +%Y%m%d-%H%M%S)"
ami_id="$(aws ec2 create-image --region "$AWS_REGION" --instance-id "$instance_id" \
  --name "$ami_name" --query ImageId --output text)"
log "creating AMI $ami_id ($ami_name)"
for _ in $(seq 1 240); do
  state="$(aws ec2 describe-images --region "$AWS_REGION" --image-ids "$ami_id" \
    --query 'Images[0].State' --output text)"
  [ "$state" = available ] && break
  [ "$state" = failed ] && { echo "AMI creation failed" >&2; exit 1; }
  sleep 15
done

# CodeBuild fleets run in service-owned accounts: the AMI must allow the
# regional CodeBuild organization to launch it.
case "$AWS_REGION" in
  us-east-1)      org=arn:aws:organizations::851725618577:organization/o-c6wcu152r1 ;;
  us-east-2)      org=arn:aws:organizations::992382780434:organization/o-seufr2suvq ;;
  us-west-2)      org=arn:aws:organizations::381491982620:organization/o-0412o99a4r ;;
  ap-northeast-1) org=arn:aws:organizations::891376993293:organization/o-b6k3sjqavm ;;
  ap-south-1)     org=arn:aws:organizations::891376924779:organization/o-krtah1lkeg ;;
  ap-southeast-1) org=arn:aws:organizations::654654522137:organization/o-mcn8uvc3tp ;;
  ap-southeast-2) org=arn:aws:organizations::767398067170:organization/o-6crt0f6bu4 ;;
  eu-central-1)   org=arn:aws:organizations::590183817084:organization/o-lb2lne3te6 ;;
  eu-west-1)      org=arn:aws:organizations::891376938588:organization/o-ullrrg5qf0 ;;
  sa-east-1)      org=arn:aws:organizations::533267309133:organization/o-db63c45ozw ;;
  *) org="" ;;
esac
if [ -n "$org" ]; then
  aws ec2 modify-image-attribute --region "$AWS_REGION" --image-id "$ami_id" \
    --launch-permission "Add=[{OrganizationArn=$org}]"
else
  log "WARNING: no CodeBuild organization ARN known for $AWS_REGION — grant launch permission manually"
fi

aws ec2 terminate-instances --region "$AWS_REGION" --instance-ids "$instance_id" >/dev/null
trap - ERR
case "$mode$with_cuda" in
  windows0) key=WINDOWS_AMI_ID ;;
  windows1) key=WINDOWS_CUDA_AMI_ID ;;
  *) key="<AMD_GPU_AMI_ID or the matching *_AMI_ID>" ;;
esac
echo "AMI ready: $ami_id"
echo "Set in your config: $key=$ami_id (then rerun provision.sh)"
