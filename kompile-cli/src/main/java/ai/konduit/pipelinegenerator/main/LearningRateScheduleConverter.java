package ai.konduit.pipelinegenerator.main;

import org.nd4j.linalg.schedule.*;
import picocli.CommandLine;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class LearningRateScheduleConverter implements CommandLine.ITypeConverter<ISchedule> {
    public final static String DELIMITER = ",";
    public final static String SCHEDULE_TYPE_KEY = "type";

    public enum Scheduletype {
        CYCLE,
        EXPONENTIAL,
        FIXED,
        INVERSE,
        MAP,
        POLY,
        RAMP,
        SIGMOID,
        STEP
    }


    @Override
    public ISchedule convert(String value) throws Exception {
        String[] paramSplit = value.split(DELIMITER);
        Map<String,String> result = new HashMap<>();
        for(String splitVal : paramSplit) {
            String[] valSplit = splitVal.split("=");
            result.put(valSplit[0],valSplit[1]);
        }

        String type = result.get(SCHEDULE_TYPE_KEY);
        result.remove(type);
        return instanceForType(type,result);
    }

    private ISchedule instanceForType(String type,Map<String,String> configurationValues) {
        switch(Scheduletype.valueOf(type.toUpperCase())) {
            case MAP:
                return new MapSchedule(ScheduleType.EPOCH, Collections.emptyMap());
            case POLY:
            return new PolySchedule(ScheduleType.EPOCH,getValue(configurationValues,"initialValue"),getValue(configurationValues,"power"),1);
            case STEP:
                return new StepSchedule(ScheduleType.EPOCH,getValue(configurationValues,"initialValue"),getValue(configurationValues,"decayRate"),getValue(configurationValues,"step"));
            case CYCLE:
                return new CycleSchedule(ScheduleType.EPOCH,getValue(configurationValues,"maxLearningRate"),getIntValue(configurationValues,"cycleLength"));
            case FIXED:
                return new FixedSchedule(getValue(configurationValues,"value"));
            case INVERSE:
                return new InverseSchedule(ScheduleType.EPOCH,getValue(configurationValues,"initialValue"),getValue(configurationValues,"gamma"),getValue(configurationValues,"power"));
            case SIGMOID:
                return new SigmoidSchedule(ScheduleType.EPOCH,getValue(configurationValues,"initialValue"),getValue(configurationValues,"gamma"),getIntValue(configurationValues,"stepSize"));
            case EXPONENTIAL:
                return new ExponentialSchedule(ScheduleType.EPOCH,getValue(configurationValues,"initialValue"),getValue(configurationValues,"gamma"));
            default:
                throw new IllegalArgumentException("Unable to create learning rate schedule of type " + type);
        }

    }


    private int getIntValue(Map<String,String> getFrom,String key) {
        if(!getFrom.containsKey(key)) {
            throw new IllegalArgumentException("Unable to find configuration value " + key);
        }
        return Integer.parseInt(getFrom.get(key));
    }

    private double getValue(Map<String,String> getFrom,String key) {
        if(!getFrom.containsKey(key)) {
            throw new IllegalArgumentException("Unable to find configuration value " + key);
        }
        return Double.parseDouble(getFrom.get(key));
    }

}
