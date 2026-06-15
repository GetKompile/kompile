/*
 * Copyright (c) 2022 Konduit K.K.
 *
 *     This program and the accompanying materials are made available under the
 *     terms of the Apache License, Version 2.0 which is available at
 *     https://www.apache.org/licenses/LICENSE-2.0.
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *     WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *     License for the specific language governing permissions and limitations
 *     under the License.
 *
 *     SPDX-License-Identifier: Apache-2.0
 */

package ai.konduit.pipelinegenerator.main.install;

import ai.konduit.pipelinegenerator.main.util.BackendInfo;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.*;

public class IndexerTests {

    @Test
    public void testIndexer() {
        ProgramIndex indexer = new ProgramIndex();
        CommandLine commandLine = new CommandLine(indexer);
        assertEquals(0,commandLine.execute("--updateIndexForce=true"));
        BackendInfo backendInfo = BackendInfo.backendClassifiersForBackend("rhel");
        assertNotNull(backendInfo.getBackends());
        assertTrue(!backendInfo.getClassifiers().isEmpty());
        assertTrue(!backendInfo.getBackends().isEmpty());


    }


}
