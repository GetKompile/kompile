# Offline model and project import

The application ships without production model weights. A new install opens the import/bootstrap screen and remains usable for project and knowledge-base setup before a native graph session exists. Import failures are shown as actionable validation messages and retained in the in-app diagnostics log.

## Two independent acquisition and execution paths

Hugging Face and Kompile staging represent different artifact layers and are not chained by the APK:

- **Direct Hugging Face model** — paste `owner/repository` or a canonical repository, tree, blob, or resolve URL. Repository and tree references query only the public `huggingface.co` model API, pin its returned immutable commit, discover GGUF/GGML files, and require an explicit choice when several quantizations exist. Exact blob/resolve references retain their requested revision and bypass metadata lookup. The app streams the selected file into private storage, loads it through the bundled SDX ABI, renders the model-owned chat template, performs a bounded real decode, and activates that exact decoded session. No browser or Kompile server participates.
- **Prepared Kompile artifact** — optionally open a configured Kompile artifact service in a browser to retrieve an already target-prepared `.sdz` or `.kproject`. This handoff carries only the APK target profile and requested artifact kind; it sends no Hugging Face repository, GGML URL, credentials, or component bundle.

The APK uses `INTERNET` only for public Hugging Face metadata and the selected model bytes. It accepts no Hugging Face token, follows only strict HTTPS Hugging Face redirects, and disables cleartext traffic. Transfer is cancellable and size-bounded, writes a temporary app-owned file, and publishes it atomically. Persistence changes only after SDX load, template rendering, and non-empty decode succeed; failure deletes the new model and restores the previous selection/runtime. Inference, graph reasoning, and imported project use remain local.

Repository and tree references are resolved rather than rejected. A single GGUF/GGML candidate starts the in-app import directly; multiple candidates remain visible until the user chooses one. Safely percent-encoded filenames are supported, and slash-bearing revisions must encode the slash (for example, `refs%2Fpr%2F123`). Encoded path separators, traversal, credentials, tokens, fragments, ports, arbitrary queries, and non-Hugging-Face hosts are rejected; only `download=true` on a resolve URL is accepted. Split `-00001-of-000NN.gguf` shard sets are never offered as standalone downloads because one shard is not a complete model.

## Preferred: full project archive

A `.kproject` archive is the complete portable unit. Import restores the canonical model or compiled provider artifacts, tokenizer assets, generation/chat configuration, Markdown fact sheets, captured source records, graph snapshot, and project metadata. Export and import are transactional: content is staged into a private generation directory, validated by digest and schema, then atomically published. A model-only file is never presented as a full project.

Knowledge-base sync should exchange `.kproject` archives or server-generated fact-sheet/graph deltas. Crawling and broad source ingestion belong on a workstation or synchronization service, not on the offline phone. On-device users can create and edit Markdown fact sheets, attach or capture individual sources, and rebuild the local graph from synchronized material.

## Direct GGUF/GGML SDX execution contract

The Android raw-model route is the canonical DL4J SDX path:

`libsdx_llm` ABI v2 → `SdxLlmCore` → `GGMLModelImport` → `GenerationPipeline`

A directly imported model must contain enough supported tokenizer metadata to reproduce the model's text protocol, including vocabulary/merges where required, special-token IDs and types, and its chat template. SDX reconstructs the tokenizer configuration from the model metadata and uses that exact template for both the activation probe and later conversation turns. Unsupported embedded tokenizer families, absent templates, invalid token IDs, or incomplete legacy GGML metadata fail explicitly; the app never invents token IDs or falls back to whitespace tokenization.

This provider-independent raw route is packaged as `SDX_GGUF_AOT` in every APK flavor. It is an explicit execution route, not a fallback from a failed accelerator provider. Prepared `.sdz` remains a separate contract: Vulkan, Hexagon, and Tensor G5 packages carry their compiled provider artifacts, while Tensor G3 carries the validated NNAPI policy/derived graph contract and lets the Android driver perform only final device compilation. Prepared cache identity includes the canonical model digest, tokenizer/config digest, provider/compiler identity, target SoC, quantization contract, and artifact format version.

## Chat readiness

A direct Hugging Face model becomes active only after all of these checks succeed:

1. The app-owned file passes path, size, and GGUF/GGML magic validation.
2. `libsdx_llm` ABI v2 creates a runtime and SDX imports the model.
3. The embedded tokenizer and model-owned chat template load and render a user turn.
4. The same loaded session returns non-empty text from a bounded deterministic decode.
5. That exact decoded session is installed in `ChatEngine`, then and only then is the model path persisted.

A prepared `.sdz`/`.kproject` additionally requires its schema and SHA-256 validation, a provider manifest matching the APK flavor, compiled artifacts matching the model digest and target SoC, and successful graph-AOT opening. Diagnostics distinguish transfer, raw SDX load/template/decode, unsupported tokenizer metadata, failed target preparation, accelerator/provider mismatch, graph corruption, and native load failure. An empty project prompts for import or acquisition; it is not reported as “native graph session cannot be opened.”
