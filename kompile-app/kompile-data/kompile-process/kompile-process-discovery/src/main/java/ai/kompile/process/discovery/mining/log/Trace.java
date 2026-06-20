/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.process.discovery.mining.log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * An ordered sequence of {@link Event}s belonging to a single case (process instance).
 *
 * <p>The activity-label projection of a trace (see {@link #activitySequence()}) is its <em>variant</em>
 * — the unit every discovery algorithm actually consumes.
 */
public final class Trace {

    private final String caseId;
    private final List<Event> events;

    public Trace(String caseId, List<Event> events) {
        this.caseId = caseId;
        this.events = new ArrayList<>(events == null ? List.of() : events);
    }

    public String caseId() {
        return caseId;
    }

    public List<Event> events() {
        return Collections.unmodifiableList(events);
    }

    public int size() {
        return events.size();
    }

    public boolean isEmpty() {
        return events.isEmpty();
    }

    /**
     * Returns the events ordered by timestamp using a <em>stable</em> sort, so events sharing a
     * timestamp (or all lacking one) keep their insertion order rather than being shuffled. A null
     * timestamp sorts first, so undated "setup" events stay at the front instead of jumping to the end.
     */
    public List<Event> ordered() {
        List<Event> copy = new ArrayList<>(events);
        copy.sort(Comparator.comparing(Event::timestamp,
                Comparator.nullsFirst(Comparator.naturalOrder())));
        return copy;
    }

    /** The activity labels in execution order — the trace "variant" projection. */
    public List<String> activitySequence() {
        List<Event> ord = ordered();
        List<String> seq = new ArrayList<>(ord.size());
        for (Event e : ord) {
            seq.add(e.activity());
        }
        return seq;
    }
}
