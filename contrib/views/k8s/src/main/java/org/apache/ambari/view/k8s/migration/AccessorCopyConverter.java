/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ambari.view.k8s.migration;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

import org.apache.ambari.view.migration.EntityConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Copies one persisted KDPS entity from the previous view version into the current one by calling the
 * public getters of the old object and the public setters of the new one, matched by name.
 *
 * <p>Ambari's default copy uses bean introspection, which honours a {@code BeanInfo}. KDPS entities use
 * BeanInfo classes to hide accessors from the DataStore (for example the nine Git accessors of a release,
 * which are folded into one {@code gitMetaJson} column since 1.0.0.8). Introspection therefore skips
 * them and the copied release loses its Git/Flux metadata. Plain reflection sees every accessor, so the
 * new entity's own setters rebuild whatever storage format the new version uses.</p>
 *
 * <p>Rules: only non-null values are copied (a fresh entity keeps its own defaults), a setter is used only
 * when the getter's type fits its parameter, and a failing property is logged and skipped rather than
 * aborting the whole migration.</p>
 */
public class AccessorCopyConverter implements EntityConverter {

    private static final Logger LOG = LoggerFactory.getLogger(AccessorCopyConverter.class);

    private static final Map<Class<?>, Class<?>> BOXED = new HashMap<>();
    static {
        BOXED.put(boolean.class, Boolean.class);
        BOXED.put(int.class, Integer.class);
        BOXED.put(long.class, Long.class);
        BOXED.put(double.class, Double.class);
        BOXED.put(float.class, Float.class);
        BOXED.put(short.class, Short.class);
        BOXED.put(byte.class, Byte.class);
        BOXED.put(char.class, Character.class);
    }

    @Override
    public void convert(Object orig, Object dest) {
        if (orig == null || dest == null) {
            return;
        }
        Map<String, Method> getters = new HashMap<>();
        for (Method m : orig.getClass().getMethods()) {
            if (m.getParameterCount() != 0 || Modifier.isStatic(m.getModifiers()) || m.getReturnType() == void.class) {
                continue;
            }
            String property = propertyOf(m.getName(), m.getReturnType());
            if (property != null && !"class".equals(property)) {
                getters.put(property, m);
            }
        }
        for (Method setter : dest.getClass().getMethods()) {
            if (setter.getParameterCount() != 1 || Modifier.isStatic(setter.getModifiers())
                    || !setter.getName().startsWith("set") || setter.getName().length() == 3) {
                continue;
            }
            String property = decapitalize(setter.getName().substring(3));
            Method getter = getters.get(property);
            if (getter == null || !fits(getter.getReturnType(), setter.getParameterTypes()[0])) {
                continue;
            }
            try {
                Object value = getter.invoke(orig);
                if (value != null) {
                    setter.invoke(dest, value);
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                LOG.warn("KDPS migration: property {} of {} not copied: {}", property,
                        dest.getClass().getSimpleName(), e.toString());
            }
        }
    }

    /** Returns the bean property named by a getter, or null when the method is not a getter. */
    static String propertyOf(String methodName, Class<?> returnType) {
        if (methodName.startsWith("get") && methodName.length() > 3) {
            return decapitalize(methodName.substring(3));
        }
        if (methodName.startsWith("is") && methodName.length() > 2
                && (returnType == boolean.class || returnType == Boolean.class)) {
            return decapitalize(methodName.substring(2));
        }
        return null;
    }

    static boolean fits(Class<?> valueType, Class<?> parameterType) {
        Class<?> from = valueType.isPrimitive() ? BOXED.get(valueType) : valueType;
        Class<?> to = parameterType.isPrimitive() ? BOXED.get(parameterType) : parameterType;
        return from != null && to != null && to.isAssignableFrom(from);
    }

    private static String decapitalize(String name) {
        if (name.isEmpty()) {
            return name;
        }
        if (name.length() > 1 && Character.isUpperCase(name.charAt(1)) && Character.isUpperCase(name.charAt(0))) {
            return name;
        }
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }
}
