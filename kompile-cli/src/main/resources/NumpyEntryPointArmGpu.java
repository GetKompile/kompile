package ai.konduit.pipelinegenerator.main;

import ai.konduit.serving.pipeline.api.data.Data;
import ai.konduit.serving.pipeline.api.data.NDArray;
import ai.konduit.serving.pipeline.api.pipeline.Pipeline;
import ai.konduit.serving.pipeline.api.pipeline.PipelineExecutor;
import org.apache.commons.lang.StringUtils;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.numpy.global.numpy;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.ObjectHandle;
import org.graalvm.nativeimage.UnmanagedMemory;
import org.graalvm.nativeimage.c.CContext;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.struct.CField;
import org.graalvm.nativeimage.c.struct.CPointerTo;
import org.graalvm.nativeimage.c.struct.CStruct;
import org.graalvm.nativeimage.c.struct.SizeOf;
import org.graalvm.nativeimage.c.type.*;
import org.graalvm.word.PointerBase;
import org.graalvm.word.SignedWord;
import org.graalvm.word.WordFactory;
import org.nd4j.common.base.Preconditions;
import org.nd4j.common.io.ClassPathResource;
import org.nd4j.common.util.ArrayUtil;
import org.nd4j.linalg.api.buffer.DataBuffer;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.memory.AllocationsTracker;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.nativeblas.NativeOps;
import org.nd4j.nativeblas.NativeOpsHolder;
import org.nd4j.nativeblas.Nd4jCuda;
import org.nd4j.jita.allocator.impl.AtomicAllocator;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;


@CContext(NumpyEntryPointDirectives.class)
public class NumpyEntryPointArmGpu {




    @CStruct("numpy_struct")
    interface NumpyStruct extends PointerBase {
        @CField("num_arrays")
        int numArrays();

        @CField("num_arrays")
        void setNumArrays(int length);

        @CField("numpy_array_addresses")
        CLongPointer getNumpyArrayAddresses();

        @CField("numpy_array_ranks")
        CLongPointer getNumpyArrayRanks();


        @CField("numpy_array_shapes")
        CLongPointerPointer getNumpyArrayShapes();


        @CField("numpy_array_data_types")
        CCharPointerPointer getNumpyArrayDataTypes();

        @CField("numpy_array_data_types")
        void setNumpyArrayDataTypes(CCharPointerPointer numpyArrayDataTypes);


        @CField("numpy_array_addresses")
        void setNumpyArrayAddresses(CLongPointerPointer numpyArrayAddresses);

        @CField("numpy_array_ranks")
        void setNumpyArrayRanks(CLongPointer numpyArrayRanks);


        @CField("numpy_array_shapes")
        void setNumpyArrayShapes(CLongPointerPointer numpyArrayAddresses);


        @CField("numpy_array_names")
        CCharPointerPointer getNumpyArrayNames();

        @CField("numpy_array_names")
        void setArrayNames(CCharPointerPointer numpyArrayNames);


    }


    @CStruct("handles")
    interface Handles extends PointerBase {


        @CField("native_ops_handle")
        ObjectHandle getNativeOpsHandle();

        @CField("native_ops_handle")
        void setNativeOpsHandle(ObjectHandle nativeOpsHandle);

        @CField("pipeline_handle")
        void setPipelineHandle(ObjectHandle pipelineHandle);

        @CField("pipeline_handle")
        ObjectHandle getPipelineHandle();

        @CField("executor_handle")
        void setExecutorHandle(ObjectHandle executorHandle);

        @CField("executor_handle")
        ObjectHandle getExecutorHandle();
    }


    @CEntryPoint(name = "printMetrics")
    public static void printMetrics(IsolateThread isolate) {
        int numDevices = Nd4j.getAffinityManager().getNumberOfDevices();
        for(int i = 0; i < numDevices; i++) {
            long allocated = AllocationsTracker.getInstance().bytesOnDevice(i);
            System.out.println("Allocated memory in bytes via allocation tracker is " + allocated);

        }

        System.out.println("Available physical bytes is " + Pointer.availablePhysicalBytes());
        System.out.println("Memory used is " + Pointer.totalBytes());

    }


    @CEntryPoint(name = "initPipeline")
    public static int initPipeline(IsolateThread isolate, Handles handles, CCharPointer pipelinePath) {
        System.setProperty("org.bytedeco.javacpp.platform", "linux-arm64");
        try {
            String pipelinePath2 = CTypeConversion.toJavaString(pipelinePath);
            System.setProperty("pipeline.path",pipelinePath2);
            EntryPointSetup.setup();


            Holder.init();


        } catch (Exception e) {
            e.printStackTrace();
        }

        return 0;
    }


    public static void main(String...args) {
        Thread.currentThread().getContextClassLoader();

    }

    public static class Holder {
        private static Pipeline pipeline;
        private static PipelineExecutor pipelineExecutor;

        public static void init() {
            String pipelinePath = System.getProperty("pipeline.path");
            if (pipeline == null)
                pipeline = Pipeline.fromJson(pipelinePath);
            System.out.println("Loaded pipeline from json");
            if (pipelineExecutor == null)
                pipelineExecutor = pipeline.executor();

        }


        public static PipelineExecutor getPipelineExecutor() {
            return pipelineExecutor;
        }

        public static Pipeline getPipeline() {
            return pipeline;
        }



    }



    @CEntryPoint(name = "runPipeline")
    public static int runPipeline(IsolateThread isolate, Handles handles, NumpyStruct numpyInput, NumpyStruct numpyOutput) {

        try {
            runPipeline(handles, numpyInput, numpyOutput);
            return 0;
        } catch (Exception e) {
            e.printStackTrace();
            return 1;
        }
    }

    public static void runPipeline(Handles handles, NumpyStruct numpyInput, NumpyStruct numpyOutput) throws Exception {
        int length = numpyInput.numArrays();
        System.out.println("Got num arrays");
        CCharPointerPointer numpyArrayNames = numpyInput.getNumpyArrayNames();
        //PinnedObject deviceNativeOpsPinned = ObjectHandles.getGlobal().get(handles.getNativeOpsHandle());
        //NativeOps deviceNativeOps = ImageSingletons.lookup(NativeOps.class);
        //PinnedObject pipelinePinned = ObjectHandles.getGlobal().get(handles.getPipelineHandle());
        Pipeline pipeline = Holder.getPipeline();
        System.out.println("Got pipeline");

        //PinnedObject pipelineExecutorPinned = ObjectHandles.getGlobal().get(handles.getPipelineHandle());
        PipelineExecutor pipelineExecutor = Holder.getPipelineExecutor();
        System.out.println("Got executioner");

        if(pipelineExecutor == null) {
            throw new IllegalStateException("Pipeline executioner was null!");
        }
        String[] namesJava = new String[length];
        for (int i = 0; i < length; i++) {
            namesJava[i] = CTypeConversion.toJavaString(numpyArrayNames.read(i));
        }


        CLongPointer numpyArrayAddressesInput = numpyInput.getNumpyArrayAddresses();
        long[] addresses = new long[length];
        for (int i = 0; i < length; i++) {
            addresses[i] = numpyArrayAddressesInput.read(i);
        }

        long[] ranks = new long[length];
        for (int i = 0; i < ranks.length; i++) {
            ranks[i] = numpyInput.getNumpyArrayRanks().read(i);
        }

        long[][] shapes = new long[length][];
        for (int i = 0; i < length; i++) {
            shapes[i] = new long[(int) ranks[i]];
            for (int j = 0; j < ranks[i]; j++) {
                shapes[i][j] = numpyInput.getNumpyArrayShapes().read(i).read(j);
            }
        }


        String[] dataTypes = new String[length];
        for (int i = 0; i < length; i++) {
            dataTypes[i] = CTypeConversion.toJavaString(numpyInput.getNumpyArrayDataTypes().read(i));
        }


        System.out.println("Creating ndarrays executioner");


        INDArray[] newArrs = new INDArray[length];
        for (int i = 0; i < length; i++) {
            long read = addresses[i];

            Pointer pointer = NativeOpsHolder.getInstance().getDeviceNativeOps().pointerForAddress(read);
            long len = ArrayUtil.prod(shapes[i]);
            pointer.limit(len * DataType.valueOf(dataTypes[i]).width());
            DataBuffer dataBuffer = Nd4j.createBuffer(pointer, len, DataType.valueOf(dataTypes[i]));
            INDArray arr = Nd4j.create(dataBuffer, shapes[i]);
            newArrs[i] = arr;
        }

        System.out.println("After ndarrays create");


        Data input = Data.empty();
        for (int i = 0; i < length; i++) {
            Preconditions.checkNotNull(newArrs[i],"New array for item " + i  + " was null!");
            Preconditions.checkNotNull(namesJava[i],"New name for item " + i  + " was null!");

            input.put(namesJava[i], NDArray.create(newArrs[i]));
        }

        System.out.println("About to exec");


        Data exec = pipelineExecutor.exec(input);

        System.out.println("After to exec");


        numpyOutput.setNumArrays(exec.keys().size());


        System.out.println("Setting up results");
        int size = SizeOf.get(CLongPointer.class) * exec.size();
        String[] outputNames = new String[exec.size()];
        PointerBase numpyArraysPointer = UnmanagedMemory.calloc(size);
        CLongPointerPointer numpyArrayAddresses = WordFactory.pointer(numpyArraysPointer.rawValue());
        int currKeyIdx = 0;
        CLongPointerPointer shapes2 = UnmanagedMemory.calloc(SizeOf.get(CLongPointerPointer.class) * exec.size());
        numpyOutput.setNumpyArrayShapes(shapes2);
        CLongPointer rankPointer = UnmanagedMemory.calloc(SizeOf.get(CLongPointer.class) * exec.size());
        numpyOutput.setNumpyArrayRanks(rankPointer);

        CCharPointerPointer dataTypesOutput = UnmanagedMemory.calloc(SizeOf.get(CCharPointerPointer.class) * exec.size());
        numpyOutput.setNumpyArrayDataTypes(dataTypesOutput);
        for (String key : exec.keys()) {
            INDArray arr = exec.getNDArray(key).getAs(INDArray.class);
            //note that the jetson nano does not have host mmeory. Device and host memory are the same.
            long address = arr.data().platformAddress();
            PointerBase cLong = UnmanagedMemory.calloc(SizeOf.get(CLongPointer.class));
            CLongPointer cLongPointer = WordFactory.pointer(cLong.rawValue());
            cLongPointer.write(address);
            numpyArrayAddresses.write(currKeyIdx, cLongPointer);
            outputNames[currKeyIdx] = key;
            CLongPointer shape = UnmanagedMemory.calloc(SizeOf.get(CLongPointer.class) * arr.rank());
            for (int i = 0; i < arr.rank(); i++) {
                shape.write(i, arr.size(i));
            }

            rankPointer.write(currKeyIdx, arr.rank());
            shapes2.write(currKeyIdx, shape);

            CCharPointer dataTypePointer = CTypeConversion.toCString(arr.dataType().name().toUpperCase()).get();
            dataTypesOutput.write(currKeyIdx, dataTypePointer);

            currKeyIdx++;
        }


        CTypeConversion.CCharPointerPointerHolder cCharPointerPointerHolder = CTypeConversion.toCStrings(outputNames);
        numpyOutput.setNumpyArrayAddresses(numpyArrayAddresses);
        numpyOutput.setArrayNames(cCharPointerPointerHolder.get());
        numpyOutput.setNumArrays(exec.keys().size());
    }


}