/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.apache.ambari.view.sqlassistant.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import javax.ws.rs.ext.ContextResolver;
import javax.ws.rs.ext.Provider;

/**
 * Provides a shared Gson instance to Jersey's message-body writers.
 * Mirrors the pattern used in the K8S view to ensure consistent JSON
 * serialisation across the view.
 */
@Provider
public class GsonConfigurator implements ContextResolver<Gson> {

    private final Gson gson;

    public GsonConfigurator() {
        this.gson = new GsonBuilder()
                .serializeNulls()
                .create();
    }

    /**
     * Returns the shared {@link Gson} instance configured for this view, regardless
     * of the requested type.  Jersey calls this method to resolve a {@code Gson}
     * instance during JSON serialisation and deserialisation.
     *
     * @param type the class for which a {@link Gson} instance is requested (not used)
     * @return the shared {@link Gson} instance with null-serialisation enabled
     */
    @Override
    public Gson getContext(Class<?> type) {
        return gson;
    }
}
