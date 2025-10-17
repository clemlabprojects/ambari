package org.apache.ambari.server.api.resources;

import org.apache.ambari.server.controller.spi.Resource;

public class AdhocKeytabResourceDefinition extends BaseResourceDefinition {
    public AdhocKeytabResourceDefinition() {
        super(Resource.Type.ADHOC_KEYTAB);
    }
    @Override public String getPluralName()   { return "adhoc_keytabs"; }
    @Override public String getSingularName() { return "adhoc_keytab"; }
}
