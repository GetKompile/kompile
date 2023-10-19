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

import ai.konduit.pipelinegenerator.main.models.Convert;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class PropertyResolverTest {



    @Test
    public void testConvertStatic() {
        new Convert();
    }
    @Test
    public void testPropertyResolver() {
        PropertyResolver propertyResolver = new PropertyResolver();
        System.setProperty("test","test");
        assertNotNull(propertyResolver.getValue("test"));
        assertEquals("test",propertyResolver.getValue("test"));
        System.setProperty("test.value","test.value");
        assertNotNull(propertyResolver.getValue("test.value"));
        assertEquals("test.value",propertyResolver.getValue("test.value"));
        System.setProperty("test.value2","${test.value}");
        assertNotNull(propertyResolver.getValue("test.value2"));
        assertEquals("test.value",propertyResolver.getValue("test.value2"));
        System.setProperty("test.value3","${test.value}${test.value}");
        assertNotNull(propertyResolver.getValue("test.value3"));
        assertEquals("test.valuetest.value",propertyResolver.getValue("test.value3"));
        System.setProperty("test.value4","${test.value2}${test.value2}");
        assertNotNull(propertyResolver.getValue("test.value3"));
        assertEquals("test.valuetest.value",propertyResolver.getValue("test.value3"));
    }

}
