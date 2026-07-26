# SDK Commands

`kompile sdk` manages the SDX Runtime SDK and SDZ model bundles for mobile and edge deployment.

## List available SDKs

```bash
kompile sdk list
kompile sdk list --platform=ios --type=sdk
```

## Download

```bash
kompile sdk download --model=qwen3-0.6b --platform=ios-arm64 \
  --component=runtime --package-role=apple-xcframework --variant=cpu
kompile sdk download --model=qwen3-0.6b --platform=linux-x86_64 \
  --sdk-version=1.0 --component=runtime --package-role=platform-sdk --output-dir=./sdk
```

The CLI fetches `sdk-v<version>/sdx-sdk-manifest.json`, selects by component,
package role, platform, and variant, then downloads the manifest's exact `fileName`
and verifies SHA-256. `--chip` remains a compatibility alias for `--variant`.
CPU is inferred only when it is the sole manifest variant for the selection.

## Scaffold a mobile app

Generate a mobile chat application project with SDX Runtime integration:

```bash
kompile sdk scaffold \
  --model=qwen3-0.6b \
  --platform=ios \
  --sdk-component=runtime \
  --sdk-package-role=apple-xcframework \
  --sdk-variant=cpu \
  --project-name=MyChatApp \
  --package-name=com.example.chat \
  --mode=chat \
  --include-model
```

## Serve locally

Launch an OpenAI-compatible API server for local LLM inference using the SDK:

```bash
kompile sdk serve \
  --model-path=./model.sdz \
  --port=8000 \
  --temperature=0.7 \
  --max-tokens=2048
```
