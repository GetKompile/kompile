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

package ai.kompile.pipelines.steps.deeplearning4j.cli.converter;

import ai.kompile.pipelines.framework.core.data.serde.ObjectMappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.nd4j.linalg.learning.config.*;
import picocli.CommandLine;

import java.util.Map;

/**
 * Picocli type converter for ND4J IUpdater instances.
 * Supports simple names (e.g., "adam", "sgd") and JSON with parameters
 * (e.g., '{"type":"adam","learningRate":0.001}').
 */
public class IUpdaterTypeConverter implements CommandLine.ITypeConverter<IUpdater> {
    private final ObjectMapper objectMapper = ObjectMappers.getJsonMapper();

    @Override
    public IUpdater convert(String value) throws Exception {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }

        String trimmed = value.trim();

        // Try simple name matching first (case-insensitive)
        IUpdater simple = fromSimpleName(trimmed);
        if (simple != null) {
            return simple;
        }

        // Try JSON deserialization
        if (trimmed.startsWith("{")) {
            return fromJson(trimmed);
        }

        // Try "name:param=val,param=val" format (e.g., "adam:lr=0.001,beta1=0.9")
        if (trimmed.contains(":")) {
            return fromColonFormat(trimmed);
        }

        throw new CommandLine.TypeConversionException(
                "Unknown updater: '" + value + "'. Supported: adam, sgd, nesterovs, rmsprop, " +
                "adagrad, adadelta, nadam, amsgrad, adamax, noop. " +
                "Or use JSON: '{\"type\":\"adam\",\"learningRate\":0.001}'");
    }

    private IUpdater fromSimpleName(String name) {
        switch (name.toLowerCase()) {
            case "adam":       return new Adam();
            case "sgd":        return new Sgd();
            case "nesterovs":
            case "nesterov":   return new Nesterovs();
            case "rmsprop":    return new RmsProp();
            case "adagrad":    return new AdaGrad();
            case "adadelta":   return new AdaDelta();
            case "nadam":      return new Nadam();
            case "amsgrad":    return new AMSGrad();
            case "adamax":     return new AdaMax();
            case "noop":
            case "none":       return new NoOp();
            default:           return null;
        }
    }

    @SuppressWarnings("unchecked")
    private IUpdater fromJson(String json) throws Exception {
        Map<String, Object> map = objectMapper.readValue(json, Map.class);
        String type = (String) map.getOrDefault("type", map.getOrDefault("updater", ""));
        if (type == null || type.isEmpty()) {
            throw new CommandLine.TypeConversionException(
                    "JSON updater config must include a 'type' field. Got: " + json);
        }

        double lr = getDouble(map, "learningRate", getDouble(map, "lr", -1));

        switch (type.toLowerCase()) {
            case "adam": {
                Adam adam = new Adam();
                if (lr > 0) adam.setLearningRate(lr);
                if (map.containsKey("beta1")) adam.setBeta1(getDouble(map, "beta1", 0.9));
                if (map.containsKey("beta2")) adam.setBeta2(getDouble(map, "beta2", 0.999));
                if (map.containsKey("epsilon")) adam.setEpsilon(getDouble(map, "epsilon", 1e-8));
                return adam;
            }
            case "sgd": {
                Sgd sgd = lr > 0 ? new Sgd(lr) : new Sgd();
                return sgd;
            }
            case "nesterovs":
            case "nesterov": {
                Nesterovs nesterovs = new Nesterovs();
                if (lr > 0) nesterovs.setLearningRate(lr);
                if (map.containsKey("momentum")) nesterovs.setMomentum(getDouble(map, "momentum", 0.9));
                return nesterovs;
            }
            case "rmsprop": {
                RmsProp rmsProp = new RmsProp();
                if (lr > 0) rmsProp.setLearningRate(lr);
                if (map.containsKey("rmsDecay")) rmsProp.setRmsDecay(getDouble(map, "rmsDecay", 0.95));
                if (map.containsKey("epsilon")) rmsProp.setEpsilon(getDouble(map, "epsilon", 1e-8));
                return rmsProp;
            }
            case "adagrad": {
                AdaGrad adaGrad = new AdaGrad();
                if (lr > 0) adaGrad.setLearningRate(lr);
                if (map.containsKey("epsilon")) adaGrad.setEpsilon(getDouble(map, "epsilon", 1e-6));
                return adaGrad;
            }
            case "adadelta": {
                AdaDelta adaDelta = new AdaDelta();
                if (map.containsKey("rho")) adaDelta.setRho(getDouble(map, "rho", 0.95));
                if (map.containsKey("epsilon")) adaDelta.setEpsilon(getDouble(map, "epsilon", 1e-6));
                return adaDelta;
            }
            case "nadam": {
                Nadam nadam = new Nadam();
                if (lr > 0) nadam.setLearningRate(lr);
                if (map.containsKey("beta1")) nadam.setBeta1(getDouble(map, "beta1", 0.9));
                if (map.containsKey("beta2")) nadam.setBeta2(getDouble(map, "beta2", 0.999));
                if (map.containsKey("epsilon")) nadam.setEpsilon(getDouble(map, "epsilon", 1e-8));
                return nadam;
            }
            case "amsgrad": {
                AMSGrad amsGrad = new AMSGrad();
                if (lr > 0) amsGrad.setLearningRate(lr);
                if (map.containsKey("beta1")) amsGrad.setBeta1(getDouble(map, "beta1", 0.9));
                if (map.containsKey("beta2")) amsGrad.setBeta2(getDouble(map, "beta2", 0.999));
                if (map.containsKey("epsilon")) amsGrad.setEpsilon(getDouble(map, "epsilon", 1e-8));
                return amsGrad;
            }
            case "adamax": {
                AdaMax adaMax = new AdaMax();
                if (lr > 0) adaMax.setLearningRate(lr);
                if (map.containsKey("beta1")) adaMax.setBeta1(getDouble(map, "beta1", 0.9));
                if (map.containsKey("beta2")) adaMax.setBeta2(getDouble(map, "beta2", 0.999));
                if (map.containsKey("epsilon")) adaMax.setEpsilon(getDouble(map, "epsilon", 1e-8));
                return adaMax;
            }
            case "noop":
            case "none":
                return new NoOp();
            default:
                throw new CommandLine.TypeConversionException("Unknown updater type in JSON: '" + type + "'");
        }
    }

    private IUpdater fromColonFormat(String value) throws Exception {
        int colonIdx = value.indexOf(':');
        String name = value.substring(0, colonIdx).trim();
        String params = value.substring(colonIdx + 1).trim();

        IUpdater base = fromSimpleName(name);
        if (base == null) {
            throw new CommandLine.TypeConversionException("Unknown updater name: '" + name + "'");
        }

        // Parse param=value pairs
        StringBuilder jsonBuilder = new StringBuilder("{\"type\":\"").append(name).append("\"");
        for (String pair : params.split(",")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                jsonBuilder.append(",\"").append(kv[0].trim()).append("\":");
                try {
                    Double.parseDouble(kv[1].trim());
                    jsonBuilder.append(kv[1].trim());
                } catch (NumberFormatException e) {
                    jsonBuilder.append("\"").append(kv[1].trim()).append("\"");
                }
            }
        }
        jsonBuilder.append("}");

        return fromJson(jsonBuilder.toString());
    }

    private static double getDouble(Map<String, Object> map, String key, double defaultValue) {
        Object val = map.get(key);
        if (val instanceof Number) {
            return ((Number) val).doubleValue();
        }
        if (val instanceof String) {
            try {
                return Double.parseDouble((String) val);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }
}
