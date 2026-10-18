package ai.kompile.cli.main.chat.tui;

import org.jline.terminal.Terminal;
import org.jline.terminal.impl.KittyGraphics;
import org.jline.terminal.impl.TerminalGraphicsManager;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.ArrayList;
import java.util.regex.Pattern;

/** Bounded raster preparation and a single surface-owned Kitty image/placement. */
final class KittyImagePreview {
    static final int MAX_FILE_BYTES = 5 * 1024 * 1024;
    static final long MAX_PIXELS = 32L * 1024 * 1024;
    static final int THUMBNAIL_PIXELS = 512;
    static final int CHUNK_BYTES = 4096;
    private static final AtomicInteger IDS = new AtomicInteger(
            ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE));
    private static final String APC = "\033_G";
    private static final String ST = "\033\\";
    static final int PLACEHOLDER = 0x10EEEE;
    // First 128 entries of Kitty's canonical row/column table. Also covers the
    // high byte of every positive 31-bit image ID; never depend on mark inference.
    // https://github.com/kovidgoyal/kitty/blob/master/gen/rowcolumn-diacritics.txt
    private static final char[] DIACRITICS = {
            0x0305, 0x030D, 0x030E, 0x0310, 0x0312, 0x033D, 0x033E, 0x033F,
            0x0346, 0x034A, 0x034B, 0x034C, 0x0350, 0x0351, 0x0352, 0x0357,
            0x035B, 0x0363, 0x0364, 0x0365, 0x0366, 0x0367, 0x0368, 0x0369,
            0x036A, 0x036B, 0x036C, 0x036D, 0x036E, 0x036F, 0x0483, 0x0484,
            0x0485, 0x0486, 0x0487, 0x0592, 0x0593, 0x0594, 0x0595, 0x0597,
            0x0598, 0x0599, 0x059C, 0x059D, 0x059E, 0x059F, 0x05A0, 0x05A1,
            0x05A8, 0x05A9, 0x05AB, 0x05AC, 0x05AF, 0x05C4, 0x0610, 0x0611,
            0x0612, 0x0613, 0x0614, 0x0615, 0x0616, 0x0617, 0x0657, 0x0658,
            0x0659, 0x065A, 0x065B, 0x065D, 0x065E, 0x06D6, 0x06D7, 0x06D8,
            0x06D9, 0x06DA, 0x06DB, 0x06DC, 0x06DF, 0x06E0, 0x06E1, 0x06E2,
            0x06E4, 0x06E7, 0x06E8, 0x06EB, 0x06EC, 0x0730, 0x0732, 0x0733,
            0x0735, 0x0736, 0x073A, 0x073D, 0x073F, 0x0740, 0x0741, 0x0743,
            0x0745, 0x0747, 0x0749, 0x074A, 0x07EB, 0x07EC, 0x07ED, 0x07EE,
            0x07EF, 0x07F0, 0x07F1, 0x07F3, 0x0816, 0x0817, 0x0818, 0x0819,
            0x081B, 0x081C, 0x081D, 0x081E, 0x081F, 0x0820, 0x0821, 0x0822,
            0x0823, 0x0825, 0x0826, 0x0827, 0x0829, 0x082A, 0x082B, 0x082C
    };

    record Image(byte[] png, String name, int width, int height) { }
    private record Placement(int top, int columns, int rows) { }

    private Image uploaded;
    private Terminal uploadedTo;
    private boolean uploadedViaTmux;
    private int imageId;
    private Placement placement;

    /** Fast eligibility only. Potentially blocking checks run on the preview worker. */
    static boolean eligible(Terminal terminal, String protocol) {
        if (terminal == null || "off".equals(protocol)) return false;
        try {
            String type = terminal.getType() == null ? "" : terminal.getType().toLowerCase(Locale.ROOT);
            boolean tmux = viaTmux(terminal);
            boolean zellij = present(terminal.getenv("ZELLIJ"));
            if (type.contains("dumb") || type.equals("ansi") || type.startsWith("vt10")
                    || present(terminal.getenv("STY")) || (tmux && zellij)) return false;
            if (zellij || tmux) return TerminalGraphicsManager.isJavaDesktopAvailable();
            if (type.contains("screen") || type.contains("tmux")) return false;
            return TerminalGraphicsManager.isJavaDesktopAvailable()
                    && ("kitty".equals(protocol) || new KittyGraphics().isSupported(terminal));
        } catch (RuntimeException | LinkageError unavailable) {
            return false;
        }
    }

    @FunctionalInterface
    interface CapabilityCommands {
        String run(List<String> command) throws IOException, InterruptedException;
        default String zellijExecutable() throws IOException { return runningZellij(); }
    }

    /** No terminal reads: bounded multiplexer IPC is captured on a background worker. */
    static boolean supported(Terminal terminal, String protocol) {
        return supported(terminal, protocol, KittyImagePreview::runCapabilityCommand);
    }

    static boolean supported(Terminal terminal, String protocol, CapabilityCommands commands) {
        if (!eligible(terminal, protocol)) return false;
        if ("kitty".equals(protocol)) return true;
        try {
            if (viaTmux(terminal)) return tmuxSupported(terminal, commands);
            if (present(terminal.getenv("ZELLIJ"))) {
                String binary = commands.zellijExecutable();
                return present(binary) && new KittyGraphics().isSupported(terminal)
                        && atLeast(commands.run(List.of(binary, "--version")), 0, 45, 1);
            }
            return true; // JLine's known-terminal detection already passed eligibility.
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException | RuntimeException | LinkageError unavailable) {
            return false;
        }
    }

    private static boolean tmuxSupported(Terminal terminal, CapabilityCommands commands)
            throws IOException, InterruptedException {
        String address = terminal.getenv("TMUX");
        int last = address.lastIndexOf(',');
        int split = last < 0 ? -1 : address.lastIndexOf(',', last - 1);
        String pane = terminal.getenv("TMUX_PANE");
        if (split < 1 || pane == null || !pane.matches("%[0-9]+")) return false;
        String socket = address.substring(0, split);
        if (!Path.of(socket).isAbsolute()) return false;
        List<String> base = List.of("tmux", "-S", socket);
        String[] server = commands.run(command(base, "display-message", "-p", "-t", pane,
                "#{session_id}\t#{allow-passthrough}\t#{version}")).strip().split("\t", -1);
        if (server.length != 3 || !server[0].matches("\\$[0-9]+")
                || !List.of("1", "2", "on", "all").contains(server[1]) || !atLeast(server[2], 3, 3, 0)) return false;
        String clients = commands.run(command(base, "list-clients", "-t", server[0], "-F",
                "#{client_termname}\t#{client_termfeatures}\t#{client_utf8}")).strip();
        if (clients.isEmpty()) return false; // No visible client can receive image data.
        for (String client : clients.split("\\R")) {
            String[] fields = client.split("\t", -1);
            if (fields.length != 3 || !fields[2].equals("1")
                    || !Pattern.compile("(?i)(?:^|[, ])(?:RGB|Tc)(?:$|[, ])").matcher(fields[1]).find()
                    || !List.of("xterm-kitty", "xterm-ghostty", "ghostty").contains(fields[0])) return false;
            // Honor a supplied old Kitty version rather than trusting the terminal name alone.
            String version = terminal.getenv("TERM_PROGRAM_VERSION");
            if (fields[0].equals("xterm-kitty") && present(version)
                    && "kitty".equalsIgnoreCase(terminal.getenv("TERM_PROGRAM"))
                    && !atLeast(version, 0, 28, 0)) return false;
        }
        return true;
    }

    private static List<String> command(List<String> base, String... arguments) {
        List<String> command = new ArrayList<>(base);
        command.addAll(List.of(arguments));
        return command;
    }

    private static boolean atLeast(String version, int major, int minor, int patch) {
        var match = Pattern.compile("^(?:(?:zellij|tmux) +)?([0-9]+)\\.([0-9]+)(?:\\.([0-9]+))?[A-Za-z0-9.+_-]*$")
                .matcher(version.strip());
        if (!match.matches()) return false;
        try {
            int a = Integer.parseInt(match.group(1)), b = Integer.parseInt(match.group(2));
            int c = match.group(3) == null ? 0 : Integer.parseInt(match.group(3));
            return a > major || (a == major && (b > minor || (b == minor && c >= patch)));
        } catch (NumberFormatException invalid) { return false; }
    }

    /** Inspect the running ancestor, not an unrelated/newer zellij executable on PATH. */
    private static String runningZellij() throws IOException {
        var process = ProcessHandle.current().parent();
        for (int depth = 0; process.isPresent() && depth < 64; depth++, process = process.get().parent()) {
            var info = process.get().info();
            String binary = info.command().orElse("");
            if (!present(binary) || !Path.of(binary).getFileName().toString().equals("zellij")) continue;
            if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) {
                return "/proc/" + process.get().pid() + "/exe";
            }
            // If the on-disk executable changed since startup, its version isn't authoritative.
            return info.startInstant().isPresent()
                    && !Files.getLastModifiedTime(Path.of(binary)).toInstant().isAfter(info.startInstant().get())
                    ? binary : "";
        }
        return "";
    }

    private static String runCapabilityCommand(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            process.getOutputStream().close(); // Never inherit or consume the chat's stdin.
            if (!process.waitFor(750, TimeUnit.MILLISECONDS)) throw new IOException("capability check timed out");
            byte[] output = process.getInputStream().readNBytes(8193);
            if (process.exitValue() != 0 || output.length > 8192) throw new IOException("capability check unavailable");
            return new String(output, java.nio.charset.StandardCharsets.UTF_8);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static boolean present(String value) { return value != null && !value.isBlank(); }

    private static boolean viaTmux(Terminal terminal) {
        return terminal != null && present(terminal.getenv("TMUX"));
    }

    /** Wrap each graphics command separately; cursor/text output must stay pane-local. */
    private static String graphics(String command, boolean tmux) {
        return tmux ? "\033Ptmux;" + command.replace("\033", "\033\033") + ST : command;
    }

    /** Decode the first frame only; inspect dimensions before allocating a raster. */
    static Image read(Path path) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) > MAX_FILE_BYTES) {
            throw new IOException("preview requires a regular image no larger than 5 MiB");
        }
        byte[] bytes;
        try (var input = Files.newInputStream(path)) {
            bytes = input.readNBytes(MAX_FILE_BYTES + 1);
        }
        if (bytes.length > MAX_FILE_BYTES) throw new IOException("image exceeds 5 MiB");
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("no installed decoder for this image format");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 32768 || height > 32768
                        || (long) width * height > MAX_PIXELS) {
                    throw new IOException("image dimensions exceed the preview limit");
                }
                var options = reader.getDefaultReadParam();
                int sample = Math.max(1, (Math.max(width, height) + THUMBNAIL_PIXELS - 1) / THUMBNAIL_PIXELS);
                options.setSourceSubsampling(sample, sample, 0, 0);
                BufferedImage raster = reader.read(0, options);
                try {
                    ByteArrayOutputStream png = new ByteArrayOutputStream();
                    if (!ImageIO.write(raster, "png", png) || png.size() > 1_500_000) {
                        throw new IOException("could not create a bounded PNG preview");
                    }
                    return new Image(png.toByteArray(), path.getFileName().toString(), width, height);
                } finally {
                    raster.flush();
                }
            } finally {
                reader.dispose();
            }
        }
    }

    /** Called only under the TUI draw lock; returns trusted controls, never transcript text. */
    String render(Image image, Terminal terminal, boolean enabled, int top, int columns, int rows) {
        if (image == null || !enabled || columns < 1 || rows < 1) return clear();
        StringBuilder output = new StringBuilder();
        if (uploadedTo != null && uploadedTo != terminal) {
            // Detach normally deletes on the old terminal. Never delete its IDs on a new one.
            invalidate();
        }
        boolean tmux = viaTmux(terminal);
        if (uploaded != image || uploadedViaTmux != tmux) {
            output.append(clear());
            imageId = IDS.updateAndGet(id -> id == Integer.MAX_VALUE ? 1 : id + 1);
            output.append(upload(image.png(), imageId, tmux));
            uploaded = image;
            uploadedTo = terminal;
            uploadedViaTmux = tmux;
        }
        Placement next = new Placement(top, Math.min(columns, 80), Math.min(rows, 20));
        if (!next.equals(placement)) {
            if (tmux) {
                // A virtual prototype has no screen coordinates. Sending ordinary
                // placements through passthrough would put them in the wrong pane.
                output.append(graphics(APC + "a=p,U=1,i=" + imageId + ",p=1,c="
                        + next.columns() + ",r=" + next.rows() + ",q=2" + ST, true));
            } else {
                output.append("\033[").append(top).append(";1H")
                        .append(APC).append("a=p,i=").append(imageId)
                        .append(",p=1,c=").append(next.columns()).append(",r=").append(next.rows())
                        .append(",C=1,q=2").append(ST);
            }
            placement = next;
        }
        // The TUI clears its text rows on every frame. Repaint placeholder cells
        // even when the uploaded bytes and virtual prototype can be reused.
        if (tmux) output.append(placeholderRows(imageId, next));
        return output.toString();
    }

    private static String placeholderRows(int id, Placement area) {
        StringBuilder rows = new StringBuilder();
        for (int row = 0; row < area.rows(); row++) {
            rows.append("\033[").append(area.top() + row).append(";1H")
                    .append("\033[0;38;2;").append((id >>> 16) & 255).append(';')
                    .append((id >>> 8) & 255).append(';').append(id & 255).append('m');
            for (int column = 0; column < area.columns(); column++) {
                rows.appendCodePoint(PLACEHOLDER).append(DIACRITICS[row])
                        .append(DIACRITICS[column]).append(DIACRITICS[id >>> 24]);
            }
            rows.append("\033[0m");
        }
        return rows.toString();
    }

    /** Delete only this surface's data, never all terminal images. */
    String clear() {
        String command = uploaded == null ? "" : graphics(
                APC + "a=d,d=I,i=" + imageId + ",q=2" + ST, uploadedViaTmux);
        invalidate();
        return command;
    }

    /** A full terminal clear can evict images; retransmit on the next frame. */
    void invalidate() {
        uploaded = null;
        uploadedTo = null;
        uploadedViaTmux = false;
        placement = null;
        imageId = 0;
    }

    private static String upload(byte[] png, int id, boolean tmux) {
        String encoded = Base64.getEncoder().encodeToString(png);
        StringBuilder output = new StringBuilder(encoded.length() + 256);
        for (int offset = 0; offset < encoded.length(); offset += CHUNK_BYTES) {
            int end = Math.min(encoded.length(), offset + CHUNK_BYTES);
            StringBuilder chunk = new StringBuilder(APC);
            if (offset == 0) chunk.append("a=t,t=d,f=100,i=").append(id).append(',');
            // Static continuation chunks may contain only m and q, not IDs/actions.
            chunk.append("q=2,m=").append(end == encoded.length() ? '0' : '1')
                    .append(';').append(encoded, offset, end).append(ST);
            output.append(graphics(chunk.toString(), tmux));
        }
        return output.toString();
    }
}
