# Gemma 4 template diagnostic fixture

This is a separate upstream fixture, **not** a replacement for the GGUF/model-owned template.

- Upstream: Google `gemma-4-E2B-it`
- Revision: `3e22461f65e89153144f8adb70e3b8c2cc9845a7`
- Source: https://huggingface.co/google/gemma-4-E2B-it/raw/3e22461f65e89153144f8adb70e3b8c2cc9845a7/chat_template.jinja
- Metadata: https://huggingface.co/api/models/google/gemma-4-E2B-it/tree/3e22461f65e89153144f8adb70e3b8c2cc9845a7
- Size: 18,569 bytes
- Upstream blob identity: `fbe3b59b625cd1b8850ea592d4203df6ec04684b`
- SHA-256: `0a2c8073c878ab1da004bee933a998606537bbb62016310352c7285c3f01c5b5`

`GemmaIndependentReferenceTest#compareTemplateEnginesWithoutWeights` verifies the upstream blob identity locally using the SHA-1 blob framing algorithm (no Git invocation). It also checks that the explicitly supplied original template equals `tokenizer.chat_template` read through `GGUFReader.readHeader()`, without reading tensors.

The test passes the same production `ChatTemplate.requestContextJson` and same template to production MiniJinja and isolated Python Jinja2. Independent tokenization uses llama.cpp `vocab_only=True`, `add_bos=False`, `special=True`; no weights or inference. Both the unchanged production ontology request and a separate nested-array/property-name edge fixture are compared. Declaration syntax uses unquoted keys and uppercase types; it is **not JSON** and is not validated as JSON.

Required opt-in properties:

- `gemma.reference.dir`: diagnostic output directory
- `gemma.reference.template`: original extracted template file (never overwritten)
- `gemma.reference.gguf`: actual source GGUF
- `gemma.reference.tokenizer`: corresponding tokenizer.json
- `gemma.reference.pythonRoot`: isolated installed Python packages (Jinja2 and llama-cpp-python)

Initial four-way comparison passed with Jinja2 3.1.6 / llama-cpp-python 0.3.35. Original template SHA-256 was `55572b8d3c8342044e25874c73fe5234b661fa0a57a57f6ef75b58e03d7d959a`.

| Case | Bytes | Tokens | Render SHA-256 |
|---|---:|---:|---|
| original ontology | 5421 | 1314 | `18cbdfe66e9204270b886b3b50294edd7b13219cf6f2a2b4695af2fc92e41081` |
| official ontology | 5421 | 1313 | `f82f0fb2ec704f07032ee00b335d17d528628d899bf4d06df095da3f3caed59c` |
| original edges | 503 | 121 | `9898371169dfc28388202ec6173b775a5ac50f82833b50be8f2f53469335573b` |
| official edges | 697 | 164 | `d11b53b8ea4a202397116262b433f033c9460c5cfefd86f80c90d311ab521a4d` |

The original's leading ARRAY/OBJECT commas, missing post-items/properties separator, and filtering of legitimate `type`/`description` property names reproduce in both engines. The pinned fixture fixes these cases. This is template-authoring evidence, not proof of ontology accuracy or universal engine parity. Nested arrays are covered as authored; the generic nested `format_argument` representation is not normalized or rewritten.

## Final validation and cross-JVM ordering caveat

Final strengthened regression proc-098 passed (one JUnit test, four template/case comparisons, no skips). It additionally asserts both original missing separators and reserved-name loss, and disables Python user-site imports. Its ontology render hashes were original `75f6cc4608b3a22e9206a9982347b0a516dd122ff09ed940a0eaa019fc82e912` and official `c6982d0ff7967a0b8ecc50f3ff274da11ad49ffa4085fc0094c0c6092db57b23`; byte/token counts and edge hashes were unchanged.

These differ from proc-096's ontology hashes above because the production prompt serializes unordered `Map.of` / `Map.copyOf` values across JVM launches. Observed context initially printed `baseEntityTypes` first; the final context printed `baseEntityParents` first. See `CorpusSchemaPromptBuilder.appendHierarchyVocabulary` and `nodeDefinitions` / `serializeValue`. This is not template drift or a renderer mismatch. Each four-way test constructs one production request and passes its identical serialized context to both engines and both templates. We deliberately did not sort/rewrite the production schema or prompt for this diagnostic. Consequently identical command settings across ontology runs do not prove byte-identical prompts across JVMs; do not attribute semantic-output differences solely to template changes.

## Single ontology diagnostic (proc-097)

After parity passed, `ModelToCrawlJvmIT#actualModelDerivesAccurateOntologyThroughProductionCrawlPath` ran once with the proc-078 model, sampling, schema, assertions, and resource settings unchanged; only `kompile.model.runtime.it.chatTemplateFile` selected this fixture. An outer 600-second process watchdog bounded the diagnostic (not model generation). It failed normally after 139.3 seconds of test execution, 142 seconds total, exit 1; no timeout.

The failure remained semantic: the frozen vocabulary lacked COMPANY. The node pass returned PERSON→PERSON and ORGANIZATION→ORGANIZATION, not COMPANY→ORGANIZATION. Relationship discovery/consolidation returned FOUNDED/OWNERSHIP; signature binding returned `1|10|11|1`, yielding ORGANIZATION→PERSON. The final logged document response classified Helios Dynamics as ORGANIZATION. These tool responses had empty parse-error lists, but that is not an ontology correctness pass.

Repeated Triton attention shared-memory admission errors also appeared; the same error already appeared 98 times in proc-078. Execution continued through six calls and shutdown. No native/backend change was attempted in this template diagnostic.

Logs: `.kompile/process-output/5673b0f4-c873-49dd-9a96-4948c41fbc56/proc-097.log`; rendering artifacts: `/tmp/gemma4-independent/template-parity/`. Original external template, GGUF, tokenizer, and SDZ were preserved. **No promotion or default template replacement.**
