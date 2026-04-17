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

package ai.kompile.app.monitor.repository;

import ai.kompile.app.monitor.domain.MonitorRegistration;
import ai.kompile.app.monitor.domain.MonitorRegistration.MonitorStatus;
import ai.kompile.app.monitor.domain.MonitorRegistration.MonitorType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MonitorRegistrationRepository extends JpaRepository<MonitorRegistration, Long> {

    Optional<MonitorRegistration> findByMonitorId(String monitorId);

    List<MonitorRegistration> findByStatusOrderByCreatedAtDesc(MonitorStatus status);

    List<MonitorRegistration> findBySessionIdOrderByCreatedAtDesc(String sessionId);

    List<MonitorRegistration> findByTaskIdAndStatus(String taskId, MonitorStatus status);

    List<MonitorRegistration> findByTypeAndStatus(MonitorType type, MonitorStatus status);
}
