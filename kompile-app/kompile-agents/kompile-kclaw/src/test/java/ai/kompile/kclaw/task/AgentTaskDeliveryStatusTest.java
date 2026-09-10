package ai.kompile.kclaw.task;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.gateway.core.gateway.channel.ChannelAdapter;
import ai.kompile.gateway.core.gateway.channel.ChannelManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentTaskDeliveryStatusTest {

    @TempDir
    Path tempDir;

    @Test
    void recordsDeliveryFailureSeparatelyFromSuccessfulAgentExecution() throws Exception {
        KompileCliRunner runner = mock(KompileCliRunner.class);
        when(runner.run("do work", null))
                .thenReturn(new KompileCliRunner.Result(true, "result", null));
        ChannelAdapter adapter = mock(ChannelAdapter.class);
        when(adapter.getChannelName()).thenReturn("telegram");
        when(adapter.send("42", "result")).thenThrow(new IllegalStateException("provider rejected"));
        ChannelManager manager = new ChannelManager();
        manager.registerAdapter("ops", adapter);
        AgentTaskService service = new AgentTaskService(
                null,
                runner,
                new AgentTaskStore(tempDir.toString(), JsonUtils.standardMapper()),
                "jarvis",
                null,
                manager);
        AgentTaskRequest request = new AgentTaskRequest();
        request.setEngine("kompile-cli");
        request.setTask("do work");
        request.setAsync(false);
        request.setChannel("ops");
        request.setChannelTarget("42");

        AgentTask task = service.submit(request);

        assertEquals(AgentTask.Status.SUCCEEDED, task.getStatus());
        assertEquals(AgentTask.DeliveryStatus.FAILED, task.getDeliveryStatus());
        assertNotNull(task.getDeliveryError());
    }
}
