#!/usr/bin/env bash
# Assemble an intact canonical DL4J AOT SDK plus Kompile-owned reasoning artifacts.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

: "${SDX_SDK_MANIFEST:?SDX_SDK_MANIFEST must point to the canonical release sdx-sdk-manifest.json}"
: "${SDX_SDK_ARTIFACT:?SDX_SDK_ARTIFACT must point to the downloaded manifest-selected AOT archive}"
SDX_SDK_MANIFEST_CHECKSUM="${SDX_SDK_MANIFEST}.sha256"
if [[ ! -f "${SDX_SDK_MANIFEST}" || ! -f "${SDX_SDK_MANIFEST_CHECKSUM}" ]]; then
    echo "Canonical manifest and checksum sidecar are required: ${SDX_SDK_MANIFEST}{,.sha256}" >&2
    exit 1
fi
if [[ ! -f "${SDX_SDK_ARTIFACT}" ]]; then
    echo "Missing canonical AOT archive: ${SDX_SDK_ARTIFACT}" >&2
    exit 1
fi

SDX_PLATFORM="${SDX_SDK_PLATFORM:-linux-x86_64}"
SDX_VARIANT="${SDX_SDK_VARIANT:-cpu}"
IFS=$'\t' read -r UPSTREAM_VERSION UPSTREAM_TAG SELECTED_CLASSIFIER SELECTED_FILE < <(
python3 - "${SDX_SDK_MANIFEST}" "${SDX_SDK_MANIFEST_CHECKSUM}" \
    "${SDX_SDK_ARTIFACT}" "${SDX_PLATFORM}" "${SDX_VARIANT}" <<'PY'
import hashlib
import json
import pathlib
import re
import sys

manifest_path, sidecar_path, artifact_path = map(pathlib.Path, sys.argv[1:4])
platform, variant = sys.argv[4:6]
manifest_bytes = manifest_path.read_bytes()
manifest_sha = hashlib.sha256(manifest_bytes).hexdigest()
sidecar = sidecar_path.read_text(encoding='utf-8')
match = re.fullmatch(r'([0-9a-fA-F]{64})[ \t]+\*?sdx-sdk-manifest\.json\s*', sidecar)
if not match or match.group(1).lower() != manifest_sha:
    raise SystemExit('Canonical SDX manifest checksum sidecar is invalid')

manifest = json.loads(manifest_bytes)
if manifest.get('schemaVersion') != 1:
    raise SystemExit('Unsupported SDX manifest schemaVersion')
version = manifest.get('releaseVersion')
tag = manifest.get('releaseTag')
token = re.compile(r'[A-Za-z0-9][A-Za-z0-9._+-]*')
if not isinstance(version, str) or not token.fullmatch(version) or tag != f'sdk-v{version}':
    raise SystemExit('Invalid SDX manifest release identity')
if not token.fullmatch(platform) or not token.fullmatch(variant):
    raise SystemExit('Requested SDX platform and variant must be safe tokens')
matches = [entry for entry in manifest.get('artifacts', [])
           if entry.get('component') == 'aot'
           and entry.get('packageRole') == 'aot-sdk'
           and entry.get('platform') == platform
           and entry.get('variant') == variant]
if len(matches) != 1:
    raise SystemExit(
        f'Expected exactly one aot/aot-sdk/{platform}/{variant} artifact; found {len(matches)}')
selected = matches[0]
file_name = selected.get('fileName')
if (not isinstance(file_name, str) or pathlib.Path(file_name).name != file_name
        or not token.fullmatch(file_name)):
    raise SystemExit('Selected SDX artifact has an unsafe fileName')
if selected.get('packaging') != 'zip':
    raise SystemExit('Selected SDX AOT artifact must use zip packaging')
if artifact_path.name != file_name:
    raise SystemExit(f'SDX_SDK_ARTIFACT must be the manifest fileName {file_name!r}')
expected_size = selected.get('size')
if not isinstance(expected_size, int) or expected_size <= 0 or artifact_path.stat().st_size != expected_size:
    raise SystemExit('Selected SDX artifact size does not match the manifest')
digest = hashlib.sha256()
with artifact_path.open('rb') as stream:
    for chunk in iter(lambda: stream.read(1024 * 1024), b''):
        digest.update(chunk)
if digest.hexdigest() != selected.get('sha256', '').lower():
    raise SystemExit('Selected SDX artifact SHA-256 does not match the manifest')
classifier = selected.get('classifier')
if classifier != f'{platform}-{variant}' or not token.fullmatch(classifier):
    raise SystemExit('Selected SDX artifact classifier is inconsistent')
print(version, tag, classifier, file_name, sep='\t')
PY
)

: "${KGR_LIB_SRC:?KGR_LIB_SRC must explicitly identify the Kompile reasoning library to package}"
KGR_SRC="${KGR_LIB_SRC}"
if [[ ! -f "${KGR_SRC}" ]]; then
    echo "Missing Kompile reasoning library: ${KGR_SRC}" >&2
    exit 1
fi
if [[ ! -f include/kompile_reasoning.h ]]; then
    echo "Missing Kompile reasoning header: include/kompile_reasoning.h" >&2
    exit 1
fi

KGR_ABI_VERSION=$(grep '#define KGR_ABI_VERSION' include/kompile_reasoning.h | awk '{print $3}' | tr -d '[:space:]')
KOMPILE_VERSION=$(python3 -c "import json,re; value=json.load(open('manifest.json'))['kompileVersion']; assert isinstance(value,str) and re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._+-]*',value), 'unsafe kompileVersion'; print(value)")
PLATFORM="${SELECTED_CLASSIFIER}"
ZIP_NAME="kompile-local-sdk-${KOMPILE_VERSION}-${PLATFORM}.zip"
ROOT_NAME="${ZIP_NAME%.zip}"
OUTPUT_DIR="${KOMPILE_SDK_OUTPUT_DIR:-target}"
mkdir -p "${OUTPUT_DIR}/stage"
OUTPUT_PARENT="$(cd "${OUTPUT_DIR}" && pwd -P)"
STAGE_PARENT="${OUTPUT_PARENT}/stage"
FINAL_STAGE_DIR="${STAGE_PARENT}/${ROOT_NAME}"
FINAL_STAGE_BACKUP="${FINAL_STAGE_DIR}.backup"
TARGET_ZIP="${OUTPUT_PARENT}/${ZIP_NAME}"
TARGET_ZIP_BACKUP="${TARGET_ZIP}.backup"
PUBLICATION_TRANSACTION="${OUTPUT_PARENT}/.${ROOT_NAME}.transaction"
WORK_PARENT="$(mktemp -d "${STAGE_PARENT}/.${ROOT_NAME}.work.XXXXXXXX")"
WORK_STAGE_DIR="${WORK_PARENT}/${ROOT_NAME}"
mkdir "${WORK_STAGE_DIR}"
WORK_ZIP=""
STAGE_DIR="${WORK_STAGE_DIR}"
rollback_publication() {
    local previous_stage previous_zip extra
    if [[ ! -f "${PUBLICATION_TRANSACTION}" ]]; then
        return 0
    fi
    if ! IFS=$'\t' read -r previous_stage previous_zip extra < "${PUBLICATION_TRANSACTION}" \
            || [[ ! "${previous_stage}" =~ ^[01]$ || ! "${previous_zip}" =~ ^[01]$ || -n "${extra:-}" ]]; then
        echo "Invalid SDK publication transaction record: ${PUBLICATION_TRANSACTION}" >&2
        return 1
    fi

    if [[ -e "${FINAL_STAGE_BACKUP}" ]]; then
        rm -rf -- "${FINAL_STAGE_DIR}"
        mv "${FINAL_STAGE_BACKUP}" "${FINAL_STAGE_DIR}" || return 1
    elif [[ "${previous_stage}" == "0" ]]; then
        rm -rf -- "${FINAL_STAGE_DIR}"
    elif [[ ! -d "${FINAL_STAGE_DIR}" ]]; then
        echo "Unable to recover the previous SDK stage" >&2
        return 1
    fi

    if [[ -e "${TARGET_ZIP_BACKUP}" ]]; then
        rm -f -- "${TARGET_ZIP}"
        mv "${TARGET_ZIP_BACKUP}" "${TARGET_ZIP}" || return 1
    elif [[ "${previous_zip}" == "0" ]]; then
        rm -f -- "${TARGET_ZIP}"
    elif [[ ! -f "${TARGET_ZIP}" ]]; then
        echo "Unable to recover the previous SDK archive" >&2
        return 1
    fi
}
cleanup() {
    local status=$?
    trap - EXIT HUP INT TERM
    if [[ -e "${PUBLICATION_TRANSACTION:-}" ]]; then
        if rollback_publication; then
            rm -f -- "${PUBLICATION_TRANSACTION}"
        else
            echo "SDK publication recovery remains pending: ${PUBLICATION_TRANSACTION}" >&2
        fi
    fi
    if [[ -n "${WORK_ZIP:-}" ]]; then
        rm -f -- "${WORK_ZIP}"
    fi
    if [[ -n "${WORK_PARENT:-}" ]]; then
        rm -rf -- "${WORK_PARENT}"
    fi
    return "${status}"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

for protected_path in "${FINAL_STAGE_DIR}" "${FINAL_STAGE_BACKUP}" \
        "${TARGET_ZIP}" "${TARGET_ZIP_BACKUP}" "${PUBLICATION_TRANSACTION}"; do
    if [[ -L "${protected_path}" ]]; then
        echo "Refusing symbolic-link SDK publication path: ${protected_path}" >&2
        exit 1
    fi
done
if [[ -e "${PUBLICATION_TRANSACTION}" && ! -f "${PUBLICATION_TRANSACTION}" ]]; then
    echo "Unsafe SDK publication transaction record: ${PUBLICATION_TRANSACTION}" >&2
    exit 1
fi
if [[ -e "${FINAL_STAGE_BACKUP}" && ! -d "${FINAL_STAGE_BACKUP}" ]]; then
    echo "Unsafe SDK stage backup: ${FINAL_STAGE_BACKUP}" >&2
    exit 1
fi
if [[ -e "${TARGET_ZIP_BACKUP}" && ! -f "${TARGET_ZIP_BACKUP}" ]]; then
    echo "Unsafe SDK archive backup: ${TARGET_ZIP_BACKUP}" >&2
    exit 1
fi
if [[ -e "${PUBLICATION_TRANSACTION}" ]]; then
    if rollback_publication; then
        rm -f -- "${PUBLICATION_TRANSACTION}"
    else
        echo "Unable to recover the interrupted SDK publication" >&2
        exit 1
    fi
fi
if [[ -e "${FINAL_STAGE_BACKUP}" ]]; then
    if [[ -e "${FINAL_STAGE_DIR}" ]]; then
        rm -rf -- "${FINAL_STAGE_BACKUP}"
    else
        mv "${FINAL_STAGE_BACKUP}" "${FINAL_STAGE_DIR}"
    fi
fi
if [[ -e "${TARGET_ZIP_BACKUP}" ]]; then
    if [[ -e "${TARGET_ZIP}" ]]; then
        rm -f -- "${TARGET_ZIP_BACKUP}"
    else
        mv "${TARGET_ZIP_BACKUP}" "${TARGET_ZIP}"
    fi
fi
if [[ -e "${FINAL_STAGE_DIR}" && ! -d "${FINAL_STAGE_DIR}" ]]; then
    echo "SDK stage publication path is not a directory: ${FINAL_STAGE_DIR}" >&2
    exit 1
fi
if [[ -e "${TARGET_ZIP}" && ! -f "${TARGET_ZIP}" ]]; then
    echo "SDK archive publication path is not a regular file: ${TARGET_ZIP}" >&2
    exit 1
fi

# Extract the checksum-verified, manifest-selected AOT SDK into a fresh private
# directory. The extractor rejects traversal, links/special files, duplicates,
# zip bombs, and an internal layout that disagrees with the release manifest.
python3 "${SCRIPT_DIR}/tools/extract_verified_sdk.py" \
    "${SDX_SDK_ARTIFACT}" "${STAGE_DIR}" \
    --version "${UPSTREAM_VERSION}" --platform "${SDX_PLATFORM}" --variant "${SDX_VARIANT}"
cp "${SDX_SDK_MANIFEST}" "${STAGE_DIR}/sdx-sdk-manifest.json"
cp "${SDX_SDK_MANIFEST_CHECKSUM}" "${STAGE_DIR}/sdx-sdk-manifest.json.sha256"

# Overlay Kompile-owned reasoning artifacts only.
mkdir -p "${STAGE_DIR}/include" "${STAGE_DIR}/lib"
mkdir -p "${STAGE_DIR}/bindings/python" "${STAGE_DIR}/bindings/rust"
mkdir -p "${STAGE_DIR}/bindings/typescript" "${STAGE_DIR}/bindings/swift" "${STAGE_DIR}/bindings/csharp"
mkdir -p "${STAGE_DIR}/examples/python" "${STAGE_DIR}/examples/rust"
mkdir -p "${STAGE_DIR}/examples/typescript" "${STAGE_DIR}/examples/swift"
mkdir -p "${STAGE_DIR}/examples/csharp" "${STAGE_DIR}/examples/data"

cp include/kompile_reasoning.h "${STAGE_DIR}/include/"
cp "${KGR_SRC}" "${STAGE_DIR}/lib/libkompile_reasoning.so"
cp bindings/python/kompile_reasoning.py "${STAGE_DIR}/bindings/python/"
cp bindings/rust/kompile_reasoning.rs "${STAGE_DIR}/bindings/rust/"
cp bindings/typescript/kompile_reasoning.ts "${STAGE_DIR}/bindings/typescript/"
cp bindings/swift/KompileReasoning.swift "${STAGE_DIR}/bindings/swift/"
cp bindings/csharp/KompileReasoning.cs "${STAGE_DIR}/bindings/csharp/"

for metadata in bindings/python/pyproject.toml bindings/typescript/package.json bindings/rust/Cargo.toml; do
    if [[ -f "${metadata}" ]]; then cp "${metadata}" "${STAGE_DIR}/$(dirname "${metadata}")/"; fi
done

cp examples/python/chat_with_graph.py "${STAGE_DIR}/examples/python/"
cp examples/rust/chat_with_graph.rs examples/rust/Cargo.toml examples/rust/build.rs "${STAGE_DIR}/examples/rust/"
cp examples/typescript/chat_with_graph.ts examples/typescript/package.json examples/typescript/tsconfig.json "${STAGE_DIR}/examples/typescript/"
cp examples/swift/chat_with_graph.swift "${STAGE_DIR}/examples/swift/"
cp examples/csharp/ChatWithGraph.cs "${STAGE_DIR}/examples/csharp/"
if [[ -f examples/data/fixture.kgraph ]]; then
    cp examples/data/fixture.kgraph "${STAGE_DIR}/examples/data/"
fi
cp README.md PUBLISHING.md "${STAGE_DIR}/"

# Record independent provenance without modifying the upstream manifest.
python3 - "${SDX_SDK_MANIFEST}" "${STAGE_DIR}/kompile-composition-manifest.json" \
    "${SELECTED_CLASSIFIER}" "${SELECTED_FILE}" <<'PY'
import datetime
import hashlib
import json
import pathlib
import sys

upstream_path = pathlib.Path(sys.argv[1])
output_path = pathlib.Path(sys.argv[2])
upstream_bytes = upstream_path.read_bytes()
upstream = json.loads(upstream_bytes)
local = json.loads(pathlib.Path('manifest.json').read_text())
composition = {
    'schemaVersion': 1,
    'name': 'kompile-local-sdk',
    'kompileVersion': local['kompileVersion'],
    'kgrAbiVersion': int(local['kgrAbiVersion']),
    'buildTimestamp': datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'upstreamSdk': {
        'schemaVersion': upstream.get('schemaVersion'),
        'releaseVersion': upstream.get('releaseVersion'),
        'releaseTag': upstream.get('releaseTag'),
        'manifestFile': 'sdx-sdk-manifest.json',
        'manifestChecksumFile': 'sdx-sdk-manifest.json.sha256',
        'manifestSha256': hashlib.sha256(upstream_bytes).hexdigest(),
        'selectedArtifact': {
            'classifier': sys.argv[3],
            'fileName': sys.argv[4],
        },
    },
    'overlay': {
        'component': 'kompile-reasoning',
        'library': 'lib/libkompile_reasoning.so',
        'header': 'include/kompile_reasoning.h',
    },
}
output_path.write_text(json.dumps(composition, indent=2) + '\n')
PY

# Build and validate the archive under a private sibling name before changing
# either published output. The archive retains the stable manifest-owned root.
WORK_ZIP="$(mktemp "${OUTPUT_PARENT}/.${ZIP_NAME}.tmp.XXXXXXXX")"
rm -f -- "${WORK_ZIP}"
(cd "${WORK_PARENT}" && zip -qr "${WORK_ZIP}" "${ROOT_NAME}/")
zip -T "${WORK_ZIP}" >/dev/null

previous_stage=0
previous_zip=0
if [[ -e "${FINAL_STAGE_DIR}" ]]; then previous_stage=1; fi
if [[ -e "${TARGET_ZIP}" ]]; then previous_zip=1; fi
transaction_record="${WORK_PARENT}/publication-transaction"
printf '%s\t%s\n' "${previous_stage}" "${previous_zip}" > "${transaction_record}"
mv "${transaction_record}" "${PUBLICATION_TRANSACTION}"

if [[ -e "${FINAL_STAGE_DIR}" ]]; then
    mv "${FINAL_STAGE_DIR}" "${FINAL_STAGE_BACKUP}"
fi
if [[ -e "${TARGET_ZIP}" ]]; then
    mv "${TARGET_ZIP}" "${TARGET_ZIP_BACKUP}"
fi
if mv "${WORK_STAGE_DIR}" "${FINAL_STAGE_DIR}"; then
    STAGE_DIR="${FINAL_STAGE_DIR}"
else
    promotion_status=$?
    if rollback_publication; then
        rm -f -- "${PUBLICATION_TRANSACTION}"
    fi
    echo "Unable to promote the completed SDK stage; the previous published SDK was restored" >&2
    exit "${promotion_status}"
fi

# WORK_ZIP and TARGET_ZIP share a physical parent, so this rename is atomic and
# never exposes zip's partially written output. The transaction record and both
# backups remain until the stage/archive pair has committed successfully.
if mv "${WORK_ZIP}" "${TARGET_ZIP}"; then
    WORK_ZIP=""
else
    promotion_status=$?
    if rollback_publication; then
        rm -f -- "${PUBLICATION_TRANSACTION}"
    fi
    echo "Unable to atomically publish SDK archive; the previous published SDK was restored" >&2
    exit "${promotion_status}"
fi
rm -f -- "${PUBLICATION_TRANSACTION}"
if [[ -e "${FINAL_STAGE_BACKUP}" ]]; then
    rm -rf -- "${FINAL_STAGE_BACKUP}"
fi
if [[ -e "${TARGET_ZIP_BACKUP}" ]]; then
    rm -f -- "${TARGET_ZIP_BACKUP}"
fi
echo "Assembly complete: ${TARGET_ZIP}"
