package ai.kompile.kclaw.gateway.channel;

import ai.kompile.gateway.core.gateway.channel.ChannelAdapter;
import ai.kompile.gateway.core.gateway.channel.EmailClient;
import ai.kompile.gateway.core.model.AgentRequest;
import ai.kompile.gateway.core.model.AgentResponse;
import ai.kompile.gateway.core.service.AgentExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailChannelAdapterSecurityTest {

    private AgentExecutor executor;
    private EmailClient client;
    private EmailChannelAdapter adapter;

    @BeforeEach
    void setUp() {
        executor = mock(AgentExecutor.class);
        client = mock(EmailClient.class);
        adapter = new EmailChannelAdapter(executor);
        adapter.setEmailClient(client);
        adapter.addAllowedSender("operator@example.com");
        adapter.updateConfig(ChannelAdapter.AdapterConfig.defaults("email", "jarvis"));
    }

    @Test
    void rejectsUnauthenticatedFromHeaderEvenWhenAddressIsAllowlisted() {
        adapter.onMessage(message(false));

        verify(executor, never()).execute(any(AgentRequest.class));
        verify(client).markAsRead("message-1");
    }

    @Test
    void quarantinesAuthenticatedMailFromUnauthorizedSenders() {
        EmailClient.EmailMessage unauthorized = new EmailClient.EmailMessage(
                "message-2", "outsider@example.net", "Outsider", "bot@example.com",
                "question", "please help", "please help", null,
                System.currentTimeMillis(), null, null, null, true, List.of());

        adapter.onMessage(unauthorized);

        verify(executor, never()).execute(any(AgentRequest.class));
        verify(client).markAsRead("message-2");
    }

    @Test
    void authenticatedMessageRepliesOnlyToAuthenticatedFromAddress() {
        when(executor.execute(any(AgentRequest.class)))
                .thenReturn(AgentResponse.of("response", "session"));

        adapter.onMessage(message(true));

        verify(executor).execute(any(AgentRequest.class));
        verify(client).sendReply(
                "operator@example.com", "Re: question", "response", "message-1");
        verify(client, never()).sendReply(
                "attacker@example.net", "Re: question", "response", "message-1");
    }

    @Test
    void authenticatedSubjectlessMessageGetsSafeReplySubject() {
        when(executor.execute(any(AgentRequest.class)))
                .thenReturn(AgentResponse.of("response", "session"));

        adapter.onMessage(message(true, null));

        verify(client).sendReply(
                "operator@example.com", "Re: (no subject)", "response", "message-1");
    }

    private static EmailClient.EmailMessage message(boolean authenticated) {
        return message(authenticated, "question");
    }

    private static EmailClient.EmailMessage message(boolean authenticated, String subject) {
        return new EmailClient.EmailMessage(
                "message-1",
                "operator@example.com",
                "Operator",
                "bot@example.com",
                subject,
                "please help",
                "please help",
                null,
                System.currentTimeMillis(),
                "attacker@example.net",
                null,
                null,
                authenticated,
                List.of());
    }
}
