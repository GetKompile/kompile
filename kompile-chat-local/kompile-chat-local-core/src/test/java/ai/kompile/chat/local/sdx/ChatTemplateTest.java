package ai.kompile.chat.local.sdx;

import ai.kompile.chat.local.ChatEngine;
import ai.kompile.chat.local.Message;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SdxChatModel.ChatTemplate} — prompt construction and name sniffing.
 * These tests are purely in-JVM and do not require the native SDX library.
 */
class ChatTemplateTest {

    // ── Sniff detection ───────────────────────────────────────────────────────

    @Test
    void sniffQwen35ReturnsChatMlNoThink() {
        var t = SdxChatModel.ChatTemplate.sniff("Qwen3.5-0.8B-Q4_K_M.gguf");
        assertEquals(SdxChatModel.ChatTemplate.CHATML_IM_NOTHINK, t,
                "qwen3 filename should resolve to CHATML_IM_NOTHINK");
    }

    @Test
    void sniffQwen3ReturnsNoThink() {
        var t = SdxChatModel.ChatTemplate.sniff("qwen3-0.6b-instruct.gguf");
        assertEquals(SdxChatModel.ChatTemplate.CHATML_IM_NOTHINK, t,
                "qwen3 filename should resolve to CHATML_IM_NOTHINK");
    }

    @Test
    void sniffQwen25ReturnsChaintml() {
        var t = SdxChatModel.ChatTemplate.sniff("qwen2.5-0.5b-instruct-q4_k_m.gguf");
        assertEquals(SdxChatModel.ChatTemplate.CHATML_IM, t,
                "qwen2.5 filename should resolve to CHATML_IM");
    }

    @Test
    void sniffPhi3ReturnsChaintml() {
        var t = SdxChatModel.ChatTemplate.sniff("Phi-3-mini-4k-instruct-q4.gguf");
        assertEquals(SdxChatModel.ChatTemplate.CHATML_IM, t,
                "phi filename should resolve to CHATML_IM");
    }

    @Test
    void sniffGenericInstructReturnsGenericPipe() {
        var t = SdxChatModel.ChatTemplate.sniff("mistral-7b-instruct-v0.2.gguf");
        assertEquals(SdxChatModel.ChatTemplate.GENERIC_PIPE, t,
                "generic instruct filename (no qwen/phi) should resolve to GENERIC_PIPE");
    }

    @Test
    void sniffBaseModelReturnsPlain() {
        var t = SdxChatModel.ChatTemplate.sniff("llama-7b-base.gguf");
        assertEquals(SdxChatModel.ChatTemplate.PLAIN, t,
                "base model filename should resolve to PLAIN");
    }

    @Test
    void sniffNullReturnsGenericPipe() {
        var t = SdxChatModel.ChatTemplate.sniff(null);
        assertEquals(SdxChatModel.ChatTemplate.GENERIC_PIPE, t,
                "null filename should fall back to GENERIC_PIPE");
    }

    // ── CHATML_IM_NOTHINK prompt format ───────────────────────────────────────

    @Test
    void chatMlImNoThinkInjectsThinkBlockAndPrefill() {
        // When last message is a user turn (not tool_result), prompt ends with tool-call pre-fill
        List<Message> msgs = List.of(
                Message.system("You help."),
                Message.user("Hi")
        );
        String prompt = SdxChatModel.buildPrompt(msgs, SdxChatModel.ChatTemplate.CHATML_IM_NOTHINK);

        assertTrue(prompt.contains("<|im_start|>assistant\n<think>\n\n</think>\n"),
                "CHATML_IM_NOTHINK should contain empty think block");
        assertTrue(prompt.endsWith(SdxChatModel.TOOL_PREFILL),
                "CHATML_IM_NOTHINK should end with tool pre-fill on user turns");
        assertTrue(prompt.startsWith("<|im_start|>system\n"), "Should start with system");
    }

    @Test
    void chatMlImNoThinkAfterToolResultNoPrefill() {
        // After tool_result, prompt should NOT end with tool pre-fill (allow free-text answer)
        List<Message> msgs = List.of(
                Message.system("You help."),
                Message.user("Who does Alice work for?"),
                Message.assistant("{\"tool\": \"graph_reasoning_query\", \"args\": {}}"),
                new Message("tool_result", "{\"entities\": [{\"label\": \"Alice\", \"type\": \"PERSON\"}]}")
        );
        String prompt = SdxChatModel.buildPrompt(msgs, SdxChatModel.ChatTemplate.CHATML_IM_NOTHINK);

        assertFalse(prompt.endsWith(SdxChatModel.TOOL_PREFILL),
                "CHATML_IM_NOTHINK should NOT end with tool pre-fill after tool_result");
        assertTrue(prompt.endsWith("<|im_start|>assistant\n<think>\n\n</think>\n"),
                "Should still have think block");
    }

    // ── stripThink ────────────────────────────────────────────────────────────

    @Test
    void stripThinkRemovesBlock() {
        String raw = "<think>\nsome reasoning\n</think>\n4";
        assertEquals("4", ChatEngine.stripThink(raw));
    }

    @Test
    void stripThinkPreservesNoThinkContent() {
        String raw = "Paris is the capital of France.";
        assertEquals(raw, ChatEngine.stripThink(raw));
    }

    @Test
    void stripThinkEmptyThinkBlock() {
        String raw = "<think>\n\n</think>\nHello!";
        assertEquals("Hello!", ChatEngine.stripThink(raw));
    }

    // ── CHATML_IM prompt format ────────────────────────────────────────────────

    @Test
    void chatMlImFormat() {
        List<Message> msgs = List.of(
                Message.system("You are helpful."),
                Message.user("Hello"),
                Message.assistant("Hi there"),
                Message.user("What is 2+2?")
        );
        String prompt = SdxChatModel.buildPrompt(msgs, SdxChatModel.ChatTemplate.CHATML_IM);

        assertTrue(prompt.startsWith("<|im_start|>system\n"), "Should start with system im_start");
        assertTrue(prompt.contains("<|im_end|>\n"), "Should contain im_end");
        assertTrue(prompt.contains("<|im_start|>user\nHello<|im_end|>\n"), "User turn must be wrapped");
        assertTrue(prompt.contains("<|im_start|>assistant\nHi there<|im_end|>\n"), "Assistant turn must be wrapped");
        assertTrue(prompt.endsWith("<|im_start|>assistant\n"), "Prompt must end with assistant primer");
    }

    @Test
    void chatMlImToolResultSurfacedAsUser() {
        List<Message> msgs = List.of(
                Message.user("Who works at Acme?"),
                new Message("tool_result", "{\"entities\":[\"Alice\"]}")
        );
        String prompt = SdxChatModel.buildPrompt(msgs, SdxChatModel.ChatTemplate.CHATML_IM);
        // tool_result should become a user turn
        assertTrue(prompt.contains("<|im_start|>user\n{\"entities\":[\"Alice\"]}<|im_end|>"),
                "tool_result should be emitted as a user im_start block");
    }

    // ── GENERIC_PIPE prompt format ─────────────────────────────────────────────

    @Test
    void genericPipeFormat() {
        List<Message> msgs = List.of(
                Message.system("Be concise."),
                Message.user("Hi")
        );
        String prompt = SdxChatModel.buildPrompt(msgs, SdxChatModel.ChatTemplate.GENERIC_PIPE);

        assertTrue(prompt.startsWith("<|system|>\nBe concise.\n"), "System tag must come first");
        assertTrue(prompt.contains("<|user|>\nHi\n"), "User tag must wrap content");
        assertTrue(prompt.endsWith("<|assistant|>"), "Must end with assistant primer");
    }

    // ── PLAIN prompt format ───────────────────────────────────────────────────

    @Test
    void plainFormat() {
        List<Message> msgs = List.of(
                Message.system("You help."),
                Message.user("Tell me something")
        );
        String prompt = SdxChatModel.buildPrompt(msgs, SdxChatModel.ChatTemplate.PLAIN);

        assertTrue(prompt.startsWith("System: You help.\n"), "System label first");
        assertTrue(prompt.contains("User: Tell me something\n"), "User label present");
        assertTrue(prompt.endsWith("Assistant:"), "Must end with Assistant: primer");
    }
}
