/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.cli.main.project;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;

/**
 * Minimal, dependency-free RTF-to-plain-text converter.
 *
 * <p>There is no AWT/Swing anywhere in this class (unlike {@code javax.swing.text.rtf.RTFEditorKit},
 * the JDK's own RTF reader) - a hand-rolled tokenizer is used instead so RTF attachments can be read
 * on the native-image CLI path, where loading any AWT class risks the same fatal, non-catchable JNI
 * abort documented on the PDFBox path (see {@link LocalDocumentLoaderRegistry#load}).</p>
 *
 * <p>Supported subset, deliberately narrow:</p>
 * <ul>
 *   <li>Brace {@code { }} groups, with skip-destinations ({@code {\*...}} and the named destinations
 *       {@code fonttbl}, {@code colortbl}, {@code stylesheet}, {@code info}, {@code pict},
 *       {@code object}, {@code themedata}, {@code datastore}, {@code xmlnstbl}, {@code listtable},
 *       {@code listoverridetable}, {@code rsidtbl}, {@code generator}) discarded in full, including
 *       everything nested inside them.</li>
 *   <li>{@code \par}, {@code \line}, {@code \sect}, {@code \page} to a newline; {@code \tab} to a tab;
 *       {@code \cell} to {@code " | "}; {@code \row} to a newline.</li>
 *   <li>{@code \'hh} hex-escaped bytes, decoded through the document's code page
 *       ({@code \ansicpg<N>}, default windows-1252) - consecutive {@code \'hh} escapes are collected
 *       into one byte run and decoded together, since a multi-byte code page can split one character
 *       across several escapes.</li>
 *   <li>{@code \\uN}, a signed 16-bit Unicode code unit ({@code N < 0} means {@code N + 65536}), plus
 *       its {@code \\ucN} fallback-skip count (default 1): the {@code N} characters/control words
 *       immediately following a {@code \\u} are the pre-Unicode fallback text and are discarded.</li>
 *   <li>Escaped literals {@code \\}, {@code \{}, {@code \}}; {@code \~} to a no-break space;
 *       {@code \-} dropped (optional hyphen); {@code \_} to a plain hyphen.</li>
 * </ul>
 *
 * <p>Anything else - font/color tables, formatting control words such as {@code \b} or {@code \fs24},
 * named symbol control words such as {@code \emdash} - is silently ignored rather than guessed at;
 * this is a text extractor, not a renderer.</p>
 */
public final class RtfText {
    private RtfText() {
    }

    /**
     * Converts one RTF document to plain text. Returns {@code ""} for {@code null} or empty input,
     * and never throws on malformed input - unbalanced braces or truncated escapes fall back to
     * whatever text was already recovered rather than raising an exception, since a best-effort
     * extraction is more useful than none for a crawl pipeline.
     */
    public static String toPlainText(String rtf) {
        if (rtf == null || rtf.isEmpty()) return "";
        return new Parser(rtf).parse();
    }

    /** Destinations whose entire content - including nested groups - is never document text. */
    private static final Set<String> SKIP_DESTINATIONS = Set.of(
            "fonttbl", "colortbl", "stylesheet", "info", "pict", "object",
            "themedata", "datastore", "xmlnstbl", "listtable", "listoverridetable",
            "rsidtbl", "generator");

    private static final Charset DEFAULT_CHARSET = safeCharset("windows-1252");

    private static Charset safeCharset(String name) {
        try {
            return Charset.forName(name);
        } catch (Exception e) {
            return StandardCharsets.ISO_8859_1;
        }
    }

    /** Maps an {@code \ansicpg} value to a JDK charset name, falling back to windows-1252. */
    private static Charset charsetForCodePage(int codepage) {
        String name = switch (codepage) {
            case 1252 -> "windows-1252";
            case 1250 -> "windows-1250";
            case 1251 -> "windows-1251";
            case 1253 -> "windows-1253";
            case 1254 -> "windows-1254";
            case 1255 -> "windows-1255";
            case 1256 -> "windows-1256";
            case 1257 -> "windows-1257";
            case 1258 -> "windows-1258";
            case 874 -> "windows-874";
            case 932 -> "Shift_JIS";
            case 936 -> "GBK";
            case 949 -> "EUC-KR";
            case 950 -> "Big5";
            case 65001 -> "UTF-8";
            case 10000 -> "MacRoman";
            case 28591 -> "ISO-8859-1";
            default -> "windows-1252";
        };
        return safeCharset(name);
    }

    /** Per-group inherited state: whether the group's text is discarded, and the active \\ucN skip count. */
    private static final class GroupState {
        boolean skip;
        int ucSkip;

        GroupState(boolean skip, int ucSkip) {
            this.skip = skip;
            this.ucSkip = ucSkip;
        }
    }

    private static final class Parser {
        private final String s;
        private final int n;
        private int i;
        private final StringBuilder out = new StringBuilder();
        private final Deque<GroupState> stack = new ArrayDeque<>();
        private final ByteArrayOutputStream hexRun = new ByteArrayOutputStream();
        private Charset charset = DEFAULT_CHARSET;
        /** Set by a bare {@code \*}; consumed by the next control word, marking its group skipped. */
        private boolean pendingStarDestination;
        /** Number of upcoming units (chars, control words, or symbols) still to discard as \\u fallback. */
        private int pendingSkip;

        Parser(String rtf) {
            this.s = rtf;
            this.n = rtf.length();
            stack.push(new GroupState(false, 1));
        }

        String parse() {
            while (i < n) {
                char c = s.charAt(i);
                if (c == '{') {
                    pushGroup();
                    i++;
                    continue;
                }
                if (c == '}') {
                    popGroup();
                    i++;
                    continue;
                }
                if (c == '\\') {
                    parseBackslashEscape();
                    continue;
                }
                // Raw CR/LF in the source is formatting whitespace for human readability of the
                // .rtf file, not document content - \par is what encodes an actual paragraph break.
                if (c == '\r' || c == '\n') {
                    i++;
                    continue;
                }
                i++;
                if (pendingSkip > 0) {
                    pendingSkip--;
                } else {
                    emit(String.valueOf(c));
                }
            }
            flushHexRun();
            return out.toString();
        }

        private void parseBackslashEscape() {
            i++; // consume backslash
            if (i >= n) return;
            char next = s.charAt(i);
            if (next == '\'') {
                i++;
                int b = readHex2();
                if (pendingSkip > 0) {
                    pendingSkip--;
                } else if (!currentGroup().skip) {
                    hexRun.write(b);
                }
                return;
            }
            if (next == '\\' || next == '{' || next == '}') {
                i++;
                if (pendingSkip > 0) {
                    pendingSkip--;
                } else {
                    emit(String.valueOf(next));
                }
                return;
            }
            if (next == '~') {
                i++;
                if (pendingSkip > 0) {
                    pendingSkip--;
                } else {
                    emit(" ");
                }
                return;
            }
            if (next == '_') {
                i++;
                if (pendingSkip > 0) {
                    pendingSkip--;
                } else {
                    emit("-");
                }
                return;
            }
            if (next == '-') {
                // Optional hyphen: never visible, but a hex run in progress still ends here.
                i++;
                if (pendingSkip > 0) {
                    pendingSkip--;
                } else {
                    flushHexRun();
                }
                return;
            }
            if (next == '*') {
                // Modifies the very next control word, not a unit of its own.
                i++;
                pendingStarDestination = true;
                return;
            }
            if (Character.isLetter(next)) {
                flushHexRun();
                String name = readControlWordName();
                Integer param = readOptionalSignedInt();
                consumeOptionalTrailingSpace();
                if (pendingSkip > 0) {
                    pendingSkip--;
                } else {
                    processControlWord(name, param);
                }
                return;
            }
            // Unrecognized control symbol (single non-alphanumeric char): consume and ignore.
            i++;
            flushHexRun();
            if (pendingSkip > 0) {
                pendingSkip--;
            }
        }

        private void processControlWord(String name, Integer param) {
            boolean marksDestination = pendingStarDestination || SKIP_DESTINATIONS.contains(name);
            pendingStarDestination = false;
            if (marksDestination) {
                currentGroup().skip = true;
            }
            switch (name) {
                case "ansicpg":
                    if (param != null) charset = charsetForCodePage(param);
                    return;
                case "uc":
                    if (param != null) currentGroup().ucSkip = Math.max(0, param);
                    return;
                case "u":
                    handleUnicodeChar(param == null ? 0 : param);
                    return;
                default:
                    break;
            }
            if (currentGroup().skip) return;
            switch (name) {
                case "par", "line", "sect", "page" -> out.append('\n');
                case "tab" -> out.append('\t');
                case "cell" -> out.append(" | ");
                case "row" -> out.append('\n');
                default -> { /* formatting/destination control word with no text equivalent */ }
            }
        }

        private void handleUnicodeChar(int param) {
            char ch = (char) (param & 0xFFFF);
            if (!currentGroup().skip) {
                out.append(ch);
            }
            pendingSkip = currentGroup().ucSkip;
        }

        private void emit(String text) {
            flushHexRun();
            if (!currentGroup().skip) out.append(text);
        }

        private void pushGroup() {
            flushHexRun();
            GroupState parent = currentGroup();
            stack.push(new GroupState(parent.skip, parent.ucSkip));
        }

        private void popGroup() {
            flushHexRun();
            if (stack.size() > 1) stack.pop();
        }

        private GroupState currentGroup() {
            return stack.peek();
        }

        private void flushHexRun() {
            if (hexRun.size() == 0) return;
            out.append(new String(hexRun.toByteArray(), charset));
            hexRun.reset();
        }

        private int readHex2() {
            if (i + 1 >= n) {
                i = n;
                return 0;
            }
            int hi = Character.digit(s.charAt(i), 16);
            int lo = Character.digit(s.charAt(i + 1), 16);
            i += 2;
            return hi < 0 || lo < 0 ? 0 : (hi << 4) | lo;
        }

        private String readControlWordName() {
            int start = i;
            while (i < n && Character.isLetter(s.charAt(i))) i++;
            return s.substring(start, i);
        }

        private Integer readOptionalSignedInt() {
            int start = i;
            if (i < n && s.charAt(i) == '-') i++;
            int digitsStart = i;
            while (i < n && Character.isDigit(s.charAt(i))) i++;
            if (i == digitsStart) {
                i = start;
                return null;
            }
            return Integer.parseInt(s.substring(start, i));
        }

        private void consumeOptionalTrailingSpace() {
            if (i < n && s.charAt(i) == ' ') i++;
        }
    }
}
