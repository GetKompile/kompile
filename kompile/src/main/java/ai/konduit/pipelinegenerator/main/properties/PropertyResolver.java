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

package ai.konduit.pipelinegenerator.main.properties;


import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PropertyResolver {

    public final static String PROPERTY_SPLIT = "\\$\\{[a-zA-Z\\d]+\\.[a-zA-Z\\d]+\\}";
    public final static Pattern PROPERTY_SPLIT_PATTERN = Pattern.compile(PROPERTY_SPLIT);

    public String getValue(String propertyName) {
        if(!System.getProperties().containsKey(propertyName))
            return null;
        String value = System.getProperty(propertyName);
        StringBuilder finalValue = new StringBuilder();
        Matcher matcher = PROPERTY_SPLIT_PATTERN.matcher(value);
        if(value.contains("${")) {
            while(matcher.find()) {
                String valueString = matcher.group();
                if(valueString.matches(PROPERTY_SPLIT)) {
                    appendPropertyValue(finalValue, valueString);
                } else { //no value to resolve
                    finalValue.append(valueString);
                }
            }

            return finalValue.toString();
        } else {
            return value;
        }


    }

    private void appendPropertyValue(StringBuilder finalValue, String valueString) {
        String strip = valueString.replace("${","").replace("}","");
        //resolve from environment
        if(strip.startsWith("env.")) {
            strip = strip.substring(3);
            finalValue.append(System.getenv(strip));
        }
        else  {
            //resolve from JVM property
            String value = System.getProperty(strip);
            //also a variable,resolve recursively
            if(value.contains("${")) {
               appendPropertyValue(finalValue,valueString);

            } else {
                finalValue.append(System.getProperty(strip));
            }

        }
    }

}
