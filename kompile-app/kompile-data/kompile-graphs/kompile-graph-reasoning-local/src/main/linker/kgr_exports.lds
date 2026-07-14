/* kgr_exports.lds — GNU linker version script for libkompile_reasoning
 *
 * Export ONLY the kgr_* public C API symbols.  Everything else (graal_*,
 * JNI_*, IsolateEnterStub__*, and GraalVM internals) is hidden with local
 * visibility so they cannot collide with a co-loaded GraalVM native image
 * (e.g. libsdx_llm.so) in the same process.
 *
 * Pattern mirrors deeplearning4j/libnd4j/cmake/sdx_exports.lds which hides
 * the SDX internal runtime under the same rule.
 *
 * macOS note: Apple's ld(1) does not support GNU version scripts.  On macOS
 * use -exported_symbols_list instead (list one symbol per line, no wildcards).
 * The build-native.sh / pom.xml only passes this script on Linux where the
 * GNU ld / lld linker is used.
 */
KGR_1 {
  global:
    kgr*;
  local:
    *;
};
