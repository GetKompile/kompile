# Kompile Chat Local — Android

Jetpack Compose chat app that wires directly into `kompile-chat-local-core` and
`kompile-graph-reasoning-local`. Pure Java 17 / ART-safe; no Spring, no JPA, no Jackson.

## Current State

| Feature | Status | Notes |
|---------|--------|-------|
| Remote chat (OpenAI-compatible) | **Works** | `AndroidRemoteChatModel` — `HttpURLConnection`, zero deps |
| Graph tool bridge | **Works** | `GraphToolBridge.open(kgraph)` on ART |
| Local reasoning tools | **Works** | `kompile-graph-reasoning-local` is pure Java 17 |
| Local SDX generation | **Blocked** | Needs `libsdx_llm.so` ARM64 AAR (not yet published) |
| Streaming tokens | Not yet | `ChatModel.generateStreaming` collapses to one chunk; UI shows spinner |

---

## Build environment (proven)

| Tool | Version | Note |
|------|---------|------|
| JDK | 17 Temurin (Amazon Corretto also works) | GraalVM 17 **breaks** AGP 8.5 jlink transform |
| Android SDK | API 35, Build Tools 35.0.0 | `ANDROID_HOME=~/dev-apps/android-sdk` |
| AGP | 8.5.2 | warns on compileSdk=35; suppress with `android.suppressUnsupportedCompileSdk=35` |
| Gradle | 8.9 | wrapper at `gradle/wrapper/gradle-wrapper.properties` |
| Kotlin | 2.0.21 | K2 compiler (K1 flag removed; K2 works fine) |

**Do NOT use GraalVM JDK** for the Android build. The `jlink --disable-plugin system-modules`
step in AGP 8.5 crashes on GraalVM's jlink. Use Temurin 17 or Amazon Corretto 17.

---

## Prerequisites

1. Install Android SDK (idempotent — skip if already present):
   ```bash
   bash kompile-chat-local/mobile/android/setup-android.sh
   ```
   This installs `cmdline-tools`, `platform-tools`, `platforms;android-35`,
   `build-tools;35.0.0`, `emulator`, `system-images;android-35;google_apis;x86_64`,
   and creates the `kompile_test_35` AVD.

2. Install kompile JARs to mavenLocal (from repo root):
   ```bash
   JAVA_HOME=~/.sdkman/candidates/java/17.0.12-graal \
   /home/agibsonccc/dev-apps/mvn/bin/mvn \
     -pl kompile-chat-local/kompile-chat-local-core,\
   kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning-local,\
   kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning \
     install -DskipTests
   ```

---

## Step 1 — Generate gradlew (first time only)

The `gradlew` script is not committed. Generate it once using a local Gradle installation:

```bash
cd kompile-chat-local/mobile/android
JAVA_HOME=~/.sdkman/candidates/java/17.0.17-amzn \
~/.gradle/wrapper/dists/gradle-8.9-bin/*/gradle-8.9/bin/gradle wrapper
```

---

## Step 2 — Build APK

```bash
cd kompile-chat-local/mobile/android
JAVA_HOME=~/.sdkman/candidates/java/17.0.17-amzn \
ANDROID_HOME=~/dev-apps/android-sdk \
./gradlew :app:assembleDebug --no-daemon
# APK: app/build/outputs/apk/debug/app-debug.apk  (~29 MB)
```

---

## Step 3 — Run on emulator

### Boot the emulator

```bash
ANDROID_HOME=~/dev-apps/android-sdk
$ANDROID_HOME/emulator/emulator \
  -avd kompile_test_35 \
  -no-window -no-audio -no-boot-anim \
  -no-snapshot \
  -gpu swangle \
  -memory 2048 \
  -cores 2 &

# Wait for boot
until adb shell getprop sys.boot_completed 2>/dev/null | grep -q "^1$"; do sleep 5; done
```

### Install APK

```bash
ANDROID_HOME=~/dev-apps/android-sdk
export PATH=$ANDROID_HOME/platform-tools:$PATH
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Seed preferences (remote stub for testing)

The stub server at `tools/openai_stub.py` serves a toy OpenAI-compatible endpoint:
- Odd call: returns a `tool_calls` response requesting `ask_graph_query`
- Even call: returns a plain text final answer

Start the stub:
```bash
python3 kompile-chat-local/mobile/android/tools/openai_stub.py --port 8971
```

Seed app SharedPreferences (from a separate terminal):
```bash
PKG="ai.kompile.chat.local.android.debug"
adb shell "run-as $PKG sh -c 'mkdir -p /data/data/$PKG/shared_prefs && cat > /data/data/$PKG/shared_prefs/kompile_chat_prefs.xml'" << 'XML'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="remote_base_url">http://10.0.2.2:8971</string>
    <string name="remote_model">stub-model</string>
    <string name="remote_api_key"></string>
    <string name="kgraph_path"></string>
    <string name="model_path"></string>
    <int name="max_tool_rounds" value="3" />
    <float name="temperature" value="0.7" />
    <int name="max_tokens" value="512" />
    <boolean name="bootstrap_done" value="true" />
</map>
XML
```

Launch and send a message:
```bash
PKG="ai.kompile.chat.local.android.debug"
adb shell am start -n "$PKG/ai.kompile.chat.local.android.MainActivity"
sleep 3
adb shell input text "Who_does_Alice_work_for?"
adb shell input keyevent 66
sleep 30
adb shell screencap -p /sdcard/chat_result.png
adb pull /sdcard/chat_result.png .
```

---

## Step 4 — Configure (in-app settings)

1. Open the app and tap the Settings gear.
2. Enter a remote base URL (e.g. `http://your-server:11434` for Ollama, or
   `https://api.openai.com` for OpenAI).
3. Set the Model ID and API Key if required.
4. Optionally browse for a `.kgraph` file and/or model file.
5. Tap **Save**. The route badge in the top bar updates immediately.

---

## Known build quirks

### KDoc comments must not contain `/*` inside glob patterns
Kotlin's block-comment lexer is nestable (`/* ... /* ... */ ... */` increments depth).
Writing `assets/graphs/*.kgraph` inside a `/** ... */` KDoc opens a nested `/*` that
is never closed. Always write `assets/graphs/ (*.kgraph files)` or escape the glob
in the doc comment.

### JNA duplicate class
`kompile-chat-local-core` pulls `net.java.dev.jna:jna:5.14.0` as a plain JAR.
`app/build.gradle.kts` also adds `net.java.dev.jna:jna:5.14.0@aar` for native lib
unpacking. All three kompile deps must exclude the JAR form:
```kotlin
implementation("ai.kompile:kompile-chat-local-core:0.1.0-SNAPSHOT") {
    exclude(group = "net.java.dev.jna", module = "jna")
}
```

### Dorkbox / nd4j duplicate META-INF resources
`com.dorkbox:Annotations` and `com.dorkbox:Updates` both ship `LICENSE.*` and `NOTICE.*`.
nd4j sub-JARs each ship `META-INF/git.properties`. The `packaging.resources` block in
`app/build.gradle.kts` excludes these and uses `pickFirsts += "META-INF/**"` for the rest.

### GraalVM jlink crash (AGP 8.5 + compileSdk=35)
AGP 8.5 runs `jlink --disable-plugin system-modules` to build a JDK image for D8.
GraalVM's `jlink` does not support this flag and crashes with exit code 1.
**Always use Temurin 17 (or Amazon Corretto 17) for the Android build.**

### Emulator 36.6.x RenderThread SIGSEGV on Fedora 36 / kernel 6.2
All headless GPU modes (`swiftshader_indirect`, `swiftshader`, `software`, `off`, `lavapipe`)
crash in gfxstream's `RenderThread` with `SIGSEGV` on kernel 6.2.15 / Fedora 36.
Use `gpu=swangle` (ANGLE + SwiftShader, different code path) or `gpu=host` with a
visible X11 display. CI runs on `ubuntu-22.04` which does not have this issue.

---

## Local SDX generation (future)

When `libsdx_llm.so` is published as an AAR:

1. Drop `sdx-llm-android-<version>.aar` into `app/libs/`.
2. Browse to a `.gguf` or `.sdz` model file in Settings.
3. The route badge will switch to **LOCAL** automatically.

`SdxChatModelAndroid` guards `UnsatisfiedLinkError` at startup — it returns
`isAvailable() = false` when the library is absent and the router falls through to
remote transparently.

---

## Architecture notes

- **ChatModel seam**: `ai.kompile.chat.local.ChatModel` (Java interface) is the only
  contact point between the Android app and the inference backend. Android injects
  `SdxChatModelAndroid` (Kotlin/JNA) + `AndroidRemoteChatModel` (HttpURLConnection).

- **No java.net.http**: `RemoteChatModel` (core) uses `java.net.http.HttpClient` which
  is absent on ART. The Android port uses `AndroidRemoteChatModel` with `HttpURLConnection`.
  Never instantiate `RemoteChatModel` on Android.

- **`tool_result` role**: `ChatEngine` emits messages with `role = "tool_result"`.
  OpenAI's `/v1/chat/completions` does not accept this role; `AndroidRemoteChatModel`
  remaps it to `"user"` before sending.

- **org.json**: Android ships `org.json` as a system library. Do NOT add
  `org.json:json` to `dependencies {}` — it conflicts with the system version.

- **SLF4J**: `kompile-graph-reasoning-local` pulls `slf4j-api`. The app's
  `packaging.resources.excludes` drops the duplicate SLF4J service file.

- **ABI filters**: only `arm64-v8a` and `x86_64` are included.

---

## File tree

```
mobile/android/
├── README.md                         (this file)
├── build.gradle.kts                  (plugin version declarations)
├── settings.gradle.kts               (includes :app, mavenLocal repo)
├── gradle.properties
├── gradlew / gradlew.bat             (generated once with `gradle wrapper`)
├── gradle/wrapper/
│   └── gradle-wrapper.properties     (Gradle 8.9)
├── setup-android.sh                  (idempotent SDK + AVD bootstrap)
├── tools/
│   └── openai_stub.py                (stateful OpenAI stub for e2e tests)
└── app/
    ├── build.gradle.kts              (deps: compose BOM, JNA @aar, kompile JARs)
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml       (usesCleartextTraffic=true for stub on 10.0.2.2)
        ├── assets/
        │   ├── graphs/               (drop .kgraph here — bootstrapped on first run)
        │   └── models/               (drop model files here — bootstrapped on first run)
        ├── res/
        │   ├── drawable/ic_launcher_foreground.xml
        │   ├── mipmap-anydpi-v26/{ic_launcher,ic_launcher_round}.xml
        │   └── values/{colors,strings,themes}.xml
        └── java/ai/kompile/chat/local/android/
            ├── KompileChatApplication.kt
            ├── MainActivity.kt
            ├── model/
            │   ├── AndroidRemoteChatModel.kt   -- HttpURLConnection remote model
            │   └── SdxChatModelAndroid.kt      -- JNA local model (SDX C ABI)
            ├── prefs/
            │   └── AppPreferences.kt           -- SharedPreferences wrapper
            ├── ui/
            │   ├── navigation/AppNavigation.kt
            │   ├── screens/
            │   │   ├── ChatScreen.kt           -- messages + tool-round cards
            │   │   └── SettingsScreen.kt       -- SAF pickers + sliders
            │   └── theme/Theme.kt
            └── viewmodel/
                ├── UiModels.kt                 -- UiMessage / ToolRoundUi data classes
                └── ChatViewModel.kt            -- ChatEngine wiring + lifecycle
```
