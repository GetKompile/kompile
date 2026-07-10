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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;

/**
 * Streaming byte-level sanitizer for subprocess PTY output.
 * <p>
 * Rendering must stay byte-preserving: converting PTY output to String and
 * back can corrupt UTF-8 when a chunk splits a multibyte glyph. This strips
 * terminal queries that would make the real terminal respond on Kompile's
 * stdin, plus non-text terminal feature payloads (Kitty graphics, DCS/APC,
 * shell integration OSC markers) that some agent TUIs emit even when the
 * parent terminal does not support them. Ordinary repaint/movement/color
 * bytes still pass through unchanged.
 */
public final class TerminalQueryStripper {
    private static final int ESC = 0x1B;
    private static final int CSI_8_BIT = 0x9B;
    private static final int DCS_8_BIT = 0x90;
    private static final int SOS_8_BIT = 0x98;
    private static final int OSC_8_BIT = 0x9D;
    private static final int PM_8_BIT = 0x9E;
    private static final int APC_8_BIT = 0x9F;
    private static final Set<String> HOST_INPUT_MODES = Set.of(
            "9", "1000", "1001", "1002", "1003", "1004", "1005", "1006",
            "1007", "1015", "1016", "2004");
    private static final String HOST_INPUT_MODE_RESET = "\033[?9l\033[?1000l\033[?1001l"
            + "\033[?1002l\033[?1003l\033[?1004l\033[?1005l\033[?1006l"
            + "\033[?1007l\033[?1015l\033[?1016l\033[?2004l\033[?25h";

    private byte[] pending = new byte[0];

    public static String hostInputModeResetSequence() {
        return HOST_INPUT_MODE_RESET;
    }

    public void reset() {
        pending = new byte[0];
    }

    public TerminalQueryStripResult strip(byte[] data, int offset, int length) {
        if ((data == null || length <= 0) && pending.length == 0) {
            return TerminalQueryStripResult.EMPTY;
        }

        int safeLength = data == null ? 0 : Math.max(0, length);
        byte[] input = combine(data, offset, safeLength);
        int len = input.length;
        if (len == 0) return TerminalQueryStripResult.EMPTY;

        ByteArrayOutputStream display = new ByteArrayOutputStream(len);
        StringBuilder queries = new StringBuilder();
        int i = 0;
        while (i < len) {
            int b = input[i] & 0xFF;
            if (b == ESC) {
                if (i + 1 >= len) {
                    savePending(input, i, len);
                    break;
                }
                int next = input[i + 1] & 0xFF;
                if (next == '[') {
                    int end = findCsiEnd(input, i + 2, len);
                    if (end < 0) {
                        savePending(input, i, len);
                        break;
                    }
                    if (isCsiQuery(input, i + 2, end - 1)) {
                        appendQuery(queries, input, i, end);
                    } else if (!isCsiNonDisplay(input, i + 2, end - 1)) {
                        display.write(input, i, end - i);
                    }
                    i = end;
                    continue;
                }
                if (next == ']') {
                    int end = findOscEnd(input, i + 2, len);
                    if (end < 0) {
                        savePending(input, i, len);
                        break;
                    }
                    int contentEnd = oscContentEnd(input, end);
                    if (isOscQuery(input, i + 2, contentEnd)) {
                        appendQuery(queries, input, i, end);
                    } else if (!isOscNonDisplay(input, i + 2, contentEnd)) {
                        display.write(input, i, end - i);
                    }
                    i = end;
                    continue;
                }
                if (isEscControlStringStarter(next)) {
                    int end = findStringTerminator(input, i + 2, len);
                    if (end < 0) {
                        savePending(input, i, len);
                        break;
                    }
                    i = end;
                    continue;
                }

                display.write(input, i, 2);
                i += 2;
                continue;
            }

            if (b == CSI_8_BIT) {
                int end = findCsiEnd(input, i + 1, len);
                if (end < 0) {
                    savePending(input, i, len);
                    break;
                }
                if (isCsiQuery(input, i + 1, end - 1)) {
                    appendC1CsiQuery(queries, input, i + 1, end);
                } else if (!isCsiNonDisplay(input, i + 1, end - 1)) {
                    display.write(input, i, end - i);
                }
                i = end;
                continue;
            }

            if (b == OSC_8_BIT) {
                int end = findOscEnd(input, i + 1, len);
                if (end < 0) {
                    savePending(input, i, len);
                    break;
                }
                int contentEnd = oscContentEnd(input, end);
                if (isOscQuery(input, i + 1, contentEnd)) {
                    appendC1OscQuery(queries, input, i + 1, end);
                } else if (!isOscNonDisplay(input, i + 1, contentEnd)) {
                    display.write(input, i, end - i);
                }
                i = end;
                continue;
            }

            if (isC1ControlStringStarter(b)) {
                int end = findStringTerminator(input, i + 1, len);
                if (end < 0) {
                    savePending(input, i, len);
                    break;
                }
                i = end;
                continue;
            }

            display.write(b);
            i++;
        }

        return new TerminalQueryStripResult(display.toByteArray(), queries.toString());
    }

    private byte[] combine(byte[] data, int offset, int length) {
        if (pending.length == 0) {
            if (length == 0) return new byte[0];
            return Arrays.copyOfRange(data, offset, offset + length);
        }
        byte[] combined = new byte[pending.length + length];
        System.arraycopy(pending, 0, combined, 0, pending.length);
        if (length > 0 && data != null) {
            System.arraycopy(data, offset, combined, pending.length, length);
        }
        pending = new byte[0];
        return combined;
    }

    private void savePending(byte[] input, int start, int end) {
        pending = Arrays.copyOfRange(input, start, end);
    }

    private int findCsiEnd(byte[] input, int start, int len) {
        for (int j = start; j < len; j++) {
            int b = input[j] & 0xFF;
            if (b >= 0x40 && b <= 0x7E) {
                return j + 1;
            }
        }
        return -1;
    }

    private boolean isCsiQuery(byte[] input, int payloadStart, int finalIndex) {
        char finalByte = (char) (input[finalIndex] & 0xFF);
        String payload = ascii(input, payloadStart, finalIndex);
        if (finalByte == 'c') {
            return payload.isEmpty() || payload.equals("0") || payload.equals(">") || payload.equals(">0");
        }
        if (finalByte == 'n') {
            return payload.equals("5") || payload.equals("6");
        }
        if (finalByte == 'u') {
            return payload.equals("?");
        }
        if (finalByte == 't') {
            return payload.equals("14") || payload.equals("16") || payload.equals("18");
        }
        if (finalByte == 'p') {
            return payload.endsWith("$");
        }
        if (finalByte == 'q') {
            return payload.equals(">") || payload.equals(">0");
        }
        return false;
    }

    private boolean isCsiNonDisplay(byte[] input, int payloadStart, int finalIndex) {
        return isHostInputModeToggle(input, payloadStart, finalIndex)
                || isSgrMouseReport(input, payloadStart, finalIndex);
    }

    private boolean isHostInputModeToggle(byte[] input, int payloadStart, int finalIndex) {
        char finalByte = (char) (input[finalIndex] & 0xFF);
        if (finalByte != 'h' && finalByte != 'l') return false;
        String payload = ascii(input, payloadStart, finalIndex);
        if (!payload.startsWith("?")) return false;
        for (String mode : payload.substring(1).split(";")) {
            int colon = mode.indexOf(':');
            if (colon >= 0) mode = mode.substring(0, colon);
            if (HOST_INPUT_MODES.contains(mode)) return true;
        }
        return false;
    }

    private boolean isSgrMouseReport(byte[] input, int payloadStart, int finalIndex) {
        char finalByte = (char) (input[finalIndex] & 0xFF);
        if (finalByte != 'M' && finalByte != 'm') return false;
        String payload = ascii(input, payloadStart, finalIndex);
        if (!payload.startsWith("<")) return false;
        String[] parts = payload.substring(1).split(";");
        if (parts.length != 3) return false;
        for (String part : parts) {
            if (!part.matches("\\d+")) return false;
        }
        return true;
    }

    private boolean isEscControlStringStarter(int next) {
        return next == 'P' || next == '_' || next == '^' || next == 'X';
    }

    private boolean isC1ControlStringStarter(int b) {
        return b == DCS_8_BIT || b == SOS_8_BIT || b == PM_8_BIT || b == APC_8_BIT;
    }

    private int findStringTerminator(byte[] input, int start, int len) {
        for (int j = start; j < len; j++) {
            int b = input[j] & 0xFF;
            if (b == ESC && j + 1 < len && (input[j + 1] & 0xFF) == '\\') return j + 2;
        }
        return -1;
    }

    private int findOscEnd(byte[] input, int start, int len) {
        for (int j = start; j < len; j++) {
            int b = input[j] & 0xFF;
            if (b == 0x07) return j + 1;
            if (b == ESC && j + 1 < len && (input[j + 1] & 0xFF) == '\\') return j + 2;
        }
        return -1;
    }

    private int oscContentEnd(byte[] input, int sequenceEnd) {
        if (sequenceEnd >= 2
                && (input[sequenceEnd - 2] & 0xFF) == ESC
                && (input[sequenceEnd - 1] & 0xFF) == '\\') {
            return sequenceEnd - 2;
        }
        return sequenceEnd - 1;
    }

    private boolean isOscQuery(byte[] input, int contentStart, int contentEnd) {
        String content = ascii(input, contentStart, contentEnd);
        return content.startsWith("10;?")
                || content.startsWith("11;?")
                || content.startsWith("12;?");
    }

    private boolean isOscNonDisplay(byte[] input, int contentStart, int contentEnd) {
        String content = ascii(input, contentStart, contentEnd);
        return content.startsWith("66;")
                || (content.startsWith("4;") && content.endsWith(";?"));
    }

    private void appendQuery(StringBuilder queries, byte[] input, int start, int end) {
        queries.append(ascii(input, start, end));
    }

    private void appendC1CsiQuery(StringBuilder queries, byte[] input, int payloadStart, int end) {
        queries.append((char) ESC).append('[').append(ascii(input, payloadStart, end));
    }

    private void appendC1OscQuery(StringBuilder queries, byte[] input, int contentStart, int end) {
        queries.append((char) ESC).append(']').append(ascii(input, contentStart, end));
    }

    private String ascii(byte[] input, int start, int end) {
        if (end <= start) return "";
        return new String(input, start, end - start, StandardCharsets.US_ASCII);
    }
}
