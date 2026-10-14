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

package org.apache.ambari.view.k8s.security;

import com.google.gson.Gson;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The Ambari master key cannot be found, so KDPS can neither encrypt a credential nor read one it stored.
 *
 * <p>A request that ends with it answers HTTP 412 (Precondition Failed) with the steps to fix it, whether a resource
 * returns {@link #response()} itself or lets the exception through. The JSON body carries {@link #CODE}, which tells
 * this apart from the 412 of a view that has no cluster connection yet.
 */
public class MasterKeyUnavailableException extends WebApplicationException {

    /** The {@code code} of the JSON body. */
    public static final String CODE = "MASTER_KEY_UNAVAILABLE";

    /** What the administrator has to do, shown as it is in the view. */
    public static final String MESSAGE = "KDPS needs the Ambari master key to encrypt the credentials it keeps: run "
            + "'ambari-server setup-security' (encrypt passwords, persist the master key) or set "
            + "AMBARI_SECURITY_MASTER_KEY, then restart Ambari.";

    private static final Gson GSON = new Gson();

    public MasterKeyUnavailableException() {
        // The (Response) constructor: the JAX-RS 1 API on the compile class path has no (String, Response) one.
        super(response());
    }

    @Override
    public String getMessage() {
        return MESSAGE;
    }

    /**
     * The answer to a request that needs the master key while there is none.
     *
     * @return HTTP 412 with {@code {"error": MESSAGE, "code": CODE}}
     */
    public static Response response() {
        return Response.status(Response.Status.PRECONDITION_FAILED)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(GSON.toJson(body()))
                .build();
    }

    /** The JSON body of {@link #response()}, as a map. */
    public static Map<String, Object> body() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", MESSAGE);
        body.put("code", CODE);
        return body;
    }
}
