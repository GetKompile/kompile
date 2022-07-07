package ai.konduit.pipelinegenerator.main;

import ai.konduit.serving.pipeline.api.data.Data;
import ai.konduit.serving.pipeline.api.data.NDArray;
import ai.konduit.serving.pipeline.api.pipeline.Pipeline;
import ai.konduit.serving.pipeline.api.pipeline.PipelineExecutor;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.javacpp.Pointer;
import org.graalvm.nativeimage.ImageSingletons;
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
import org.graalvm.nativeimage.hosted.Feature;
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
import org.nd4j.linalg.factory.Nd4jBackend;
import org.nd4j.nativeblas.NativeOps;
import org.nd4j.nativeblas.NativeOpsHolder;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;


@CContext(NumpyEntryPoint.NumpyEntryPointDirectives.class)
public class NumpyEntryPoint {



    static class NumpyEntryPointDirectives implements CContext.Directives {

        @Override
        public List<String> getHeaderFiles() {
            /*
             * The header file with the C declarations that are imported. We use a helper class that
             * locates the file in our project structure.
             */
            try {
                return Collections.singletonList("\"" + new ClassPathResource("numpy_struct.h").getFile().getAbsolutePath() + "\"");
            } catch (IOException e) {
                e.printStackTrace();
                return null;
            }
        }
    }


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

    /**
     * A pointer to a pointer to a 64-bit C primitive value.
     *
     * @since 19.0
     */
    @CPointerTo(CLongPointer.class)
    public interface CLongPointerPointer extends PointerBase {

        /**
         * Reads the value at the pointer address.
         *
         * @since 19.0
         */
        CLongPointer read();

        /**
         * Reads the value of the array element with the specified index, treating the pointer as an
         * array of the C type.
         *
         * @since 19.0
         */
        CLongPointer read(int index);

        /**
         * Reads the value of the array element with the specified index, treating the pointer as an
         * array of the C type.
         *
         * @since 19.0
         */
        CIntPointer read(SignedWord index);

        /**
         * Writes the value at the pointer address.
         *
         * @since 19.0
         */
        void write(CLongPointer value);

        /**
         * Writes the value of the array element with the specified index, treating the pointer as an
         * array of the C type.
         *
         * @since 19.0
         */
        void write(int index, CLongPointer value);

        /**
         * Writes the value of the array element with the specified index, treating the pointer as an
         * array of the C type.
         *
         * @since 19.0
         */
        void write(SignedWord index, CLongPointer value);

        /**
         * Computes the address of the array element with the specified index, treating the pointer as
         * an array of the C type.
         *
         * @since 19.0
         */
        CLongPointer addressOf(int index);

        /**
         * Computes the address of the array element with the specified index, treating the pointer as
         * an array of the C type.
         *
         * @since 19.0
         */
        CLongPointer addressOf(SignedWord index);
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

    @CEntryPoint(name = "initPipeline")
    public static int initPipeline(IsolateThread isolate, Handles handles, CCharPointer pipelinePath) {
        try {
            String pipelinePath2 = CTypeConversion.toJavaString(pipelinePath);
            System.setProperty("pipeline.path",pipelinePath2);
            System.setProperty("org.eclipse.python4j.numpyimport", "false");





            System.setProperty("org.eclipse.python4j.release_gil_automatically", "false");
            System.out.println("Disabling automatic gil release");
            System.setProperty("org.eclipse.python4j.path.append", "none");
            Holder.init();


        } catch (Exception e) {
            e.printStackTrace();
        }




        return 0;
    }


    public static class Holder {
        private static NativeOps nativeOps = null;
        private static Pipeline pipeline;
        private static PipelineExecutor pipelineExecutor;

        public static void init() {
            try {
                Nd4jBackend load = Nd4jBackend.load();
                if(load.getClass().getName().toLowerCase().contains("cpu")) {
                    Class<? extends NativeOps> nativeOpsClazz = (Class<? extends NativeOps>) Class.forName("org.nd4j.linalg.cpu.nativecpu.bindings.Nd4jCpu");
                    nativeOps = nativeOpsClazz.newInstance();
                } else if(load.getClass().getName().toLowerCase().contains("cuda")) {
                    Class<? extends NativeOps> nativeOpsClazz = (Class<? extends NativeOps>) Class.forName("org.nd4j.linalg.jcublas.bindings.Nd4jCuda");
                    nativeOps = nativeOpsClazz.newInstance();

                } else if(load.getClass().getName().toLowerCase().contains("aurora")) {
                    Class<? extends NativeOps> nativeOpsClazz = (Class<? extends NativeOps>) Class.forName("org.nd4j.aurora.Nd4jAuroraOps");
                    nativeOps = nativeOpsClazz.newInstance();


                }
            }catch (Exception e) {
                throw new RuntimeException(e);
            }


            String pipelinePath = System.getProperty("pipeline.path");
            if (pipeline == null)
                pipeline = Pipeline.fromJson(pipelinePath);
            if (pipelineExecutor == null)
                pipelineExecutor = pipeline.executor();

        }


        public static PipelineExecutor getPipelineExecutor() {
            return pipelineExecutor;
        }

        public static Pipeline getPipeline() {
            return pipeline;
        }

        public static NativeOps getNativeOps() {
            return nativeOps;
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
        CCharPointerPointer numpyArrayNames = numpyInput.getNumpyArrayNames();
        //PinnedObject deviceNativeOpsPinned = ObjectHandles.getGlobal().get(handles.getNativeOpsHandle());
        //NativeOps deviceNativeOps = ImageSingletons.lookup(NativeOps.class);
        NativeOps deviceNativeOps = Holder.getNativeOps();
        //PinnedObject pipelinePinned = ObjectHandles.getGlobal().get(handles.getPipelineHandle());
        Pipeline pipeline = Holder.getPipeline();
        //PinnedObject pipelineExecutorPinned = ObjectHandles.getGlobal().get(handles.getPipelineHandle());
        PipelineExecutor pipelineExecutor = Holder.getPipelineExecutor();
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


        INDArray[] newArrs = new INDArray[length];
        for (int i = 0; i < length; i++) {
            long read = addresses[i];
            Pointer pointer = deviceNativeOps.pointerForAddress(read);
            long len = ArrayUtil.prod(shapes[i]);
            pointer.limit(len * DataType.valueOf(dataTypes[i]).width());
            DataBuffer dataBuffer = Nd4j.createBuffer(pointer, len, DataType.valueOf(dataTypes[i]));
            INDArray arr = Nd4j.create(dataBuffer, shapes[i]);
            newArrs[i] = arr;
            System.out.println(dataBuffer);
        }

        Data input = Data.empty();
        for (int i = 0; i < length; i++) {
            Preconditions.checkNotNull(newArrs[i],"New array for item " + i  + " was null!");
            Preconditions.checkNotNull(namesJava[i],"New name for item " + i  + " was null!");

            input.put(namesJava[i], NDArray.create(newArrs[i]));
        }


        Data exec = pipelineExecutor.exec(input);
        numpyOutput.setNumArrays(exec.keys().size());
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
            long address = arr.data().address();
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

}
