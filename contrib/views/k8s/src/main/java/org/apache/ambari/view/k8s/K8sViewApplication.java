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
