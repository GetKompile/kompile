package ai.kompile.kclaw.gateway;

import ai.kompile.gateway.core.gateway.channel.ChannelManager;
import ai.kompile.gateway.core.service.AgentExecutor;
import ai.kompile.kclaw.gateway.channel.WhatsAppApiClient;
import ai.kompile.kclaw.gateway.channel.WhatsAppChannelAdapter;
import ai.kompile.kclaw.gateway.whatsapp.WhatsAppWebhookInbox;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChannelControllerWhatsAppRoutingTest {

    @Test
    void sharedAppSecretRoutesPayloadOnlyToMatchingPhoneNumberConnection() throws Exception {
        WhatsAppApiClient firstClient = mock(WhatsAppApiClient.class);
        WhatsAppApiClient secondClient = mock(WhatsAppApiClient.class);
        when(firstClient.verifyWebhookSignature(any(), anyString())).thenReturn(true);
        when(secondClient.verifyWebhookSignature(any(), anyString())).thenReturn(true);
        WhatsAppChannelAdapter first = adapter("phone-1", firstClient);
        WhatsAppChannelAdapter second = adapter("phone-2", secondClient);
        ChannelManager manager = new ChannelManager();
        manager.registerAdapter("first", first);
        manager.registerAdapter("second", second);
        WhatsAppWebhookInbox inbox = mock(WhatsAppWebhookInbox.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<WhatsAppWebhookInbox> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(inbox);
        var mvc = MockMvcBuilders.standaloneSetup(new ChannelController(manager, provider)).build();

        mvc.perform(post("/api/kclaw/channels/webhook/whatsapp")
                        .header("X-Hub-Signature-256", "sha256=test")
                        .contentType("application/json")
                        .content("""
                                {"entry":[{"changes":[{"value":{"metadata":{"phone_number_id":"phone-2"}}}]}]}
                                """))
                .andExpect(status().isAccepted());

        verify(inbox).enqueue(eq("second"), anyString(), any());
        verify(secondClient, never()).processWebhookPayload(any());
        verify(firstClient, never()).processWebhookPayload(any());
    }

    @Test
    void mixedPhoneBatchIsPartitionedBeforeEnqueue() throws Exception {
        WhatsAppApiClient firstClient = mock(WhatsAppApiClient.class);
        WhatsAppApiClient secondClient = mock(WhatsAppApiClient.class);
        when(firstClient.verifyWebhookSignature(any(), anyString())).thenReturn(true);
        when(secondClient.verifyWebhookSignature(any(), anyString())).thenReturn(true);
        ChannelManager manager = new ChannelManager();
        manager.registerAdapter("first", adapter("phone-1", firstClient));
        manager.registerAdapter("second", adapter("phone-2", secondClient));
        WhatsAppWebhookInbox inbox = mock(WhatsAppWebhookInbox.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<WhatsAppWebhookInbox> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(inbox);
        var mvc = MockMvcBuilders.standaloneSetup(new ChannelController(manager, provider)).build();

        mvc.perform(post("/api/kclaw/channels/webhook/whatsapp")
                        .header("X-Hub-Signature-256", "sha256=test")
                        .contentType("application/json")
                        .content("""
                                {"entry":[
                                  {"changes":[{"value":{"metadata":{"phone_number_id":"phone-1"},
                                    "messages":[{"id":"wamid.1","from":"15550001","text":{"body":"one"}}]}}]},
                                  {"changes":[{"value":{"metadata":{"phone_number_id":"phone-2"},
                                    "messages":[{"id":"wamid.2","from":"15550002","text":{"body":"two"}}]}}]}
                                ]}
                                """))
                .andExpect(status().isAccepted());

        verify(inbox).enqueue(eq("first"), eq("message:wamid.1"), any());
        verify(inbox).enqueue(eq("second"), eq("message:wamid.2"), any());
        verify(firstClient, never()).processWebhookPayload(any());
        verify(secondClient, never()).processWebhookPayload(any());
    }

    private static WhatsAppChannelAdapter adapter(String phoneNumberId, WhatsAppApiClient client) {
        WhatsAppChannelAdapter adapter = new WhatsAppChannelAdapter(mock(AgentExecutor.class));
        adapter.setPhoneNumberId(phoneNumberId);
        adapter.setApiClient(client);
        return adapter;
    }
}
