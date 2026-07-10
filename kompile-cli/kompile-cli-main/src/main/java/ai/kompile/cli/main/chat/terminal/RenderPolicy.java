/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.terminal;

import ai.kompile.cli.main.chat.tui.VirtualTerminal;

import java.util.function.Consumer;

/**
 * L2 of the Terminal Session Framework — the "reproduce the agent UI" axis (design §4.3). A
 * hot-swappable strategy that decides HOW a frame reaches the real terminal, decoupled from the
 * output pump so {@code /render} can swap the object at runtime without touching input/turn/enforcer
 * layers:
 *
 * <ul>
 *   <li>{@link Mode#RAW} — forward the (query-stripped) display bytes verbatim to the terminal; the
 *       agent paints its own screen (used when the decoder reports {@code renderRawTui()==true}).
 *       No settle gate.</li>
 *   <li>{@link Mode#MIRROR} — settle-gate, then blit the agent's shadow screen verbatim into
 *       Kompile's scroll region (Kompile keeps its bars + input box). The recommended TUI default:
 *       reproduce the agent UI while owning input.</li>
 *   <li>{@link Mode#DECODED} — settle-gate, then decoder extract → Kompile-owned transcript scroll.
 *       Robust fallback with Kompile-owned scrollback.</li>
 * </ul>
 *
 * <p>The actual rendering stays in the host (it is entangled with chrome geometry, the draw lock,
 * status/cursor restore); a policy reaches it through the sinks wired at construction. The host
 * builds the three policies once and swaps {@code current} on {@code /render}. Settle-window / time-cap
 * constants live on {@link FrameSettleGate}.</p>
 */
public final class RenderPolicy {

    public enum Mode { RAW, MIRROR, DECODED }

    private final Mode mode;
    private final long settleWindowMs;
    private final long timeCapMs;
    private final Consumer<byte[]> rawSink;              // used when RAW
    private final Consumer<VirtualTerminal> frameSink;   // used when MIRROR / DECODED

    private RenderPolicy(Mode mode, long settleWindowMs, long timeCapMs,
                         Consumer<byte[]> rawSink, Consumer<VirtualTerminal> frameSink) {
        this.mode = mode;
        this.settleWindowMs = settleWindowMs;
        this.timeCapMs = timeCapMs;
        this.rawSink = rawSink;
        this.frameSink = frameSink;
    }

    /** Raw byte-forward policy. {@code rawSink} writes display bytes to the terminal under the draw lock. */
    public static RenderPolicy raw(Consumer<byte[]> rawSink) {
        return new RenderPolicy(Mode.RAW, 0L, 0L, rawSink, null);
    }

    /** Mirror policy (verbatim blit into the scroll region). */
    public static RenderPolicy mirror(Consumer<VirtualTerminal> frameSink) {
        return new RenderPolicy(Mode.MIRROR, FrameSettleGate.MIRROR_SETTLE_MS,
                FrameSettleGate.MIRROR_TIME_CAP_MS, null, frameSink);
    }

    /** Decoded policy (decoder extract → transcript scroll). */
    public static RenderPolicy decoded(Consumer<VirtualTerminal> frameSink) {
        return new RenderPolicy(Mode.DECODED, FrameSettleGate.DECODED_SETTLE_MS,
                FrameSettleGate.DECODED_TIME_CAP_MS, null, frameSink);
    }

    public Mode mode() {
        return mode;
    }

    public boolean isRaw() {
        return mode == Mode.RAW;
    }

    public long settleWindowMs() {
        return settleWindowMs;
    }

    public long timeCapMs() {
        return timeCapMs;
    }

    public String name() {
        return mode.name().toLowerCase(java.util.Locale.ROOT);
    }

    /** Forward display bytes verbatim (RAW only). */
    public void applyRaw(byte[] displayBytes) {
        if (rawSink != null) rawSink.accept(displayBytes);
    }

    /** Render a settled frame from the shadow terminal (MIRROR / DECODED). */
    public void applyFrame(VirtualTerminal vt) {
        if (frameSink != null) frameSink.accept(vt);
    }
}
