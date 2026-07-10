package ai.kompile.process.release;

import java.util.List;
import java.util.Optional;

/**
 * Durable storage boundary for process releases.
 */
public interface ProcessReleaseRepository {

    ProcessRelease save(ProcessRelease release);

    Optional<ProcessRelease> findById(String id);

    List<ProcessRelease> findAll();

    default List<ProcessRelease> findByProcessAndEnvironment(String processDefinitionId,
                                                              String environment) {
        return findAll().stream()
                .filter(release -> processDefinitionId.equals(release.getProcessDefinitionId()))
                .filter(release -> environment.equals(release.getEnvironment()))
                .toList();
    }

    default Optional<ProcessRelease> findActive(String processDefinitionId, String environment) {
        List<ProcessRelease> active = findByProcessAndEnvironment(processDefinitionId, environment)
                .stream()
                .filter(release -> release.getStatus() == ProcessReleaseStatus.ACTIVE)
                .toList();
        if (active.size() > 1) {
            throw new IllegalStateException("Multiple active releases exist for process "
                    + processDefinitionId + " in environment " + environment);
        }
        return active.stream().findFirst();
    }
}
