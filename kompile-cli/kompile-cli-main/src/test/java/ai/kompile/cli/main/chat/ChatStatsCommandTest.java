package ai.kompile.cli.main.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatStatsCommandTest {

    @Test
    void kompileSessionTotalsTreatCacheCountersAsDisjointInputClasses() {
        ChatStatsCommand.SessionData session = new ChatStatsCommand.SessionData();
        session.inputTokens = 70;
        session.outputTokens = 10;
        session.cacheReadTokens = 20;
        session.cacheCreationTokens = 5;

        assertEquals(105, ChatStatsCommand.sessionTotalTokens(session));
    }

    @Test
    void importedProviderTotalsDoNotDoubleCountCacheBreakdowns() {
        ChatStatsCommand.ProviderUsageData usage = new ChatStatsCommand.ProviderUsageData();
        usage.inputTokens = 100;
        usage.outputTokens = 10;
        usage.totalTokens = 110;
        usage.cacheReadTokens = 80;
        usage.cacheCreationTokens = 5;

        assertEquals(110, ChatStatsCommand.providerTotalTokens(usage));
    }

    @Test
    void importedProviderTotalsFallBackForLegacyRecordsWithoutTotal() {
        ChatStatsCommand.ProviderUsageData usage = new ChatStatsCommand.ProviderUsageData();
        usage.inputTokens = 100;
        usage.outputTokens = 10;
        usage.cacheReadTokens = 80;

        assertEquals(110, ChatStatsCommand.providerTotalTokens(usage));
    }
}
