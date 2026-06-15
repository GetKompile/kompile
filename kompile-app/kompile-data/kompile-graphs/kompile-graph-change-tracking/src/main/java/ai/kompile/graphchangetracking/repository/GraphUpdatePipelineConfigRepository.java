package ai.kompile.graphchangetracking.repository;

import ai.kompile.graphchangetracking.domain.GraphUpdatePipelineConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GraphUpdatePipelineConfigRepository extends JpaRepository<GraphUpdatePipelineConfig, Long> {

    Optional<GraphUpdatePipelineConfig> findByPipelineId(String pipelineId);

    List<GraphUpdatePipelineConfig> findByEnabledTrue();

    @Query("SELECT p FROM GraphUpdatePipelineConfig p WHERE p.enabled = true " +
            "AND p.triggerChannels LIKE CONCAT('%', :channelName, '%') " +
            "ORDER BY p.priority DESC")
    List<GraphUpdatePipelineConfig> findEnabledByChannel(@Param("channelName") String channelName);

    List<GraphUpdatePipelineConfig> findByTargetFactSheetId(Long factSheetId);

    void deleteByPipelineId(String pipelineId);
}
