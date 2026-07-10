package ai.kompile.process.service;

import ai.kompile.process.execution.RunStatus;
import ai.kompile.process.execution.WorkflowRun;
import ai.kompile.process.release.ArtifactManifest;
import ai.kompile.process.release.ExecutableArtifactResolver;
import ai.kompile.process.release.ExecutableKind;
import ai.kompile.process.release.ExecutableRef;
import ai.kompile.process.release.ProcessRelease;
import ai.kompile.process.release.ProcessReleaseRepository;
import ai.kompile.process.release.ProcessReleaseStatus;
import ai.kompile.process.workflow.ProcessDefinition;
import ai.kompile.process.workflow.ProcessPhase;
import ai.kompile.process.workflow.ProcessStatus;
import ai.kompile.process.workflow.ProcessStep;
import ai.kompile.process.workflow.StepType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProcessReleaseRuntimeExecutionTest {

    @TempDir
    Path home;

    @Test
    void pinsActiveReleaseAndExecutesResolvedScriptInsteadOfInlineBody() {
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            ProcessEngineServiceImpl engine = new ProcessEngineServiceImpl();
            engine.init();

            ExecutableRef reference = ExecutableRef.builder()
                    .kind(ExecutableKind.SCRIPT)
                    .artifactId("billing-script")
                    .version("1")
                    .contentHash("sha256:abc")
                    .build();
            ProcessStep step = ProcessStep.builder()
                    .id("1.1")
                    .name("Calculate")
                    .stepType(StepType.SCRIPT)
                    .scriptLanguage("javascript")
                    .scriptBody("throw new Error('mutable inline code must not execute')")
                    .executableRef(reference)
                    .build();
            ProcessDefinition definition = ProcessDefinition.builder()
                    .id("billing")
                    .version(2)
                    .status(ProcessStatus.APPROVED)
                    .phases(List.of(ProcessPhase.builder().order(1).steps(List.of(step)).build()))
                    .build();
            engine.restoreProcessDefinition(definition);

            ArtifactManifest manifest = ArtifactManifest.builder()
                    .artifactId("billing-script").version("1")
                    .kind(ExecutableKind.SCRIPT).contentHash("sha256:abc").build();
            ProcessRelease release = ProcessRelease.builder()
                    .id("billing-prod-1")
                    .processDefinitionId("billing")
                    .processDefinitionVersion(2)
                    .environment("production")
                    .status(ProcessReleaseStatus.ACTIVE)
                    .artifacts(List.of(manifest))
                    .build();

            ProcessReleaseRepository repository = mock(ProcessReleaseRepository.class);
            when(repository.findActive("billing", "production")).thenReturn(Optional.of(release));
            when(repository.findById("billing-prod-1")).thenReturn(Optional.of(release));
            engine.setProcessReleaseRepository(repository);

            ExecutableArtifactResolver resolver = mock(ExecutableArtifactResolver.class);
            when(resolver.resolve(release, reference)).thenReturn(
                    new ExecutableArtifactResolver.ResolvedExecutable(
                            manifest, "_output = {amount: 42};", "javascript"));
            engine.setExecutableArtifactResolver(resolver);

            StepExecutionDispatcher dispatcher = mock(StepExecutionDispatcher.class);
            when(dispatcher.executeScript(eq("javascript"), eq("_output = {amount: 42};"), anyMap()))
                    .thenReturn(Map.of("amount", 42));
            engine.setStepExecutionDispatcher(dispatcher);

            WorkflowRun run = engine.startRun("billing", Map.of());

            assertEquals(RunStatus.COMPLETED, run.getStatus());
            assertEquals("billing-prod-1", run.getProcessReleaseId());
            assertEquals("production", run.getReleaseEnvironment());
            assertEquals(2, run.getProcessVersion());
            assertEquals(42, run.getRunData().get("amount"));
            verify(dispatcher).executeScript(eq("javascript"), eq("_output = {amount: 42};"), anyMap());
        } finally {
            System.setProperty("user.home", previousHome);
        }
    }

    @Test
    void executesPinnedAgentSessionAndPersistsConversationContext() {
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            ProcessEngineServiceImpl engine = new ProcessEngineServiceImpl();
            engine.init();
            ExecutableRef reference = ExecutableRef.builder()
                    .kind(ExecutableKind.AGENT_SESSION).artifactId("triage-prompt")
                    .version("1").contentHash("sha256:def").build();
            ProcessStep step = ProcessStep.builder().id("1.1").name("Triage")
                    .stepType(StepType.AGENT_SESSION).agentSpecId("triage-agent")
                    .agentPromptTemplate("mutable prompt").executableRef(reference)
                    .conversationIdKey("conversationId").conversationOutputKey("triage").build();
            ProcessDefinition definition = ProcessDefinition.builder().id("support").version(1)
                    .status(ProcessStatus.APPROVED)
                    .phases(List.of(ProcessPhase.builder().order(1).steps(List.of(step)).build())).build();
            engine.restoreProcessDefinition(definition);
            ArtifactManifest manifest = ArtifactManifest.builder().artifactId("triage-prompt")
                    .version("1").kind(ExecutableKind.AGENT_SESSION).contentHash("sha256:def").build();
            ProcessRelease release = ProcessRelease.builder().id("support-prod-1")
                    .processDefinitionId("support").processDefinitionVersion(1)
                    .environment("production").status(ProcessReleaseStatus.ACTIVE)
                    .artifacts(List.of(manifest)).build();
            ProcessReleaseRepository repository = mock(ProcessReleaseRepository.class);
            when(repository.findActive("support", "production")).thenReturn(Optional.of(release));
            when(repository.findById("support-prod-1")).thenReturn(Optional.of(release));
            engine.setProcessReleaseRepository(repository);
            ExecutableArtifactResolver resolver = mock(ExecutableArtifactResolver.class);
            when(resolver.resolve(release, reference)).thenReturn(
                    new ExecutableArtifactResolver.ResolvedExecutable(
                            manifest, "Triage this request using the workflow context.", "text"));
            engine.setExecutableArtifactResolver(resolver);
            StepExecutionDispatcher dispatcher = mock(StepExecutionDispatcher.class);
            when(dispatcher.executeAgentSession(eq("triage-agent"),
                    eq("Triage this request using the workflow context."), eq(null), anyMap()))
                    .thenReturn(Map.of("conversationId", "conv-1", "category", "billing"));
            engine.setStepExecutionDispatcher(dispatcher);

            WorkflowRun run = engine.startRun("support", Map.of("message", "charged twice"));

            assertEquals(RunStatus.COMPLETED, run.getStatus());
            assertEquals("conv-1", run.getRunData().get("conversationId"));
            assertEquals("billing", ((Map<?, ?>) run.getRunData().get("triage")).get("category"));
            verify(dispatcher).executeAgentSession(eq("triage-agent"),
                    eq("Triage this request using the workflow context."), eq(null), anyMap());
        } finally {
            System.setProperty("user.home", previousHome);
        }
    }
}
