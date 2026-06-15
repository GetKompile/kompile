# SDK Commands

`kompile sdk` manages the SDX Runtime SDK and SDZ model bundles for mobile and edge deployment.

## List available SDKs

```bash
kompile sdk list
kompile sdk list --platform=ios --type=sdk
```

## Download

```bash
kompile sdk download --model=qwen3-0.6b --platform=ios --chip=gpu
kompile sdk download --sdk-version=1.0 --output-dir=./sdk
```

## Scaffold a mobile app

Generate a mobile chat application project with SDX Runtime integration:

```bash
kompile sdk scaffold \
  --model=qwen3-0.6b \
  --platform=ios \
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
