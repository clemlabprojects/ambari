package org.apache.ambari.view.k8s.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.marcnuri.helm.Release;
import org.apache.ambari.view.DataStore;
import org.apache.ambari.view.PersistenceException;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.model.CommandState;
import org.apache.ambari.view.k8s.model.CommandStatus;
import org.apache.ambari.view.k8s.model.CommandType;
import org.apache.ambari.view.k8s.requests.HelmDeployRequest;
import org.apache.ambari.view.k8s.resources.HelmResource;
import org.apache.ambari.view.k8s.store.CommandStatusEntity;
import org.apache.ambari.view.k8s.service.ViewConfigurationService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.UUID;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Objects;

import static java.util.Objects.requireNonNull;

/**
 * Service for managing asynchronous Helm command execution
 */
public class CommandService {
    private static final Logger LOG = LoggerFactory.getLogger(CommandService.class);
    
    // Singleton per View instance
    private static final ConcurrentMap<String, CommandService> INSTANCES = new ConcurrentHashMap<>();

    public static CommandService get(ViewContext ctx) {
        return INSTANCES.computeIfAbsent(ctx.getInstanceName(), k -> new CommandService(ctx));
    }

    private final String nodeId = UUID.randomUUID().toString(); // identifier for this process
    private final ViewContext ctx;
    private final ViewConfigurationService configService;
    private final DataStore ds;
    private final ExecutorService exec;
    private final ScheduledExecutorService scheduler;
    private final Gson gson;

    // polling parameters during resume
    private static final int POLL_INTERVAL_SEC = 10;
    private static final int POLL_MAX_MINUTES = 15; // maximum wait time for resume

    private CommandService(ViewContext ctx) {
        this.ctx = requireNonNull(ctx);
        this.ds = ctx.getDataStore();
        this.exec = Executors.newFixedThreadPool(4);
        this.scheduler = Executors.newScheduledThreadPool(2);
        this.gson = new GsonBuilder().create();
        this.configService = new ViewConfigurationService(ctx);

        resumeInFlight(); // automatic restart on startup
    }

    // ---------------- Public API ----------------

    /** Submit a Helm deploy/upgrade (asynchronous persistent). */
    public String submitHelmDeploy(HelmDeployRequest req, String repoId, String version, String kubeContext) {
        final String id = UUID.randomUUID().toString();
        final String now = Instant.now().toString();

        // Required fields
        Objects.requireNonNull(req.getChart(), "chart is required");
        Objects.requireNonNull(req.getReleaseName(), "releaseName is required");
        Objects.requireNonNull(req.getNamespace(), "namespace is required");

        // Build a null-safe request payload
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("chart", req.getChart());
        request.put("releaseName", req.getReleaseName());
        request.put("namespace", req.getNamespace());
        if (req.getServiceKey() != null && !req.getServiceKey().isBlank()) {
            request.put("serviceKey", req.getServiceKey());
        }
        if (req.getValues() != null && !req.getValues().isEmpty()) {
            request.put("values", req.getValues());
        }
        if (repoId != null && !repoId.isBlank()) {
            request.put("repoId", repoId);
        }
        if (version != null && !version.isBlank()) {
            request.put("version", version);
        }
        if (kubeContext != null && !kubeContext.isBlank()) {
            request.put("kubeContext", kubeContext);
        }

        CommandStatusEntity e = new CommandStatusEntity();
        e.setId(id);
        e.setViewInstance(ctx.getInstanceName());
        e.setWorkerId(null);
        e.setAttempt(0);
        e.setType(CommandType.HELM_DEPLOY.name());
        e.setState(CommandState.PENDING.name());
        e.setPercent(0);
        e.setStep(0);
        e.setMessage("Queued...");
        e.setCreatedAt(now);
        e.setUpdatedAt(now);

        LOG.info("Submitting Helm deploy command id={} type={} at {}", id, e.getType(), now);
        LOG.info("Request payload: {}", gson.toJson(request));

        // Persist the request (null-safe; no Map.of)
        e.setRequestJson(gson.toJson(request));
        store(e);

        // Kick the worker (or schedule) immediately
        takeAndRun(id, /*resume*/ false);
        return id;
    }

    public CommandStatus getStatus(String id) {
        CommandStatusEntity e = find(id);
        if (e == null) return null;
        CommandStatus s = new CommandStatus();
        s.id = e.getId();
        s.type = CommandType.valueOf(e.getType());
        s.state = CommandState.valueOf(e.getState());
        s.percent = nvl(e.getPercent());
        s.step = nvl(e.getStep());
        s.message = e.getMessage();
        s.createdAt = e.getCreatedAt();
        s.updatedAt = e.getUpdatedAt();
        s.error = e.getError();
        if (e.getResultJson() != null) {
            try { s.result = gson.fromJson(e.getResultJson(), Map.class); } catch (Exception ignore) {}
        }
        return s;
    }

    // ---------------- Resume on startup ----------------

    /** Re-queue all PENDING/RUNNING commands for this View instance. */
    private void resumeInFlight() {
        try {
            @SuppressWarnings("unchecked")
            Iterable<CommandStatusEntity> all = 
                (Iterable<CommandStatusEntity>) ds.findAll(CommandStatusEntity.class, null);
            for (CommandStatusEntity e : all) {
                if (!ctx.getInstanceName().equals(e.getViewInstance())) continue;
                if (CommandState.PENDING.name().equals(e.getState()) || CommandState.RUNNING.name().equals(e.getState())) {
                    LOG.info("Resuming command id={} type={} state={} attempt={}", e.getId(), e.getType(), e.getState(), e.getAttempt());
                    takeAndRun(e.getId(), /*resume*/ true);
                }
            }
        } catch (Exception ex) {
            LOG.warn("resumeInFlight failed", ex);
        }
    }

    /** Reservation + execution. */
    private void takeAndRun(String id, boolean resume) {
        CommandStatusEntity e = find(id);
        if (e == null) return;

        // Reservation by this process (workerId)
        e.setWorkerId(nodeId);
        if (CommandState.PENDING.name().equals(e.getState())) {
            e.setState(CommandState.RUNNING.name());
            e.setMessage(resume ? "Resuming..." : "Starting...");
            e.setPercent(0);
            e.setStep(0);
        }
        e.setAttempt(nvl(e.getAttempt()) + 1);
        e.setUpdatedAt(Instant.now().toString());
        store(e);

        exec.submit(() -> runDeploy(id, resume));
    }

    // ---------------- Execution + resume ----------------

    private void runDeploy(String id, boolean resume) {
        CommandStatusEntity e = find(id);
        if (e == null) return;

        try {
            tick(e, CommandState.RUNNING, resume ? "Resume / reconciliation..." : "Validation...", 0, 5);
            LOG.info("Running command id={} type={} attempt={}", e.getId(), e.getType(), e.getAttempt());

            @SuppressWarnings("rawtypes")
            Map payload = gson.fromJson(e.getRequestJson(), Map.class);
            LOG.info("Command payload: {}", gson.toJson(payload));

            // Accept both shapes
            HelmDeployRequest req = buildRequestFromPayload(payload);
            String repoId = (String) (payload != null ? payload.get("repoId") : null);
            String version = (String) (payload != null ? payload.get("version") : null);
            String kubeContext = (String) (payload != null ? payload.get("kubeContext") : null);

            if (req == null || isBlank(req.getChart()) || isBlank(req.getReleaseName()) || isBlank(req.getNamespace())) {
                throw new IllegalArgumentException("Invalid payload: chart, releaseName and namespace are required");
            }

            final String name = req.getReleaseName();
            final String ns = req.getNamespace();

            // kubeconfig preference: provided kubeContext first, else from View config
            final String kc = !isBlank(kubeContext) ? kubeContext : kubeconfig();

            // If we're resuming, check current release state first
            if (resume) {
                Release current = findRelease(ns, name, kc);
                if (current != null) {
                    String st = safe(current.getStatus());
                    if (isDeployed(st)) {
                        succeed(e, toReleaseDto(current));
                        return;
                    } else if (isPending(st)) {
                        e.setMessage("Waiting for release stabilization (resume)...");
                        e.setStep(1); e.setPercent(20); e.setUpdatedAt(Instant.now().toString());
                        store(e);
                        schedulePolling(e.getId(), ns, name, kc);
                        return;
                    } else if (isFailed(st)) {
                        LOG.info("Resuming {}: status FAILED → attempting redeployment", name);
                    }
                }
            }

            // Deploy / Upgrade
            tick(e, CommandState.RUNNING, "Helm deployment...", 1, 30);
            HelmService svc = new HelmService(ctx);
            Release rel = svc.deployOrUpgrade(req, kc, repoId, version);

            tick(e, CommandState.RUNNING, "Finalizing...", 2, 90);
            succeed(e, toReleaseDto(rel));

        } catch (Throwable ex) {
            LOG.error("Command {} failed", id, ex);
            fail(e, ex);
        }
    }

    /** Schedule regular polling (every 10s) until stability. */
    private void schedulePolling(String id, String ns, String name, String kubeconfig) {
        final long deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(POLL_MAX_MINUTES);

        final Runnable task = () -> {
            CommandStatusEntity e = find(id);
            if (e == null) return;
            try {
                Release r = findRelease(ns, name, kubeconfig);
                if (r == null) {
                    tick(e, CommandState.RUNNING, "Release not found, retrying verification...", e.getStep(), e.getPercent());
                    return;
                }
                String st = safe(r.getStatus());
                if (isDeployed(st)) {
                    succeed(e, toReleaseDto(r));
                    throw new CancellationException("done");
                } else if (isFailed(st)) {
                    fail(e, new IllegalStateException("Release failed: " + st));
                    throw new CancellationException("done");
                } else if (isPending(st)) {
                    // stay waiting, light update
                    e.setMessage("Waiting... status: " + st);
                    e.setUpdatedAt(Instant.now().toString());
                    e.setPercent(Math.min(85, nvl(e.getPercent()) + 1));
                    store(e);
                } else {
                    // unknown status: continue polling
                    e.setMessage("Current status: " + st);
                    e.setUpdatedAt(Instant.now().toString());
                    store(e);
                }

                if (System.currentTimeMillis() > deadline) {
                    fail(e, new TimeoutException("Resume timeout exceeded"));
                    throw new CancellationException("timeout");
                }
            } catch (CancellationException done) {
                throw done;
            } catch (Throwable t) {
                LOG.warn("polling {} failed: {}", id, t.getMessage());
            }
        };

        final ScheduledFuture<?> f = scheduler.scheduleAtFixedRate(task, 0, POLL_INTERVAL_SEC, TimeUnit.SECONDS);
        // Clean cancellation when finished (CancellationException thrown from task)
        scheduler.scheduleAtFixedRate(() -> {
            if (f.isCancelled() || f.isDone()) return;
            CommandStatusEntity e = find(id);
            if (e == null) { f.cancel(true); return; }
            if (CommandState.SUCCEEDED.name().equals(e.getState()) || CommandState.FAILED.name().equals(e.getState())) {
                f.cancel(true);
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    private Release findRelease(String ns, String name, String kubeconfig) {
        try {
            HelmService svc = new HelmService(ctx);
            return svc.helm().list(ns, kubeconfig, false).stream()
                .filter(r -> name.equals(r.getName()))
                .findFirst().orElse(null);
        } catch (Exception ex) {
            LOG.warn("findRelease error: {}", ex.getMessage());
            return null;
        }
    }

    // ---------------- State/persistence helpers ----------------

    private static boolean isDeployed(String s) {
        return s != null && s.toLowerCase(Locale.ROOT).contains("deployed");
    }
    private static boolean isPending(String s) {
        return s != null && s.toLowerCase(Locale.ROOT).startsWith("pending");
    }
    private static boolean isFailed(String s) {
        return s != null && s.toLowerCase(Locale.ROOT).contains("failed");
    }
    private static String safe(Object o) { return o == null ? null : String.valueOf(o); }

    private void tick(CommandStatusEntity e, CommandState st, String msg, int step, int pct) {
        e.setState(st.name());
        e.setMessage(msg);
        e.setStep(step);
        e.setPercent(pct);
        e.setUpdatedAt(Instant.now().toString());
        store(e);
    }
    
    private void succeed(CommandStatusEntity e, Map<String,Object> result) {
        e.setState(CommandState.SUCCEEDED.name());
        e.setPercent(100);
        e.setMessage("Completed");
        e.setResultJson(gson.toJson(result));
        e.setUpdatedAt(Instant.now().toString());
        store(e);
    }
    
    private void fail(CommandStatusEntity e, Throwable ex) {
        e.setState(CommandState.FAILED.name());
        e.setMessage("Failed");
        e.setError(ex.getMessage());
        e.setUpdatedAt(Instant.now().toString());
        store(e);
    }

    private void store(CommandStatusEntity e) {
        try { ds.store(e); } catch (PersistenceException pe) { LOG.error("store CommandStatusEntity failed", pe); }
    }
    
    private CommandStatusEntity find(String id) {
        try { return (CommandStatusEntity) ds.find(CommandStatusEntity.class, id); }
        catch (PersistenceException pe) { LOG.error("find CommandStatusEntity failed", pe); return null; }
    }
    
    private static int nvl(Integer i) { return i == null ? 0 : i; }

    private String kubeconfig() {
        return this.configService.getKubeconfigContents();
    }
    
    private static Map<String,Object> toReleaseDto(com.marcnuri.helm.Release rel) {
        Map<String,Object> dto = new LinkedHashMap<>();
        if (rel == null) {
            dto.put("status", "UNKNOWN");
            return dto;
        }
        dto.put("name", rel.getName());
        dto.put("namespace", rel.getNamespace());
        try { dto.put("revision", rel.getRevision()); } catch (Throwable ignored) {}
        try { dto.put("status", rel.getStatus());   } catch (Throwable ignored) {}
        return dto;
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    private HelmDeployRequest buildRequestFromPayload(Map payload) {
        if (payload == null) return null;

        // Old shape: { "req": { ... }, "repoId": "...", "version": "...", "kubeContext": "..." }
        Object reqObj = payload.get("req");
        if (reqObj != null) {
            // Gson round-trip to convert LinkedTreeMap → HelmDeployRequest
            return gson.fromJson(gson.toJson(reqObj), HelmDeployRequest.class);
        }

        // New shape (flattened): { "chart": "...", "releaseName": "...", "namespace": "...", ... }
        HelmDeployRequest r = new HelmDeployRequest();
        r.setChart((String) payload.get("chart"));
        r.setReleaseName((String) payload.get("releaseName"));
        r.setNamespace((String) payload.get("namespace"));

        Object valsObj = payload.get("values");
        if (valsObj instanceof Map) {
            Map<String,Object> m = new LinkedHashMap<>((Map<String,Object>) valsObj);
            // strip UI-only keys
            m.remove("installMode");
            m.remove("repoId");
            m.remove("svcKey");
            r.setValues(m);
        }

        Object sk = payload.get("serviceKey");
        if (sk != null) r.setServiceKey(String.valueOf(sk));

        return r;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
