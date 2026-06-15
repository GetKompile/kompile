package ai.kompile.graphchangetracking.hook;

import ai.kompile.graphchangetracking.event.GraphChangesetCompletedEvent;
import ai.kompile.graphchangetracking.event.GraphMutationEvent;

public interface GraphUpdateHook {

    String getId();

    default int getPriority() {
        return 0;
    }

    default boolean onChannelMessage(ChannelGraphUpdateContext context) {
        return true;
    }

    default void onGraphMutated(GraphMutationEvent event) {
    }

    default void onChangesetComplete(GraphChangesetCompletedEvent event) {
    }

    default void onPipelineError(String pipelineId, Throwable error) {
    }
}
