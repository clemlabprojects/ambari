package org.apache.ambari.server.api.services;

import java.util.HashMap;
import java.util.Map;

import javax.ws.rs.POST;
//import javax.ws.rs.Path;
import javax.ws.rs.Produces;

import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.apache.ambari.annotations.ApiIgnore;
import org.apache.ambari.server.api.resources.ResourceInstance;
import org.apache.ambari.server.controller.spi.Resource;


/**
 * POST /api/v1/clusters/{cluster}/adhoc_keytab
 * Enqueues a pollable Ambari Request that runs GenerateAdhocKeytabServerAction.
 * No custom-action XML is used; the action is referenced by CLASS name.
 */

public class AdhocKeytabService extends BaseService {
    private final String clusterName;

    public AdhocKeytabService() {
        this(null);
    }

    public AdhocKeytabService(String clusterName) { this.clusterName = clusterName; }

    @POST @ApiIgnore // until documented
    @Produces("text/plain")
    public Response create(String body, @Context HttpHeaders headers, @Context UriInfo ui) {
        // delegate to the provider via the factory pipeline
        return handleRequest(headers, body, ui, Request.Type.POST, createResource());
    }

    private ResourceInstance createResource() {
        Map<Resource.Type,String> ids = new HashMap<>();
        ids.put(Resource.Type.Cluster, clusterName);
        return createResource(Resource.Type.ADHOC_KEYTAB, ids);
    }
}

