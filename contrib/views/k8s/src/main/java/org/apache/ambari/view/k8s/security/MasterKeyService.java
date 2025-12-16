package org.apache.ambari.view.k8s.security;

public interface MasterKeyService {
    char[] getMasterSecret();
    boolean isMasterKeyInitialized();
}
