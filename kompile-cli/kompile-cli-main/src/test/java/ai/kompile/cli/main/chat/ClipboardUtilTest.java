package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.ClipboardUtil.CopyResult;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A copy reads as done only when a native helper took the text. OSC 52 is written
 * blind (VTE terminals such as GNOME Terminal ignore it), so on its own it must not
 * produce the "Copied" notice.
 */
class ClipboardUtilTest {

    private static final List<String> X11_HELPERS = List.of("xclip", "xsel");

    @Test
    void onlyAConfirmedCopyReadsAsCopied() {
        assertEquals("✓ Copied 12 characters to the clipboard",
                ClipboardUtil.describe(CopyResult.COPIED, 12, X11_HELPERS));

        String osc52 = ClipboardUtil.describe(CopyResult.OSC52_ONLY, 12, X11_HELPERS);
        assertFalse(osc52.contains("✓") || osc52.contains("Copied"), osc52);
        assertTrue(osc52.contains("OSC 52") && osc52.contains("xclip/xsel"), osc52);

        String failed = ClipboardUtil.describe(CopyResult.FAILED, 12, X11_HELPERS);
        assertFalse(failed.contains("✓") || failed.contains("Copied"), failed);
        assertTrue(failed.contains("xclip/xsel"), failed);
    }

    @Test
    void aSessionWithoutHelpersSaysSo() {
        assertEquals("Copy failed: no clipboard helper for this session",
                ClipboardUtil.describe(CopyResult.FAILED, 3, List.of()));
    }

    @Test
    void onlySelectionServingHelpersAreDetached() {
        String[] xclip = {"xclip", "-selection", "clipboard"};
        String[] detached = ClipboardUtil.detached(xclip);
        if (detached.length != xclip.length) {
            assertTrue(detached[0].endsWith("/setsid"), String.join(" ", detached));
            assertArrayEquals(xclip, Arrays.copyOfRange(detached, 1, detached.length));
        } else {
            assertArrayEquals(xclip, detached, "no setsid: the helper runs as before");
        }
        assertArrayEquals(new String[]{"pbcopy"}, ClipboardUtil.detached(new String[]{"pbcopy"}));
        assertArrayEquals(new String[]{"clip.exe"}, ClipboardUtil.detached(new String[]{"clip.exe"}));
    }

    /**
     * The copy result is the helper's exit status, so setsid must exec the helper in
     * place: if it forked, it would exit 0 for a helper that failed. A ProcessBuilder
     * child never leads a process group, which is what keeps setsid from forking.
     */
    @Test
    void detachedHelperKeepsItsExitStatusAndLeadsItsOwnSession() throws Exception {
        String[] probe = ClipboardUtil.detached(new String[]{"xclip"});
        assumeTrue(probe.length == 2, "no setsid on this platform");

        Process process = new ProcessBuilder(probe[0], "sh", "-c",
                "read -r pid comm state ppid pgrp sid rest < /proc/$$/stat; "
                        + "if [ \"$sid\" = \"$$\" ]; then exit 7; else exit 9; fi")
                .redirectErrorStream(true)
                .start();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "setsid probe did not exit");
        assertEquals(7, process.exitValue(),
                "0 means setsid forked; 9 means the helper shares the chat's session: "
                        + new String(process.getInputStream().readAllBytes()));
    }
}
