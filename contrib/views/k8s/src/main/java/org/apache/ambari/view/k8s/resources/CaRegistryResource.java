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
package org.apache.ambari.view.k8s.resources;

import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.security.AuthHelper;
import org.apache.ambari.view.k8s.service.CaRegistryService;
import org.apache.ambari.view.k8s.service.KubernetesService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.List;
import java.util.Map;

/**
 * REST endpoints for the Company Issuing CA registry. Backed by
 * {@link CaRegistryService}; each CA is persisted as a K8s Secret in a privileged
 * namespace (default {@code ambari-pki}).
 *
 * <p>Mounted at {@code /resources/api/pki/cas}. All mutating endpoints require the
 * standard view write permission ({@link AuthHelper#checkWritePermission()}).
 *
 * <h3>Endpoints</h3>
 * <ul>
 *   <li>{@code GET    /pki/cas}            — list registry entries (no private keys)</li>
 *   <li>{@code GET    /pki/cas/{name}}     — get one entry (no private key)</li>
 *   <li>{@code POST   /pki/cas}            — upload a new CA (or replace one with the same name)</li>
 *   <li>{@code DELETE /pki/cas/{name}}     — remove a CA</li>
 * </ul>
 *
 * <p>Upload payload (JSON):
 * <pre>
 * {
 *   "caName": "acme-issuing-ca",
 *   "caCertPem": "-----BEGIN CERTIFICATE-----\\n...",
 *   "caKeyPem":  "-----BEGIN PRIVATE KEY-----\\n...",
 *   "description": "Acme Corp intermediate CA, valid until 2027"
 * }
 * </pre>
 */
public class CaRegistryResource {

    private static final Logger LOG = LoggerFactory.getLogger(CaRegistryResource.class);

    private final ViewContext viewContext;
    private final KubernetesService kubernetesService;
    private final AuthHelper authHelper;

    public CaRegistryResource(ViewContext viewContext, KubernetesService kubernetesService) {
        this.viewContext = viewContext;
        this.kubernetesService = kubernetesService;
        this.authHelper = new AuthHelper(viewContext);
    }

    private CaRegistryService service() {
        return new CaRegistryService(viewContext, kubernetesService);
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response list() {
        try {
            List<CaRegistryService.CaEntry> entries = service().list();
            return Response.ok(Map.of("items", entries)).build();
        } catch (Exception ex) {
            LOG.warn("CA registry list failed: {}", ex.toString());
            return Response.serverError().entity(Map.of("error", ex.getMessage())).build();
        }
    }

    @GET
    @Path("{name}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response get(@PathParam("name") String caName) {
        try {
            return Response.ok(service().get(caName)).build();
        } catch (IllegalArgumentException iae) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", iae.getMessage())).build();
        } catch (Exception ex) {
            LOG.warn("CA registry get {} failed: {}", caName, ex.toString());
            return Response.serverError().entity(Map.of("error", ex.getMessage())).build();
        }
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response upload(Map<String, Object> body) {
        try {
            authHelper.checkWritePermission();
            if (body == null) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "Empty request body")).build();
            }
            String caName = strOrNull(body.get("caName"));
            String caCertPem = strOrNull(body.get("caCertPem"));
            String caKeyPem = strOrNull(body.get("caKeyPem"));
            String description = strOrNull(body.get("description"));
            CaRegistryService.CaEntry entry = service().upload(caName, caCertPem, caKeyPem, description);
            return Response.status(Response.Status.CREATED).entity(entry).build();
        } catch (IllegalArgumentException iae) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", iae.getMessage())).build();
        } catch (Exception ex) {
            LOG.warn("CA registry upload failed: {}", ex.toString());
            return Response.serverError().entity(Map.of("error", ex.getMessage())).build();
        }
    }

    @DELETE
    @Path("{name}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response delete(@PathParam("name") String caName) {
        try {
            authHelper.checkWritePermission();
            service().delete(caName);
            return Response.noContent().build();
        } catch (IllegalArgumentException iae) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", iae.getMessage())).build();
        } catch (Exception ex) {
            LOG.warn("CA registry delete {} failed: {}", caName, ex.toString());
            return Response.serverError().entity(Map.of("error", ex.getMessage())).build();
        }
    }

    /**
     * Promote an uploaded Company CA to a cert-manager.io ClusterIssuer.
     * Creates a {@code ClusterIssuer} of kind {@code ca} that references the existing
     * {@code kubernetes.io/tls} Secret in {@code ambari-pki}, so any service installed
     * via the {@code signedByClusterIssuer} TLS mode can issue leaves chained to the
     * same Company root the admin already uploaded. Idempotent: re-promoting overwrites.
     *
     * Response payload:
     * <pre>{ "caName": "...", "clusterIssuerName": "clemlab-...", "ready": false }</pre>
     * The {@code ready} flag is read once just after apply — cert-manager may take
     * a second or two to reconcile. The Cluster Security page polls the discovery
     * endpoint for the live status.
     */
    @POST
    @Path("{name}/promote-to-cluster-issuer")
    @Produces(MediaType.APPLICATION_JSON)
    public Response promoteToClusterIssuer(@PathParam("name") String caName) {
        try {
            authHelper.checkWritePermission();
            CaRegistryService.CaEntry entry = service().get(caName);
            // ClusterIssuer name we use: "clemlab-<sanitized-caName>". Keep predictable
            // so re-promotes don't proliferate issuers and the wizard's discovery list
            // shows a stable name.
            String issuerName = "clemlab-" + caName.toLowerCase().replaceAll("[^a-z0-9-]", "-");
            kubernetesService.applyCertManagerClusterIssuerFromCa(issuerName, entry.namespace(), entry.secretName());
            // Best-effort: poll once for readiness so the UI shows a useful value
            // immediately. The discovery endpoint will tell the truth on next refresh.
            boolean ready = kubernetesService.isClusterIssuerReady(issuerName);
            return Response.ok(Map.of(
                    "caName", caName,
                    "clusterIssuerName", issuerName,
                    "ready", ready
            )).build();
        } catch (IllegalArgumentException iae) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", iae.getMessage())).build();
        } catch (Exception ex) {
            LOG.warn("CA registry promote {} failed: {}", caName, ex.toString());
            return Response.serverError().entity(Map.of("error", ex.getMessage())).build();
        }
    }

    private static String strOrNull(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }
}
