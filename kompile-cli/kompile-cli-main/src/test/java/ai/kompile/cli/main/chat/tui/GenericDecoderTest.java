package ai.kompile.cli.main.chat.tui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Synthetic-screen tests for {@link GenericDecoder} — the fallback decoder used for unknown
 * agent CLIs. Tests feed escape sequences into a {@link VirtualTerminal} and verify that the
 * prompt-detection machinery inherited from {@link AbstractTuiDecoder} works correctly through
 * the generic decoder.
 *
 * <p>Prompt shapes covered:
 * <ul>
 *   <li>Unbordered numbered menu with a selection cursor (❯ 1. Yes)</li>
 *   <li>Box-bordered numbered menu (│ ❯ 1. Yes │)</li>
 *   <li>y/n confirmation ("Proceed? (y/n)")</li>
 *   <li>"Press Enter to continue" affordance</li>
 *   <li>Plain-question fallback (last content line ending with '?')</li>
 *   <li>False-positive guards: numbered list in prose without a cursor, and a question
 *       quoted mid-paragraph while a spinner/generation marker is present</li>
 * </ul>
 */
class GenericDecoderTest {

    // ------------------------------------------------------------------
    // 1. Unbordered numbered menu (❯ 1. Yes / 2. No)
    // ------------------------------------------------------------------

    @Test
    void detectsUnborderedNumberedMenu_isAwaiting() {
        GenericDecoder d = new GenericDecoder();
        VirtualTerminal vt = new VirtualTerminal(20, 80);

        vt.feed("\033[5;1HApply this patch?");
        vt.feed("\033[7;1H❯ 1. Yes, apply");
        vt.feed("\033[8;1H  2. No, skip");

        assertTrue(d.isAwaitingUserInput(vt), "numbered menu must be detected as awaiting input");
    }

    @Test
    void extractsUnborderedMenuQuestionAndOptions() {
        GenericDecoder d = new GenericDecoder();
        VirtualTerminal vt = new VirtualTerminal(20, 80);

        vt.feed("\033[5;1HApply this patch?");
        vt.feed("\033[7;1H❯ 1. Yes, apply");
        vt.feed("\033[8;1H  2. No, skip");

        String prompt = d.extractPromptText(vt);
        assertTrue(prompt.contains("Apply this patch?"), "question line must appear in prompt text: " + prompt);
        assertTrue(prompt.contains("1. Yes, apply"), "option 1 must appear: " + prompt);
        assertTrue(prompt.contains("2. No, skip"), "option 2 must appear: " + prompt);
    }

    @Test
    void readsHighlightedDigitFromUnborderedMenu() {
        GenericDecoder d = new GenericDecoder();

        // Cursor on option 1
        VirtualTerminal onOne = new VirtualTerminal(20, 80);
        onOne.feed("\033[7;1H❯ 1. Yes, apply");
        onOne.feed("\033[8;1H  2. No, skip");
        assertEquals("1", d.selectedOptionDigit(onOne), "must read digit 1 from ❯-highlighted row");

        // Cursor on option 2
        VirtualTerminal onTwo = new VirtualTerminal(20, 80);
        onTwo.feed("\033[7;1H  1. Yes, apply");
        onTwo.feed("\033[8;1H❯ 2. No, skip");
        assertEquals("2", d.selectedOptionDigit(onTwo), "must read digit 2 from ❯-highlighted row");
    }

    // ------------------------------------------------------------------
    // 2. Box-bordered numbered menu (│ ❯ 1. Yes │)
    // ------------------------------------------------------------------

    @Test
    void detectsBoxBorderedNumberedMenu_isAwaiting() {
        GenericDecoder d = new GenericDecoder();
        VirtualTerminal vt = new VirtualTerminal(20, 80);

        vt.feed("\033[5;1H│ Delete this file? │");
        vt.feed("\033[7;1H│ ❯ 1. Yes │");
        vt.feed("\033[8;1H│   2. No  │");

        assertTrue(d.isAwaitingUserInput(vt), "box-bordered numbered menu must be detected as awaiting input");
    }

    @Test
    void extractsBoxBorderedMenuQuestionAndOptions() {
        GenericDecoder d = new GenericDecoder();
        VirtualTerminal vt = new VirtualTerminal(20, 80);

        vt.feed("\033[5;1H│ Delete this file? │");
        vt.feed("\033[7;1H│ ❯ 1. Yes │");
        vt.feed("\033[8;1H│   2. No  │");

        String prompt = d.extractPromptText(vt);
        assertTrue(prompt.contains("1. Yes"), "option 1 must appear after box-border strip: " + prompt);
        assertTrue(prompt.contains("2. No"), "option 2 must appear after box-border strip: " + prompt);
    }

    @Test
    void readsHighlightedDigitFromBoxBorderedMenu() {
        GenericDecoder d = new GenericDecoder();
        VirtualTerminal vt = new VirtualTerminal(20, 80);

        // Box-bordered: "│ ❯ 1. Yes │" and "│   2. No  │"
        vt.feed("\033[7;1H│ ❯ 1. Yes │");
        vt.feed("\033[8;1H│   2. No  │");

        assertEquals("1", d.selectedOptionDigit(vt),
                "must read digit 1 after stripping │ borders: " + d.selectedOptionDigit(vt));
    }

    // ------------------------------------------------------------------
    // 3. y/n confirmation prompt
    // ------------------------------------------------------------------

    @Test
    void detectsYnConfirmationPrompt_isAwaiting() {
        GenericDecoder d = new GenericDecoder();
        VirtualTerminal vt = new VirtualTerminal(20, 80);

        vt.feed("\033[8;1HOverwrite existing config? (y/n)");

        assertTrue(d.isAwaitingUserInput(vt), "(y/n) confirmation must be detected as awaiting input");
    }

    @Test
    void detectsYesNoConfirmationPrompt_isAwaiting() {
        GenericDecoder d = new GenericDecoder();
        VirtualTerminal vt = new VirtualTerminal(20, 80);

        vt.feed("\033[8;1HProceed with installation? (yes/no)");

        assertTrue(d.isAwaitingUserInput(vt), "(yes/no) confirmation must be detected as awaiting input");
    }

    @Test
    void detectsBracketedYnVariant_isAwaiting() {
        GenericDecoder d = new GenericDecoder();
        VirtualTerminal vt = new VirtualTerminal(20, 80);

        vt.feed("\033[8;1HRemove all temporary files? [y/n]");

        assertTrue(d.isAwaitingUserInput(vt), "[y/n] confirmation must be detected as awaiting input");
    }

    // ------------------------------------------------------------------
    // 4. "Press Enter to continue" affordance
    // ------------------------------------------------------------------

    @Test
    void detectsPressEnterToContinue_isAwaiting() {
        GenericDecoder d = new GenericDecoder();
        VirtualTerminal vt = new VirtualTerminal(20, 80);

        vt.feed("\033[5;1HInstallation complete.");
        vt.feed("\033[8;1HPress Enter to continue");

        assertTrue(d.isAwaitingUserInput(vt), "'Press Enter to continue' must be detected as awaiting input");
    }

    @Test
    void detectsEnterToConfirmAffordance_isAwaiting() {
        GenericDecoder d = new GenericDecoder();
        VirtualTerminal vt = new VirtualTerminal(20, 80);

        vt.feed("\033[7;1H❯ 1. Yes, proceed");
        vt.feed("\033[8;1H  2. No");
        vt.feed("\033[10;1HEnter to confirm");

        assertTrue(d.isAwaitingUserInput(vt), "'Enter to confirm' must be detected as awaiting input");
    }

    // ------------------------------------------------------------------
    // 5. Plain-question fallback (last content line ends with '?')
    // ------------------------------------------------------------------

    @Test
    void detectsPlainQuestionFallback_isAwaiting() {
        GenericDecoder d = new GenericDecoder();
        VirtualTerminal vt = new VirtualTerminal(20, 80);

        // A lone question line with no numbered menu — should use the plain-question fallback.
        vt.feed("\033[8;1HWould you like to explore the project?");

        assertTrue(d.isAwaitingUserInput(vt), "single line ending with '?' must be detected as awaiting");
    }

    @Test
    void extractsPlainQuestionFallbackText() {
        GenericDecoder d = new GenericDecoder();
        VirtualTerminal vt = new VirtualTerminal(20, 80);

        vt.feed("\033[8;1HWould you like to explore the project?");

        String prompt = d.extractPromptText(vt);
        assertTrue(prompt.contains("Would you like to explore the project?"),
                "question line must be returned by extractPromptText: " + prompt);
    }

    @Test
    void selectOptionDigitNullForYnPromptWithNoNumberedMenu() {
        GenericDecoder d = new GenericDecoder();
        VirtualTerminal vt = new VirtualTerminal(20, 80);

        vt.feed("\033[8;1HProceed? (y/n)");

        // No numbered options → selectedOptionDigit must return null (caller sends bare CR/y/n).
        assertNull(d.selectedOptionDigit(vt),
                "y/n prompt without numbered options must return null for selectedOptionDigit");
    }

    // ------------------------------------------------------------------
    // 6a. False-positive guard: numbered list in PROSE (no selection cursor)
    // ------------------------------------------------------------------

    @Test
    void doesNotFalsePositiveOnProseNumberedList_noAwaiting() {
        GenericDecoder d = new GenericDecoder();
        VirtualTerminal vt = new VirtualTerminal(20, 80);

        // A prose response containing a numbered list but NO ❯ cursor and no confirm affordance.
        vt.feed("\033[3;1HHere are the three main approaches:");
        vt.feed("\033[4;1H1. Use a hashmap for O(1) lookups");
        vt.feed("\033[5;1H2. Use a sorted array with binary search");
        vt.feed("\033[6;1H3. Use a trie for prefix queries");

        assertFalse(d.isAwaitingUserInput(vt),
                "prose numbered list without a selection cursor must NOT be detected as awaiting input");
    }

    @Test
    void doesNotFalsePositiveOnProseNumberedList_awaitingIsFalseIsTheRealGuard() {
        GenericDecoder d = new GenericDecoder();
        VirtualTerminal vt = new VirtualTerminal(20, 80);

        vt.feed("\033[3;1HHere are the three main approaches:");
        vt.feed("\033[4;1H1. Use a hashmap for O(1) lookups");
        vt.feed("\033[5;1H2. Use a sorted array with binary search");
        vt.feed("\033[6;1H3. Use a trie for prefix queries");

        // The real false-positive protection is isAwaitingUserInput returning false —
        // without a ❯ cursor and without a confirm affordance, no decision is pending.
        // extractPromptText may return numbered lines (it scans for the pattern), but since
        // isAwaitingUserInput is false the REPL never shows them as a blocking prompt.
        assertFalse(d.isAwaitingUserInput(vt),
                "prose numbered list without ❯ cursor or confirm affordance must NOT trigger awaiting");
        // selectedOptionDigit must be null because no ❯ is present.
        assertNull(d.selectedOptionDigit(vt),
                "no ❯ cursor → selectedOptionDigit must be null for a prose numbered list");
    }

    // ------------------------------------------------------------------
    // 6b. False-positive guard: question quoted mid-paragraph while spinner is active
    // ------------------------------------------------------------------

    @Test
    void doesNotFalsePositiveOnQuestionMidParagraphWithSpinner_noAwaiting() {
        GenericDecoder d = new GenericDecoder();
        VirtualTerminal vt = new VirtualTerminal(20, 80);

        // A response body that contains a '?'-ending sentence mid-paragraph, while a spinner is active.
        // The spinner ("Working (3s") should suppress the trailing-question detection.
        vt.feed("\033[3;1HThe main question here is: should we use async?");
        vt.feed("\033[4;1HThis is the tradeoff you need to consider carefully.");
        vt.feed("\033[8;1HWorking (3s)");

        assertFalse(d.isAwaitingUserInput(vt),
                "question embedded mid-paragraph while spinner is active must NOT trigger awaiting");
    }

    @Test
    void doesNotFalsePositiveOnMultiLineProseEndingWithQuestion_noAwaiting() {
        GenericDecoder d = new GenericDecoder();
        VirtualTerminal vt = new VirtualTerminal(20, 80);

        // Multi-line prose response whose last visible line ends with '?', but there are many content
        // rows — should NOT trigger the trailing-question fallback (which requires ≤2 content rows).
        vt.feed("\033[2;1HThe refactoring involves three steps.");
        vt.feed("\033[3;1HFirst, extract the common interface.");
        vt.feed("\033[4;1HSecond, inject it into each consumer.");
        vt.feed("\033[5;1HThird, write tests for each variant.");
        vt.feed("\033[6;1HDoes that approach make sense?");

        assertFalse(d.isAwaitingUserInput(vt),
                "prose with >2 content rows ending in '?' must NOT trigger awaiting-input");
    }

    // ------------------------------------------------------------------
    // 7. altScreenIsDialog default — should remain true for GenericDecoder
    // ------------------------------------------------------------------

    @Test
    void altScreenIsDialogReturnsTrueForGenericDecoder() {
        GenericDecoder d = new GenericDecoder();
        assertTrue(d.altScreenIsDialog(),
                "GenericDecoder must keep altScreenIsDialog()=true (unknown agent alt-screen = dialog to mirror)");
    }

    // ------------------------------------------------------------------
    // 8. Decoder identity and query policy
    // ------------------------------------------------------------------

    @Test
    void agentNameIsGeneric() {
        assertEquals("generic", new GenericDecoder().agentName());
    }

    @Test
    void queryPolicyIsConservative() {
        GenericDecoder d = new GenericDecoder();
        assertEquals(QueryPolicy.CONSERVATIVE, d.queryPolicy(),
                "GenericDecoder must use CONSERVATIVE query policy for unknown-agent safety");
    }

    @Test
    void renderRawTuiIsFalse() {
        // Extending AbstractTuiDecoder means this should be false — kompile renders extracted text.
        GenericDecoder d = new GenericDecoder();
        assertFalse(d.renderRawTui(),
                "GenericDecoder extends AbstractTuiDecoder so renderRawTui() must be false");
    }

    // ------------------------------------------------------------------
    // 9. extractContent passes through normal content rows
    // ------------------------------------------------------------------

    @Test
    void extractContentPassesThroughNormalRows() {
        GenericDecoder d = new GenericDecoder();
        VirtualTerminal vt = new VirtualTerminal(20, 80);

        vt.feed("\033[3;1HThis is a normal assistant response.");
        vt.feed("\033[4;1HIt spans two lines.");

        String content = d.extractContent(vt);
        assertTrue(content.contains("This is a normal assistant response."), content);
        assertTrue(content.contains("It spans two lines."), content);
    }

    @Test
    void extractContentFiltersSeparatorRows() {
        GenericDecoder d = new GenericDecoder();
        VirtualTerminal vt = new VirtualTerminal(20, 80);

        vt.feed("\033[3;1HActual response content.");
        // A pure separator row that should be filtered.
        vt.feed("\033[4;1H─────────────────────────────────────────");

        String content = d.extractContent(vt);
        assertTrue(content.contains("Actual response content."), content);
        assertFalse(content.contains("────"), content);
    }
}
