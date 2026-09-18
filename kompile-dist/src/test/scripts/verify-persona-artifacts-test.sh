#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
VERIFIER="${SCRIPT_DIR}/../../main/build/verify-persona-artifacts.py"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT

python3 - "${TMP_DIR}" <<'PY'
from pathlib import Path
import json
import sys
import zipfile

root = Path(sys.argv[1])
dist = root / "full"
(dist / "lib").mkdir(parents=True)
(dist / "bin").mkdir(parents=True)

contracts = {
    "server": (
        "kompile-server.jar",
        "ai.kompile.app.MainApplication",
        ["kompile-app-web-shared-1.jar", "kompile-app-web-admin-1.jar", "kompile-app-ui-1-admin.jar"],
    ),
    "chat": (
        "kompile-chat.jar",
        "ai.kompile.app.chat.ChatApplication",
        ["kompile-app-web-shared-1.jar", "kompile-app-web-chat-1.jar", "kompile-app-web-graph-1.jar", "kompile-app-ui-1-chat.jar"],
    ),
    "crawl-manager": (
        "kompile-crawl-manager.jar",
        "ai.kompile.app.crawlmanager.CrawlManagerApplication",
        ["kompile-app-web-shared-1.jar", "kompile-app-web-crawl-1.jar", "kompile-app-web-graph-1.jar", "kompile-app-ui-1-crawl.jar"],
    ),
}

for _, (jar_name, start_class, libraries) in contracts.items():
    with zipfile.ZipFile(dist / "lib" / jar_name, "w") as archive:
        archive.writestr("META-INF/MANIFEST.MF", f"Manifest-Version: 1.0\nStart-Class: {start_class}\n\n")
        archive.writestr("BOOT-INF/classes/example.class", b"class")
        for library in libraries:
            archive.writestr(f"BOOT-INF/lib/{library}", b"jar")

for launcher in ("kompile-server.sh", "kompile-chat.sh", "kompile-crawl-manager.sh"):
    (dist / "bin" / launcher).write_text("#!/usr/bin/env sh\n", encoding="utf-8")

(dist / ".dist-info.json").write_text(json.dumps({
    "variant": "full",
    "components": {
        "server": {"jar": True, "native": False},
        "chat": {"jar": True, "native": False},
        "crawl-manager": {"jar": True, "native": False},
    },
}), encoding="utf-8")
manifest_files = [
    ".dist-info.json",
    "bin/kompile-server.sh",
    "bin/kompile-chat.sh",
    "bin/kompile-crawl-manager.sh",
    "lib/kompile-server.jar",
    "lib/kompile-chat.jar",
    "lib/kompile-crawl-manager.jar",
]
(dist / "manifest.sha256").write_text(
    "".join(f"0  {relative}\n" for relative in manifest_files), encoding="utf-8")

# cli-only now ships exactly one persona artifact: the chat handoff JAR.
def build_cli(name: str, with_chat_jar: bool) -> None:
    dist = root / name
    (dist / "lib").mkdir(parents=True)
    (dist / "bin").mkdir(parents=True)
    manifest_files = [".dist-info.json"]
    if with_chat_jar:
        jar_name, start_class, libraries = contracts["chat"]
        with zipfile.ZipFile(dist / "lib" / jar_name, "w") as archive:
            archive.writestr("META-INF/MANIFEST.MF", f"Manifest-Version: 1.0\nStart-Class: {start_class}\n\n")
            archive.writestr("BOOT-INF/classes/example.class", b"class")
            for library in libraries:
                archive.writestr(f"BOOT-INF/lib/{library}", b"jar")
        manifest_files.append(f"lib/{jar_name}")
    (dist / ".dist-info.json").write_text(json.dumps({
        "variant": "cli-only",
        "components": ({
            "chat": {"jar": with_chat_jar, "native": False},
        } if with_chat_jar else {}),
    }), encoding="utf-8")
    (dist / "manifest.sha256").write_text(
        "".join(f"0  {relative}\n" for relative in manifest_files), encoding="utf-8")

build_cli("cli", True)
build_cli("cli-missing-chat", False)
PY

python3 "${VERIFIER}" --dist-root "${TMP_DIR}/full" --variant full --platform linux-x86_64
python3 "${VERIFIER}" --dist-root "${TMP_DIR}/cli" --variant cli-only --platform linux-x86_64

if python3 "${VERIFIER}" --dist-root "${TMP_DIR}/cli-missing-chat" --variant cli-only --platform linux-x86_64; then
    echo "ERROR: cli-only dist without the chat handoff JAR was accepted" >&2
    exit 1
fi

python3 - "${TMP_DIR}/full/lib/kompile-chat.jar" <<'PY'
from pathlib import Path
import sys
import zipfile

path = Path(sys.argv[1])
with zipfile.ZipFile(path, "a") as archive:
    archive.writestr("BOOT-INF/lib/kompile-app-web-admin-1.jar", b"foreign")
PY
if python3 "${VERIFIER}" --dist-root "${TMP_DIR}/full" --variant full --platform linux-x86_64; then
    echo "ERROR: foreign persona dependency was accepted" >&2
    exit 1
fi

echo "verify-persona-artifacts tests passed"
