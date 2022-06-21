package ai.konduit.pipelinegenerator.main.install;

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
        pb.start();
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
        pb.stop();
    }
}
