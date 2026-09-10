#!/usr/bin/env python3
"""Run the real AOT object-copy blocks without Graal, Maven, or an SDK build."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[4]
GRAPH = ROOT / "kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning-local/build-android-ndk.sh"
SDX = ROOT.parent / "deeplearning4j/nd4j/sdx-aot/src/main/android/build-android-aot-sdk.sh"


def blocks():
    graph = GRAPH.read_text()
    sdx = SDX.read_text()
    start = graph.index('if [[ -n "$OBJECT_OUTPUT" ]]; then\n')
    publication = graph[start:graph.index('\nfi\n', start) + 4]
    start = sdx.index('stage_native_image_object() {\n')
    restore = sdx[start:sdx.index('\n}\n', start) + 3]
    restore += '\nstage_native_image_object "$source_stage"\n'
    start = sdx.index('    dd if="$OBJECT" of="$OBJECT_STAGE_TMP/libsdx_llm.o"')
    cache = sdx[start:sdx.index('\n    {\n', start)]
    return {'publication': publication, 'restore': restore, 'cache': cache}


class ObjectCopyTest(unittest.TestCase):
    def run_copy(self, kind, fault=''):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'source/libsdx_llm.o'
            source.parent.mkdir()
            # Sparse object-shaped payload with data both before and after a hole.
            with source.open('wb') as stream:
                stream.write(b'\x7fELF' + bytes(range(256)) * 17)
                stream.seek(3 * 1024 * 1024)
                stream.write(b'nonzero-tail' * 1024)
            original = source.read_bytes()
            target_dir = root / 'target'
            target_dir.mkdir()
            target = target_dir / 'libsdx_llm.o'
            target.write_bytes(b'old output must survive failed publication')
            prelude = '''set -euo pipefail
fail() { printf '%s\\n' "$*" >&2; exit 3; }
sha256_file() { sha256sum "$1" | awk '{print $1}'; }
# Any reintroduction of extent-aware copying fails this test.
cp() { printf 'cp must not copy objects\\n' >&2; return 99; }
GRAPH_OBJECT="$1"
source_stage="$(dirname "$1")"
OBJECT_OUTPUT="$2"
OBJECT_STAGE_TMP="$(dirname "$2")"
OBJECT="$2"
'''
            if kind == 'cache':
                prelude += 'OBJECT="$1"\nOBJECT_SHA256="$(sha256_file "$OBJECT")"\n'
            if fault:
                prelude += '''dd() {
  local src dst arg
  for arg in "$@"; do
    case "$arg" in if=*) src="${arg#if=}" ;; of=*) dst="${arg#of=}" ;; esac
  done
'''
                if fault == 'hardlink':
                    prelude += '  rm -f -- "$dst"\n  ln -- "$src" "$dst"\n'
                else:
                    prelude += '  command dd "$@"\n'
                    if fault == 'corrupt':
                        prelude += '  printf broken > "$dst"\n'
                    elif fault == 'source-change':
                        prelude += '  printf changed >> "$src"\n'
                    elif fault == 'copy-failure':
                        prelude += '  return 17\n'
                prelude += '}\n'
            result = subprocess.run(['bash', '-c', prelude + blocks()[kind],
                                     'test', str(source), str(target)],
                                    capture_output=True, text=True, timeout=10)
            if not fault:
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(original, target.read_bytes())
                self.assertNotEqual(os.stat(source).st_ino, os.stat(target).st_ino)
            else:
                self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
                if kind == 'publication':
                    self.assertEqual(b'old output must survive failed publication', target.read_bytes())

    def test_sparse_bytes_and_independent_inode(self):
        for kind in blocks():
            with self.subTest(kind=kind):
                self.run_copy(kind)

    def test_corrupt_copy_is_rejected(self):
        for kind in blocks():
            with self.subTest(kind=kind):
                self.run_copy(kind, 'corrupt')

    def test_source_mutation_is_rejected(self):
        for kind in blocks():
            with self.subTest(kind=kind):
                self.run_copy(kind, 'source-change')

    def test_hardlink_is_rejected(self):
        for kind in blocks():
            with self.subTest(kind=kind):
                self.run_copy(kind, 'hardlink')

    def test_failed_copy_is_not_published(self):
        for kind in blocks():
            with self.subTest(kind=kind):
                self.run_copy(kind, 'copy-failure')


if __name__ == '__main__':
    unittest.main()
