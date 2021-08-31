package ai.konduit.pipelinegenerator.main;

import org.bytedeco.javacpp.Pointer;
import org.nd4j.linalg.api.memory.AllocationsTracker;
import org.nd4j.linalg.api.ops.performance.PerformanceTracker;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.profiler.ProfilerConfig;

public class EntryPointSetup {

    public static void setup() {
        //-Dorg.bytedeco.javacpp.maxbytes=8G -Dorg.bytedeco.javacpp.maxphysicalbytes=10G
        String maxBytes = System.getenv().containsKey("KOMPILE_MAX_BYTES") ? System.getenv("KOMPILE_MAX_BYTES") : "1g";
        System.setProperty("org.bytedeco.javacpp.maxbytes",maxBytes);
        System.setProperty("org.bytedeco.javacpp.maxphysicalbytes",maxBytes);


        System.setProperty("org.bytedeco.javacpp.logger.debug",getBoolFromEnv("KOMPILE_JAVACPP_DEBUG","false"));

        System.out.println("Set max bytes to " + maxBytes);
        System.out.println("Off heap memory before init usage is " + Pointer.physicalBytes());

        long nd4jMaxBytes = Pointer.parseBytes(maxBytes,1);
        Nd4j.getEnvironment().setMaxSpecialMemory(nd4jMaxBytes);
        Nd4j.getEnvironment().setMaxDeviceMemory(nd4jMaxBytes);


        Nd4j.getEnvironment().setLeaksDetector(getBoolFromEnv("KOMPILE_LEAK_DETECTOR",false));
        String periodicGcWindow = System.getenv().containsKey("KOMPILE_GC_WINDOW") ? System.getenv("KOMPILE_GC_WINDOW") : "5000";

        // this will limit frequency of gc calls to 5000 milliseconds
        Nd4j.getMemoryManager().setAutoGcWindow(Integer.parseInt(periodicGcWindow));
        Nd4j.getMemoryManager().togglePeriodicGc(getBoolFromEnv("KOMPILE_PERIODIC_GC",true));

        Nd4j.getEnvironment().setVerbose(getBoolFromEnv("KOMPILE_VERBOSE",false));
        Nd4j.getExecutioner().setProfilingConfig(ProfilerConfig.builder()
                .checkForINF(getBoolFromEnv("KOMPILE_CHECK_INF",false))
                .checkElapsedTime(getBoolFromEnv("KOMPILE_ELAPSED_TIME",false))
                .checkWorkspaces(getBoolFromEnv("KOMPILE_WORKSPACE_CHECK",false))
                .checkForNAN(getBoolFromEnv("KOMPILE_CHECK_NAN",false))
                .checkLocality(getBoolFromEnv("KOMPILE_CHECK_LOCALITY",false))
                .build());

        System.out.println("Off heap memory usage is " + Pointer.physicalBytes());

        System.setProperty("org.eclipse.python4j.release_gil_automatically", "false");
        System.setProperty("org.eclipse.python4j.path.append", "none");

    }


    public static boolean getBoolFromEnv(String key,Boolean defaultValue) {
        Boolean ret = System.getenv().containsKey(key) ? Boolean.parseBoolean(System.getenv(key)) : defaultValue;
        return ret;
    }


}
