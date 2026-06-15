package ai.kompile.graphchangetracking.hook;

import ai.kompile.graphchangetracking.event.GraphChangesetCompletedEvent;
import ai.kompile.graphchangetracking.event.GraphMutationEvent;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class GraphUpdateHookRegistry {

    private final Map<String, GraphUpdateHook> hooks = new ConcurrentHashMap<>();
    private volatile List<GraphUpdateHook> sortedHooks = List.of();

    public void register(GraphUpdateHook hook) {
        hooks.put(hook.getId(), hook);
        rebuildSorted();
        log.info("Registered graph update hook: {} (priority={})", hook.getId(), hook.getPriority());
    }

    public void unregister(String hookId) {
        if (hooks.remove(hookId) != null) {
            rebuildSorted();
            log.info("Unregistered graph update hook: {}", hookId);
        }
    }

    public Optional<GraphUpdateHook> get(String hookId) {
        return Optional.ofNullable(hooks.get(hookId));
    }

    public List<GraphUpdateHook> getAll() {
        return sortedHooks;
    }

    public boolean executeChannelMessage(ChannelGraphUpdateContext context) {
        for (GraphUpdateHook hook : sortedHooks) {
            try {
                if (!hook.onChannelMessage(context)) {
                    log.debug("Hook {} short-circuited channel message processing", hook.getId());
                    return false;
                }
            } catch (Exception e) {
                log.error("Error in hook {} onChannelMessage", hook.getId(), e);
            }
        }
        return true;
    }

    public void executeGraphMutated(GraphMutationEvent event) {
        for (GraphUpdateHook hook : sortedHooks) {
            try {
                hook.onGraphMutated(event);
            } catch (Exception e) {
                log.error("Error in hook {} onGraphMutated", hook.getId(), e);
            }
        }
    }

    public void executeChangesetComplete(GraphChangesetCompletedEvent event) {
        for (GraphUpdateHook hook : sortedHooks) {
            try {
                hook.onChangesetComplete(event);
            } catch (Exception e) {
                log.error("Error in hook {} onChangesetComplete", hook.getId(), e);
            }
        }
    }

    public void executePipelineError(String pipelineId, Throwable error) {
        for (GraphUpdateHook hook : sortedHooks) {
            try {
                hook.onPipelineError(pipelineId, error);
            } catch (Exception e) {
                log.error("Error in hook {} onPipelineError", hook.getId(), e);
            }
        }
    }

    private void rebuildSorted() {
        List<GraphUpdateHook> sorted = new ArrayList<>(hooks.values());
        sorted.sort(Comparator.comparingInt(GraphUpdateHook::getPriority).reversed());
        this.sortedHooks = Collections.unmodifiableList(sorted);
    }
}
