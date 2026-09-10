#!/usr/bin/env python3
"""Exercise real APK staging under its conditional Bash caller, without Gradle."""
import fcntl
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
import zipfile

PACKAGER = Path(__file__).with_name('apk-publication.sh')


def staging_function():
    text = PACKAGER.read_text()
    start = text.index('stage_apk_exactly() {\n')
    return text[start:text.index('\n}\n', start) + 3]


class ApkStagingTest(unittest.TestCase):
    def run_stage(self, fault=''):
        # Fail rather than silently falling back to /tmp when an explicitly
        # requested diagnostic filesystem is unavailable.
        with tempfile.TemporaryDirectory(prefix='apk-publication-test-',
                                         dir=os.environ.get('TMPDIR')) as directory:
            root = Path(directory)
            source = root / 'source.apk'
            target = root / 'staged.apk'
            if os.environ.get('APK_PUBLICATION_TEST_FLAT_TARGET') == '1':
                # Match production's hidden sibling file, not a nested directory.
                holder = tempfile.NamedTemporaryFile(dir=root.parent,
                    prefix='.kompile-apk-probe.', suffix='.apk', delete=False)
                holder.close()
                target = Path(holder.name)
                self.addCleanup(target.unlink, missing_ok=True)
            # Optional read-only real artifact for filesystem diagnosis. Fault
            # injection always uses a disposable fixture, never the supplied APK.
            fixture = os.environ.get('APK_PUBLICATION_TEST_SOURCE') if not fault else None
            if fixture:
                source = Path(fixture).resolve(strict=True)
            else:
                with zipfile.ZipFile(source, 'w') as archive:
                    archive.writestr('payload', os.urandom(2 * 1024 * 1024))
            streaming_probe = bool(fixture) and os.environ.get('APK_PUBLICATION_TEST_STREAMING') == '1'
            original = None if streaming_probe else source.read_bytes()
            prelude = '''set -euo pipefail
source_apk="$1"
staging_apk="$2"
'''
            if fault in ('source-sync', 'staging-sync'):
                which = '$source_apk' if fault == 'source-sync' else '$staging_apk'
                prelude += 'sync() { if [[ "$1" == "' + which + '" ]]; then return 17; fi; command sync "$@"; }\n'
            elif fault in ('hash-failure', 'empty-hash'):
                prelude += 'sha256sum() { return ' + ('17' if fault == 'hash-failure' else '0') + '; }\n'
            elif fault == 'remove-failure':
                prelude += 'rm() { return 17; }\n'
            elif fault:
                prelude += '''dd() {
  local src dst arg
  for arg in "$@"; do
    case "$arg" in if=*) src="${arg#if=}" ;; of=*) dst="${arg#of=}" ;; esac
  done
  command dd "$@" || return
'''
                if fault == 'source-change':
                    prelude += '  printf changed >> "$src"\n'
                elif fault == 'copy-corrupt':
                    prelude += '  printf broken > "$dst"\n'
                elif fault == 'copy-failure':
                    prelude += '  return 17\n'
                prelude += '}\n'
            # Exactly the production calling context: errexit is disabled inside the function.
            script = prelude + staging_function() + '''
if ! digest="$(stage_apk_exactly "$1" "$2")"; then exit 23; fi
printf '%s\n' "$digest"
'''
            start = time.monotonic()
            # Reproduce the canonical wrapper's inherited flock descriptor without
            # taking its real pipeline lock or any system resource reservation.
            with tempfile.TemporaryFile(dir=root) as lock:
                fcntl.flock(lock, fcntl.LOCK_EX)
                inherited_lock = os.environ.get('APK_PUBLICATION_TEST_INHERIT_LOCK') == '1'
                environment = os.environ.copy()
                if inherited_lock:
                    environment['SDX_ANDROID_PIPELINE_LOCK_HELD'] = '1'
                    environment['SDX_ANDROID_PIPELINE_LOCK_FD'] = str(lock.fileno())
                result = subprocess.run(['bash', '-c', script, 'test', str(source), str(target)],
                                        capture_output=True, text=True, timeout=90,
                                        env=environment,
                                        pass_fds=(lock.fileno(),) if inherited_lock else ())
            print(f'STAGING_TEST fault={fault or "none"} seconds={time.monotonic()-start:.3f} rc={result.returncode}', flush=True)
            if fault:
                self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
            else:
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                if streaming_probe:
                    def digest_file(path):
                        digest = hashlib.sha256()
                        with path.open('rb') as stream:
                            for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                                digest.update(chunk)
                        return digest.hexdigest()
                    self.assertEqual(digest_file(source), result.stdout.strip())
                    self.assertEqual(digest_file(source), digest_file(target))
                else:
                    self.assertEqual(hashlib.sha256(original).hexdigest(), result.stdout.strip())
                    self.assertEqual(original, target.read_bytes())
                self.assertNotEqual(source.stat().st_ino, target.stat().st_ino)

    def test_exact_copy(self):
        self.run_stage()

    def test_failures_cannot_be_hidden_by_conditional_caller(self):
        for fault in ('source-sync', 'staging-sync', 'hash-failure', 'empty-hash',
                      'remove-failure', 'copy-failure', 'source-change', 'copy-corrupt'):
            with self.subTest(fault=fault):
                self.run_stage(fault)


class RetainedPublicationTest(unittest.TestCase):
    """Small synthetic inputs; only the expensive host verifier is a test double."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='retained-publication-test-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.tools = self.root / 'android/tools'
        self.tools.mkdir(parents=True)
        for name in ('apk-publication.sh', 'publish-retained-tensor-g3.sh'):
            (self.tools / name).write_text(PACKAGER.with_name(name).read_text())
        self.retained = self.root / '.kompile-android-app-build.v123.ABCdef'
        self.app = self.retained / 'outputs/apk/tensorG3/debug/app-tensorG3-debug.apk'
        self.test = self.retained / 'outputs/apk/androidTest/tensorG3/debug/app-tensorG3-debug-androidTest.apk'
        self.normalized = self.retained / 'sdx-normalized-aar/tensor-g3/sdx-runtime-tensor-g3.aar'
        self.aar = self.root / 'producer/runtime.aar'
        for path in (self.app, self.test, self.aar):
            path.parent.mkdir(parents=True, exist_ok=True)
            with zipfile.ZipFile(path, 'w') as archive:
                archive.writestr('payload', path.name.encode())
        self.normalized.parent.mkdir(parents=True)
        self.normalized.write_bytes(self.aar.read_bytes())
        self.sdk = self.root / 'sdk'
        self.ndk = self.root / 'ndk'
        self.output = self.root / 'output'
        self.staging = self.root / 'staging'
        self.aot = self.root / 'aot'
        for path in (self.sdk, self.ndk, self.output, self.staging, self.aot / 'metadata'):
            path.mkdir(parents=True)
        (self.ndk / 'source.properties').write_text('Pkg.Revision = test\n')
        ndk_hash = self.digest(self.ndk / 'source.properties')
        self.receipt = Path(str(self.aar) + '.build-receipt')
        self.receipt.write_text('format=3\nstage=full\nvariant=tensor-g3\n'
            f'artifact={self.aar}\nsha256={self.digest(self.aar)}\nndk_revision_sha256={ndk_hash}\n')
        self.aot_receipt = self.aot / 'metadata/build-receipt'
        self.aot_receipt.write_text(f'stage=android-aot-sdk\nndk_revision_sha256={ndk_hash}\n')
        self.args = {
            '--retained-root': str(self.retained), '--build-id': 'v123', '--version-code': '123',
            '--expected-apk-sha256': self.digest(self.app), '--tensor-g3-aar': str(self.aar),
            '--expected-aar-sha256': self.digest(self.aar),
            '--expected-aar-receipt-sha256': self.digest(self.receipt),
            '--sdx-llm-sdk': str(self.aot), '--expected-aot-receipt-sha256': self.digest(self.aot_receipt),
            '--android-sdk': str(self.sdk), '--android-ndk': str(self.ndk),
            '--output': str(self.output), '--staging-root': str(self.staging),
        }
        self.environment = os.environ.copy()
        self.environment['PROBE_ROOT'] = str(self.root)
        self.environment.pop('VERIFY_FAIL', None)
        self.environment.pop('COPY_FAIL', None)
        # The shared verify_apk must forward every historical identity anchor.
        self.executable(self.tools / 'verify-offline-apk.sh', '''#!/usr/bin/env bash
set -euo pipefail
printf '%s\\n' "$@" > "$PROBE_ROOT/verification-args"
while (( $# )); do
  case "$1" in
    --expected-build-id) [[ "$2" == v123 ]] || exit 31 ;;
    --expected-version-code) [[ "$2" == 123 ]] || exit 32 ;;
  esac
  shift 2
done
[[ ${VERIFY_FAIL:-0} == 0 ]]
''')
        forbidden = '''#!/usr/bin/env bash
printf forbidden > "$PROBE_ROOT/forbidden"
exit 99
'''
        for name in ('prune-offline-apk-candidates.sh', 'allocate-apk-version-code.sh',
                     'build-offline-accelerators.sh'):
            self.executable(self.tools / name, forbidden)
        self.executable(self.tools.parent / 'gradlew', forbidden)
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        self.executable(self.bin / 'dd', '''#!/usr/bin/env bash
if [[ ${COPY_FAIL:-0} == 1 ]]; then
  for arg in "$@"; do
    case "$arg" in of="$PROBE_ROOT/output/"*) exit 17 ;; esac
  done
fi
exec /usr/bin/dd "$@"
''')
        self.environment['PATH'] = str(self.bin) + os.pathsep + self.environment['PATH']
        # Existing unrelated candidates/stable aliases must never be pruned.
        (self.output / 'unrelated.apk').write_bytes(b'keep candidate')
        (self.output / 'kompile-offline-graph-chat-tensor-g3-pixel-8a.apk').write_bytes(b'keep stable')

    @staticmethod
    def executable(path, text):
        path.write_text(text)
        path.chmod(0o755)

    @staticmethod
    def digest(path):
        return hashlib.sha256(path.read_bytes()).hexdigest()

    def invoke(self, success=False):
        inputs = [self.app, self.test, self.normalized, self.aar, self.receipt, self.aot_receipt]
        before = {p: (p.read_bytes(), p.stat().st_ino) for p in inputs}
        command = ['bash', str(self.tools / 'publish-retained-tensor-g3.sh')]
        for key, value in self.args.items():
            command += [key, value]
        result = subprocess.run(command, env=self.environment, capture_output=True,
                                text=True, timeout=15)
        self.assertEqual(success, result.returncode == 0, result.stdout + result.stderr)
        for path, snapshot in before.items():
            self.assertEqual(snapshot, (path.read_bytes(), path.stat().st_ino))
        self.assertFalse((self.root / 'forbidden').exists())
        self.assertEqual(b'keep candidate', (self.output / 'unrelated.apk').read_bytes())
        self.assertEqual(b'keep stable', (self.output / 'kompile-offline-graph-chat-tensor-g3-pixel-8a.apk').read_bytes())
        self.assertEqual([], list(self.staging.iterdir()))
        return result

    def test_success_preserves_inputs_and_forwards_full_verifier_arguments(self):
        self.invoke(success=True)
        manifests = list(self.output.glob('*.candidate'))
        self.assertEqual(1, len(manifests))
        fields = dict(line.split('=', 1) for line in manifests[0].read_text().splitlines())
        self.assertEqual('PASS', fields['host_verification'])
        self.assertEqual('123', fields['version_code'])
        self.assertEqual(self.app.read_bytes(), Path(fields['candidate_apk']).read_bytes())
        self.assertEqual(self.test.read_bytes(), Path(fields['test_apk']).read_bytes())
        self.assertEqual(self.aar.read_bytes(), Path(fields['normalized_runtime_aar']).read_bytes())
        args = (self.root / 'verification-args').read_text().splitlines()
        forwarded = dict(zip(args[::2], args[1::2]))
        self.assertEqual(str(self.normalized), forwarded['--runtime-aar'])
        self.assertEqual(self.digest(self.aar), forwarded['--expected-source-runtime-aar-sha256'])
        self.assertEqual(self.digest(self.receipt), forwarded['--expected-runtime-provenance-sha256'])
        self.assertEqual(self.digest(self.aot_receipt), forwarded['--expected-sdx-aot-provenance-sha256'])
        self.assertEqual(str(self.aot), forwarded['--sdx-sdk'])
        self.assertEqual(str(self.sdk), forwarded['--android-sdk'])
        self.assertEqual(str(self.ndk), forwarded['--android-ndk'])
        # Same generation cannot overwrite a successful publication on retry.
        self.invoke()

    def test_all_wrong_expected_hashes_fail_before_verification(self):
        for key in ('--expected-apk-sha256', '--expected-aar-sha256',
                    '--expected-aar-receipt-sha256', '--expected-aot-receipt-sha256'):
            with self.subTest(key=key):
                original = self.args[key]
                self.args[key] = '0' * 64
                self.invoke()
                self.args[key] = original
                self.assertFalse((self.root / 'verification-args').exists())
                self.assertFalse(list(self.output.glob('*.candidate')))

    def test_verifier_failure_cannot_publish(self):
        self.environment['VERIFY_FAIL'] = '1'
        self.invoke()
        self.assertFalse(list(self.output.glob('*.candidate')))

    def test_publication_fsync_copy_failure_preserves_retained_input(self):
        self.environment['COPY_FAIL'] = '1'
        self.invoke()
        self.assertTrue((self.root / 'verification-args').exists())
        self.assertFalse(list(self.output.glob('*.candidate')))
        self.assertFalse(list(self.output.glob('.*.publish.*')))

    def test_wrong_version_is_rejected_by_full_verifier_call(self):
        self.args['--version-code'] = '124'
        self.invoke()
        self.assertTrue((self.root / 'verification-args').exists())
        self.assertFalse(list(self.output.glob('*.candidate')))

    def test_wrong_build_id_and_output_overlap_fail_before_verification(self):
        self.args['--build-id'] = 'v124'
        self.invoke()
        self.args['--build-id'] = 'v123'
        self.args['--output'] = str(self.retained)
        self.invoke()
        self.assertFalse((self.root / 'verification-args').exists())

    def test_receipt_path_mismatch_is_rejected_even_with_expected_hash(self):
        self.receipt.write_text(self.receipt.read_text().replace(f'artifact={self.aar}', 'artifact=/wrong/aar'))
        self.args['--expected-aar-receipt-sha256'] = self.digest(self.receipt)
        self.invoke()
        self.assertFalse((self.root / 'verification-args').exists())

    def test_symlink_input_is_rejected(self):
        alias = self.root / 'aar-link'
        alias.symlink_to(self.aar)
        self.args['--tensor-g3-aar'] = str(alias)
        self.invoke()
        self.assertFalse((self.root / 'verification-args').exists())

    def run_shared_publisher(self, mode, fault=''):
        self.executable(self.tools / 'prune-offline-apk-candidates.sh',
                        '#!/usr/bin/env bash\nprintf pruned > "$PROBE_ROOT/pruned"\n')
        environment = self.environment | {
            'SCRIPT_DIR': str(self.tools.parent), 'APP_BUILD_ROOT': str(self.retained),
            'APK_BUILD_ID': 'v123', 'APK_VERSION_CODE': '123', 'VARIANT': 'tensor-g3',
            'TENSOR_G3_AAR': str(self.aar), 'TENSOR_G3_SOURCE_SHA256': self.digest(self.aar),
            'TENSOR_G3_FULL_RECEIPT': str(self.receipt),
            'TENSOR_G3_PROVENANCE_SHA256': self.digest(self.receipt),
            'SDX_LLM_SDK': str(self.aot), 'SDX_AOT_RECEIPT': str(self.aot_receipt),
            'SDX_AOT_PROVENANCE_SHA256': self.digest(self.aot_receipt),
            'ANDROID_SDK': str(self.sdk), 'ANDROID_NDK_ARG': str(self.ndk),
            'OUTPUT_DIR': str(self.output), 'APK_STAGING_ROOT': str(self.staging),
            'FAULT': fault,
        }
        script = '''set -euo pipefail
source "$1"
TEMPORARY_FILES=()
sha256_file() { sha256sum "$1" | cut -d ' ' -f 1; }
sync() {
  if [[ "$FAULT" == output-sync && "$1" == "$OUTPUT_DIR/"* ]]; then return 17; fi
  command sync "$@"
}
# Conditional invocation deliberately disables errexit in the entire function.
if ! publish_candidate "$2" kompile-offline-graph-chat-tensor-g3-pixel-8a.apk tensorG3 "$3" "$4"; then
  exit 23
fi
'''
        return subprocess.run(['bash', '-c', script, 'test', str(PACKAGER), str(self.app),
                               mode, self.digest(self.app)], env=environment,
                              capture_output=True, text=True, timeout=15)

    def test_normal_publisher_keeps_disposable_removal_and_pruning(self):
        result = self.run_shared_publisher('build')
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertFalse(self.app.exists())
        self.assertTrue(self.test.exists())
        self.assertTrue((self.root / 'pruned').exists())
        self.assertEqual(1, len(list(self.output.glob('*.candidate'))))

    def test_output_sync_failure_propagates_under_conditional_caller(self):
        before = self.app.read_bytes()
        result = self.run_shared_publisher('retained', 'output-sync')
        self.assertEqual(23, result.returncode, result.stdout + result.stderr)
        self.assertEqual(before, self.app.read_bytes())
        self.assertFalse((self.root / 'pruned').exists())
        self.assertFalse(list(self.output.glob('*.candidate')))

    def test_helper_is_source_safe(self):
        result = subprocess.run(['bash', '-c', 'set -euo pipefail; source "$1"',
                                 'test', str(PACKAGER)], capture_output=True, text=True, timeout=5)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('', result.stdout)


if __name__ == '__main__':
    unittest.main()
