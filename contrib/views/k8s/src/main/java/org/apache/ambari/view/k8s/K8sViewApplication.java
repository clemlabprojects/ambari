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
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ambari.view.k8s;

import org.glassfish.jersey.media.multipart.MultiPartFeature;

import org.apache.ambari.view.k8s.resources.CommandResource;
import org.apache.ambari.view.k8s.resources.StackResource;

import javax.ws.rs.core.Application;
import java.util.HashSet;
import java.util.Set;

/**
 * JAX-RS application configuration class for the K8S view.
 * This is where all the services and API features are declared.
 */
public class K8sViewApplication extends Application {
    @Override
    public Set<Class<?>> getClasses() {
        final Set<Class<?>> classes = new HashSet<>();
        
        // Declare our main REST service
        classes.add(KubeService.class);
        classes.add(CommandResource.class);
        classes.add(StackResource.class);

        // Declare the feature needed to handle file uploads
        classes.add(MultiPartFeature.class);
        
        return classes;
    }
}
