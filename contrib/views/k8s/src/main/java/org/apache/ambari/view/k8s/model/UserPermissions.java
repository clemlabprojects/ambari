package org.apache.ambari.view.k8s.model;

public class UserPermissions {
    private final String role;
    private final boolean canConfigure;
    private final boolean canWrite;

    public UserPermissions(String role, boolean canConfigure, boolean canWrite) {
        this.role = role;
        this.canConfigure = canConfigure;
        this.canWrite = canWrite;
    }

    public String getRole() { return role; }
    public boolean isCanConfigure() { return canConfigure; }
    public boolean isCanWrite() { return canWrite; }
}
