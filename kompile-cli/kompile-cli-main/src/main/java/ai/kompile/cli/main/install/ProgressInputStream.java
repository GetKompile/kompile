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

package ai.kompile.cli.main.install;

import me.tongfei.progressbar.ProgressBar;
import org.apache.commons.io.input.CountingInputStream;

import java.io.IOException;
import java.io.InputStream;

public class ProgressInputStream extends CountingInputStream {

    private long max;
    private ProgressBar pb = new ProgressBar("Downloading:", 100); // name, initial max
    public ProgressInputStream(InputStream in,long max) {
        super(in);
        this.max = max;
        pb.resume();
    }

    @Override
    protected synchronized void afterRead(int n) {
        super.afterRead(n);
        double progress = (getByteCount() / (double) max) * 100.0;
        pb.stepTo((long) progress);
    }


    @Override
    public void close() throws IOException {
        super.close();
        pb.reset();
    }
}
