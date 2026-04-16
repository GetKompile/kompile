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

package ai.kompile.notebook.repository;

import ai.kompile.notebook.domain.NotebookSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface NotebookSourceRepository extends JpaRepository<NotebookSource, Long> {

    List<NotebookSource> findByNotebookIdOrderByAddedAtDesc(Long notebookId);

    Optional<NotebookSource> findByNotebookIdAndFactId(Long notebookId, Long factId);

    boolean existsByNotebookIdAndFactId(Long notebookId, Long factId);

    void deleteByNotebookIdAndFactId(Long notebookId, Long factId);

    List<NotebookSource> findByFactId(Long factId);
}
