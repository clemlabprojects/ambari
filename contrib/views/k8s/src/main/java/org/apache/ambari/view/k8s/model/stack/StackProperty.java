package org.apache.ambari.view.k8s.model.stack;

public class StackProperty {
    public String name;          // "SUPERSET_SECRET_KEY"
    public String displayName;
    public String description;

    // Types: "string", "int", "boolean", "password", "content"
    public String type = "string";

    public Object value;         // Default value

    // If set, the service reads the content of this file from classpath relative to config
    public String valueSourceFile;

    public boolean required = false;
    public String language;      // For editor: "python", "xml", "ini"
}