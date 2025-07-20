package org.apache.ambari.view.k8s;

import org.glassfish.jersey.media.multipart.MultiPartFeature;

import javax.ws.rs.core.Application;
import java.util.HashSet;
import java.util.Set;

/**
 * Classe de configuration de l'application JAX-RS pour la vue K8S.
 * C'est ici que sont déclarés tous les services et fonctionnalités de l'API.
 */
public class K8sViewApplication extends Application {
    @Override
    public Set<Class<?>> getClasses() {
        final Set<Class<?>> classes = new HashSet<>();
        
        // On déclare notre service REST principal.
        classes.add(KubeService.class);
        
        // On déclare la fonctionnalité nécessaire pour gérer les téléversements de fichiers.
        classes.add(MultiPartFeature.class);
        
        return classes;
    }
}
