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

package org.apache.ambari.view.k8s.utils;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.admissionregistration.v1.MutatingWebhook;
import io.fabric8.kubernetes.api.model.admissionregistration.v1.MutatingWebhookConfiguration;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.apache.ambari.view.ViewContext;
import org.apache.ambari.view.k8s.security.KeytabWebhookAuthenticator;
import org.apache.ambari.view.k8s.service.KubernetesService;
import org.apache.ambari.view.k8s.service.WebHookConfigurationService;
import org.apache.ambari.view.k8s.store.InstanceSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.auth.x500.X500Principal;
import javax.ws.rs.core.MultivaluedMap;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * Bootstrapper that prepares ONLY prerequisites for the mutating webhook:
 *  - ensures target namespace exists
 *  - ensures Ambari CA exists (or creates it)
 *  - issues client mTLS cert and stores it into a Secret (webhook -> Ambari); it is kept while valid, and renewed for
 *    the same key (also in the background, see {@link #renewClientCertificateIfDue})
 *  - issues serving TLS cert for the Kubernetes Service DNS and stores it into a Secret
 *  - computes caBundle (base64 DER of Ambari CA) and persists it to the instance settings
 *  - ensures the webhook's Ambari account, which may only open this view instance (see
 *    {@link KeytabWebhookAuthenticator}); its password is derived from the client key, so none is stored in
 *    Kubernetes
 *
 * It DOES NOT install any Kubernetes resources for the webhook (no Deployment, no WebhookConfiguration).
 * Run this once on View startup; it is safe to re-run (idempotent).
 */
public class WebHookBootstrap {

    private static final Logger LOG = LoggerFactory.getLogger(WebHookBootstrap.class);
    private static final String BC = "BC";
    /**
     * Credential store alias that names the webhook's Ambari account. It records that the view created that account, so
     * that only an account the view created is ever removed. The credential store is shared by the whole Ambari server,
     * so each view instance records its own account under this prefix followed by {@code .<instance>} (see
     * {@link #accountAlias}). Earlier versions recorded under this bare alias the one account they shared between all
     * the view instances, an Ambari administrator.
     */
    public static final String USER_ALIAS = "ambari.k8s.webhook.user";
    /** Credential store alias of the password earlier versions generated for the account; no longer kept. */
    public static final String PASS_ALIAS = "ambari.k8s.webhook.password";
    /** Name of the Kerberos keytab webhook: the prefix of its {@code k8s.view.webhooks.<name>.*} settings. */
    public static final String KEYTAB_WEBHOOK = "keytab-webhook";
    /** Common name of the client certificate the view issues to the webhook. */
    public static final String CLIENT_CERTIFICATE_CN = "ambari-webhook-client";
    /** Helm value of the webhook chart naming the Ambari account it signs in with. */
    public static final String ACCOUNT_USERNAME_VALUE = "auth.account.username";
    /** Earlier versions wrote the account's password into this Secret of the webhook's namespace. */
    private static final String LEGACY_CREDENTIALS_SECRET = "webhook-user";
    /**
     * Validity of the client certificate. The view checks it on every keytab request, so it is renewed well before it
     * expires: at a WEBHOOK-mode deploy, when the view starts, and daily (see {@link #renewClientCertificateIfDue}).
     */
    static final int CLIENT_CERTIFICATE_VALIDITY_DAYS = 365;
    static final int CLIENT_CERTIFICATE_MIN_DAYS_LEFT = 90;
    /** Below this many days left, a renewal that fails is logged as an error, not a warning. */
    private static final int CLIENT_CERTIFICATE_ALERT_DAYS = 30;
    private static final Map<String, String> CLIENT_CERTIFICATE_LABELS =
            Map.of("managed-by", "ambari-k8s-view", "component", "webhook-client-mtls");

    static {
        // BouncyCastle once per JVM
        Security.addProvider(new BouncyCastleProvider());
    }

    /**
     * Prepare prerequisites for a given webhook.
     *
     * Reads the following optional keys from ambari.properties (passthrough convention):
     *   k8s.view.webhooks.<webhookName>.namespace              (default: ambari-managed-mutating-webhooks)
     *   k8s.view.webhooks.<webhookName>.appName               (default: <webhookName>)
     *   k8s.view.webhooks.<webhookName>.dnsSuffix             (default: .svc.cluster.local)
     *   k8s.view.webhooks.<webhookName>.clientMtls.secretName (default: <webhookName>-client-tls)
     *   k8s.view.webhooks.<webhookName>.serverTls.secretName  (default: <webhookName>-tls)
     *
     * Writes (into the instance settings) for Helm to consume later:
     *   k8s.view.webhooks.<webhookName>.webhook.caBundle = <base64 DER of Ambari CA>
     *
     * @param ctx          Ambari ViewContext
     * @param k8sSvc       KubernetesService adapter
     * @param webhookName  logical name (must match your Helm values prefix)
     * @param callerHeaders the request of an Ambari administrator, whose credentials set up the webhook's account
     */
    public static void prepareWebhookPrereqs(ViewContext ctx,
                                             KubernetesService k8sSvc,
                                             String webhookName,
                                             MultivaluedMap<String,String> callerHeaders) {
        Objects.requireNonNull(ctx,          "ViewContext must not be null");
        Objects.requireNonNull(k8sSvc,       "KubernetesService must not be null");
        Objects.requireNonNull(webhookName,  "webhookName must not be null");

        AmbariAliasResolver ambariAliasResolver = new AmbariAliasResolver(ctx);
        final long t0 = System.currentTimeMillis();
        LOG.info("[webhook={}]: starting prerequisite preparation", webhookName);

        // ---- 1) Read inputs from ambari.properties with sensible defaults ----
        final String ns        = prop(ctx, webhookName, "namespace",              "ambari-mutating-webhooks");
        final String appName   = prop(ctx, webhookName, "appName",                webhookName);
        final String dnsSuffix = prop(ctx, webhookName, "dnsSuffix",              ".svc.cluster.local");
        final String clientSec = prop(ctx, webhookName, "clientMtls.secretName",  webhookName + "-client-tls");
        final String serveSec  = prop(ctx, webhookName, "serverTls.secretName",   webhookName + "-tls");
        final String secretName = prop(ctx, webhookName, "backend.caSecretName",   "ambari-root-ca");
        final String webhookUserName = webhookUserName(ctx, webhookName);

        final int minDaysLeft = 30;
        LOG.info("[webhook={}] resolved inputs: namespace='{}', appName='{}', dnsSuffix='{}', clientSecret='{}', servingSecret='{}'",
                webhookName, ns, appName, dnsSuffix, clientSec, serveSec);

        // ---- 2) Ensure namespace exists ----
        try {
            k8sSvc.createNamespace(ns);
            LOG.info("[webhook={}] ensured namespace '{}'", webhookName, ns);
        } catch (Exception e) {
            LOG.error("[webhook={}] failed ensuring namespace '{}'", webhookName, ns, e);
            throw e;
        }

        // ---- 3) Ensure Ambari CA exists (or create it) ----
        WebHookConfigurationService webHookConfigurationService = new WebHookConfigurationService(ctx, k8sSvc);
        WebHookConfigurationService.CertificateAuthorityMaterial ca = webHookConfigurationService.ensureAmbariCertificateAuthority();
        LOG.info("[webhook={}] Ambari CA ready (dir={})", webhookName, ca.storageDirectory());

        // ---- 4) Ensure client mTLS Secret used by the webhook to call Ambari ----
        //         Kept while valid, and renewed for the same key: the webhook's Ambari password is derived from
        //         its key, so a new key would change the password under the running webhook.
        try {
            webHookConfigurationService.ensureOrRotateClientMtlsSecret(
                    ns,
                    clientSec,
                    CLIENT_CERTIFICATE_CN,
                    List.of(),                         // DNS SANs (optional for client auth)
                    List.of(),                         // URI SANs (optional)
                    CLIENT_CERTIFICATE_VALIDITY_DAYS,
                    CLIENT_CERTIFICATE_MIN_DAYS_LEFT,
                    CLIENT_CERTIFICATE_LABELS
            );
            LOG.info("[webhook={}] ensured client mTLS Secret {}/{}", webhookName, ns, clientSec);
        } catch (Exception e) {
            LOG.error("[webhook={}] failed creating client mTLS Secret {}/{}", webhookName, ns, clientSec, e);
            throw e;
        }

        // ---- 5) Issue serving TLS cert for the Service DNS names and store as Secret ----
        //         Kubernetes API server connects over these names; SANs must match.
        final String svcShort = appName + "." + ns + ".svc";
        final String svcFqdn  = svcShort + (dnsSuffix.startsWith(".") ? dnsSuffix : "." + dnsSuffix);
        try {
            ServerCert sc = issueServerCert(ca, List.of(svcShort, svcFqdn), 365);
            createOrReplaceTlsSecret(k8sSvc.getClient(), ns, serveSec, sc.certPem(), sc.keyPem());
            LOG.info("[webhook={}] ensured serving TLS Secret {}/{} (SANs: {}, {})",
                    webhookName, ns, serveSec, svcShort, svcFqdn);
        } catch (Exception e) {
            LOG.error("[webhook={}] failed creating serving TLS Secret {}/{}", webhookName, ns, serveSec, e);
            throw e;
        }

        // ---- 6) Compute caBundle (base64 DER of Ambari CA) and persist for Helm passthrough ----
        try {
            String caBundle = toBase64Der(ca.caCertificatePem());
            String key = "k8s.view.webhooks." + webhookName + ".webhook.caBundle";
            // Persist to the instance settings shared by every user (safe to overwrite). Your Helm flow will read
            // this back as an override.
            new InstanceSettings(ctx).put(key, caBundle);
            LOG.info("[webhook={}] stored caBundle into view setting key='{}' ({} bytes)", webhookName, key,
                    (caBundle != null ? caBundle.length() : 0));
        } catch (Exception e) {
            LOG.error("[webhook={}] failed computing/persisting caBundle", webhookName, e);
            throw e;
        }

        // ----- 7) load ambari'server CA from JAVA based keystore
        final String truststorePath = ctx.getAmbariProperty("ssl.trustStore.path");
        boolean truststoreExists = (truststorePath != null && !truststorePath.isBlank());
        if (truststoreExists) {
            try {
                final String truststoreType = Optional.ofNullable(ctx.getAmbariProperty("ssl.trustStore.type"))
                        .filter(s -> !s.isBlank()).orElse("JKS");

                // Read property (could be plain or ${alias=...})
                final String truststorePassProp = Optional.ofNullable(ctx.getAmbariProperty("ssl.trustStore.password"))
                        .orElse("");

                char[] truststorePass = ambariAliasResolver.resolve(ctx, truststorePassProp);
                webHookConfigurationService.ensureOrRotateAmbariTruststoreCaSecret(
                        ns,
                        secretName,
                        truststorePath,
                        truststoreType,
                        truststorePass,
                        minDaysLeft,
                        Map.of("purpose", "backend-ca-external")
                );

            } catch (Exception e) {
                LOG.error("[webhook={}] failed creating ambari truststore CA Secret {}/{}", webhookName, ns, secretName, e);
                throw e;
            }
        } else {
            LOG.warn("[webhook={}] ambari truststore path not configured; skipping truststore CA Secret creation", webhookName);
        }
        // ---- 8) The webhook's Ambari account: Ambari signs every API call in, so the webhook needs one to reach
        //         the view. It may only open this view instance; the view then checks the webhook's certificate
        //         on each keytab request and issues keytabs with the credentials of the release's deploy.
        try {
            String password = KeytabWebhookAuthenticator.accountPassword(
                    webHookConfigurationService.readClientPrivateKeyPem(ns, clientSec));
            var ambariActionClient = new AmbariActionClient(ctx, AmbariLoopbackUrlResolver.resolveApiBase(ctx),
                    AmbariActionClient.toAuthHeaders(callerHeaders));
            ensureWebhookAccount(ctx, ambariActionClient, ambariAliasResolver, webhookUserName, password);
            removeLegacyCredentials(ctx, k8sSvc, ambariAliasResolver, webhookName);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        LOG.info("[webhook={}] prerequisite preparation complete in {} ms", webhookName, (System.currentTimeMillis() - t0));
    }

    /**
     * The Ambari account a webhook signs in with when it calls the view back. It is the account this bootstrap
     * creates; the webhook chart gets its name as {@value #ACCOUNT_USERNAME_VALUE}. Each view instance has its own:
     * its password is derived from the instance's own webhook client key, and it may only open that instance, so view
     * instances that manage different Kubernetes clusters do not change or remove each other's account.
     *
     * @param ctx         Ambari ViewContext
     * @param webhookName logical name of the webhook, e.g. {@link #KEYTAB_WEBHOOK}
     * @return the {@code k8s.view.webhooks.<webhookName>.backend.webhookUsername} setting ({@code webhook-user} by
     *         default) followed by {@code -<instance>}, in lower case as Ambari keeps user names
     */
    public static String webhookUserName(ViewContext ctx, String webhookName) {
        String base = prop(ctx, webhookName, "backend.webhookUsername", "webhook-user");
        String instance = instanceKey(ctx);
        return (instance == null ? base : base + "-" + instance).toLowerCase(Locale.ROOT);
    }

    /**
     * The credential store alias that records the webhook account of this view instance.
     *
     * @param ctx the view instance
     * @return {@value #USER_ALIAS}{@code .<instance>}, in lower case as the credential store keeps aliases
     */
    static String accountAlias(ViewContext ctx) {
        String instance = instanceKey(ctx);
        return instance == null ? USER_ALIAS : USER_ALIAS + "." + instance;
    }

    /** The view instance name, in lower case and limited to the characters user names and aliases safely hold. */
    private static String instanceKey(ViewContext ctx) {
        String instance = ctx.getInstanceName();
        if (instance == null || instance.isBlank()) {
            return null;
        }
        return instance.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
    }

    /**
     * Makes the webhook's Ambari account what the webhook needs and no more: a local user with the password derived
     * from its client key, allowed to open this view instance only. The account earlier versions shared between all
     * the view instances, an Ambari administrator, is deleted: no webhook of this version signs in with it. When Ambari
     * refuses, that is logged and the account is deleted at the next setup or settings save.
     *
     * @param ctx       the view instance the account may open
     * @param ambari    Ambari, with the credentials of an Ambari administrator
     * @param aliases   the credential store, which records the account the view created
     * @param user      the account, {@link #webhookUserName} of this instance
     * @param password  the password derived from the webhook's client key
     */
    static void ensureWebhookAccount(ViewContext ctx, AmbariActionClient ambari, AmbariAliasResolver aliases,
                                     String user, String password) throws Exception {
        ambari.createWebhookAmbariUser(user, password);
        if (ambari.revokeAmbariAdministrator(user)) {
            LOG.info("The keytab webhook's Ambari account '{}' is no longer an Ambari administrator", user);
        }
        ambari.grantViewInstanceUser(ctx.getViewName(), ctx.getViewDefinition().getVersion(), ctx.getInstanceName(),
                user);
        String alias = accountAlias(ctx);
        Set<String> existing = aliases.listAliases();
        if (!existing.contains(alias) || !user.equals(new String(aliases.getPasswordForAlias(alias)))) {
            aliases.addAliasToCredentialStore(alias, user);
        }
        removeSharedLegacyAccount(ctx, ambari, aliases, user);
    }

    /**
     * Deletes the Ambari account earlier versions created for the webhooks of every view instance, an Ambari
     * administrator, when the credential store records it. Webhooks of this version sign in with an account of their
     * own instance, so none of them uses it. The record is only dropped once Ambari deleted the account or said it is
     * gone: dropped earlier, nothing would ever remove that administrator account.
     *
     * @param keep the account of the current instance, never deleted here
     * @return a warning for the administrator when the account could not be deleted, else null
     */
    private static String removeSharedLegacyAccount(ViewContext ctx, AmbariActionClient ambari,
                                                    AmbariAliasResolver aliases, String keep) throws Exception {
        if (USER_ALIAS.equals(accountAlias(ctx)) || !aliases.listAliases().contains(USER_ALIAS)) {
            return null;
        }
        String legacy = new String(aliases.getPasswordForAlias(USER_ALIAS));
        if (!legacy.equalsIgnoreCase(keep)) {
            try {
                if (ambari.deleteUser(legacy)) {
                    LOG.warn("Deleted the Ambari account '{}' that earlier versions created, as an Ambari "
                            + "administrator, for the keytab webhooks of every view instance: see the release notes.",
                            legacy);
                }
            } catch (Exception e) {
                LOG.warn("Could not delete the Ambari account '{}' that earlier versions created, as an Ambari "
                        + "administrator, for the keytab webhooks: {}", legacy, e.toString());
                return "KDPS could not delete the Ambari account '" + legacy + "' that earlier versions created, as an "
                        + "Ambari Administrator, for the Kerberos keytab webhook (" + e.getMessage() + "). Ask an "
                        + "Ambari administrator to save these settings, or to delete that account in Ambari (Manage "
                        + "Ambari > Users).";
            }
        }
        aliases.removeAlias(USER_ALIAS);
        return null;
    }

    /** The warnings that are not null, one after the other; null when there are none. */
    private static String warnings(String... warnings) {
        StringBuilder joined = new StringBuilder();
        for (String warning : warnings) {
            if (warning != null) {
                joined.append(joined.length() == 0 ? "" : " ").append(warning);
            }
        }
        return joined.length() == 0 ? null : joined.toString();
    }

    /**
     * Removes what earlier versions kept for the webhook's sign-in: the Secret holding the account's password in the
     * webhook's namespace, then the password in Ambari's credential store, which marks that the Secret is still to be
     * removed. The webhook now derives its password from its client key. Best effort: a failure is logged.
     */
    private static void removeLegacyCredentials(ViewContext ctx, KubernetesService k8sSvc, AmbariAliasResolver aliases,
                                                String webhookName) {
        try {
            if (aliases.listAliases().contains(PASS_ALIAS) && removeLegacyCredentialsSecret(ctx, k8sSvc, webhookName)) {
                aliases.removeAlias(PASS_ALIAS);
            }
        } catch (Exception e) {
            LOG.warn("Could not remove the credential alias {}: {}", PASS_ALIAS, e.toString());
        }
    }

    /**
     * Removes, when the view starts, the Secret in which an earlier version kept the password of the keytab webhook's
     * Ambari account, an Ambari administrator. Only done where that version ran the WEBHOOK injection mode (its
     * password is in Ambari's credential store). The view cannot change Ambari accounts without an administrator's
     * credentials: the account itself is deleted at the next WEBHOOK-mode deploy, or when the injection mode is saved as
     * something else. Best effort.
     *
     * @param ctx    the view instance
     * @param k8sSvc the view's Kubernetes access; nothing is done when the view has no cluster configured
     */
    public static void removeLegacyCredentialsAtStart(ViewContext ctx, KubernetesService k8sSvc) {
        try {
            removeLegacyCredentials(ctx, k8sSvc, new AmbariAliasResolver(ctx), KEYTAB_WEBHOOK);
        } catch (Exception e) {
            LOG.debug("Could not look for the keytab webhook's earlier credentials: {}", e.toString());
        }
    }

    /**
     * Renews the keytab webhook's client certificate when it nears expiry, without waiting for a deploy; the view runs
     * this when it starts and then daily. The webhook proves every keytab request with that certificate: once it
     * expires the view refuses those requests, and the webhook then refuses every new Kerberized pod (a restart, a
     * scale-out, a drained node). Only done for a view instance that set up the webhook's account (WEBHOOK mode). The
     * new certificate is issued for the same key, so the account's password, derived from that key, does not change
     * and no Ambari administrator is needed: the view's own Kubernetes access and internal CA suffice. Best effort.
     *
     * @param ctx    the view instance
     * @param k8sSvc the view's Kubernetes access; nothing is done when it is null
     * @return true when a new certificate was written
     */
    public static boolean renewClientCertificateIfDue(ViewContext ctx, KubernetesService k8sSvc) {
        try {
            if (k8sSvc == null || WebHookConfigurationService.certificateAuthorityPem(ctx) == null
                    || !new AmbariAliasResolver(ctx).listAliases().contains(accountAlias(ctx))) {
                return false;
            }
        } catch (Exception e) {
            LOG.debug("Could not tell whether the keytab webhook is set up: {}", e.toString());
            return false;
        }
        return renewClientCertificate(ctx, new WebHookConfigurationService(ctx, k8sSvc));
    }

    /**
     * Renews the keytab webhook's client certificate in its Secret when it expires within
     * {@value #CLIENT_CERTIFICATE_MIN_DAYS_LEFT} days or was issued by an earlier CA; a missing Secret is left missing.
     *
     * @return true when a new certificate was written
     */
    static boolean renewClientCertificate(ViewContext ctx, WebHookConfigurationService service) {
        String ns = prop(ctx, KEYTAB_WEBHOOK, "namespace", "ambari-mutating-webhooks");
        String clientSec = prop(ctx, KEYTAB_WEBHOOK, "clientMtls.secretName", KEYTAB_WEBHOOK + "-client-tls");
        try {
            boolean renewed = service.renewClientMtlsSecretIfPresent(ns, clientSec, CLIENT_CERTIFICATE_CN,
                    CLIENT_CERTIFICATE_VALIDITY_DAYS, CLIENT_CERTIFICATE_MIN_DAYS_LEFT, CLIENT_CERTIFICATE_LABELS);
            if (renewed) {
                LOG.info("Renewed the keytab webhook's client certificate in Secret {}/{}", ns, clientSec);
            }
            return renewed;
        } catch (Exception e) {
            Instant notAfter = null;
            try {
                notAfter = service.clientCertificateNotAfter(ns, clientSec);
            } catch (Exception ignored) {
                // The renewal failure below says enough.
            }
            String message = "Could not renew the keytab webhook's client certificate in Secret " + ns + "/" + clientSec
                    + (notAfter == null ? "" : ", which expires at " + notAfter) + ": " + e
                    + ". Once it expires, KDPS refuses the webhook's keytab requests and the Kubernetes cluster refuses "
                    + "every new Kerberized pod. Redeploy a release in Webhook mode to renew it.";
            if (notAfter != null && notAfter.isBefore(Instant.now().plus(CLIENT_CERTIFICATE_ALERT_DAYS, ChronoUnit.DAYS))) {
                LOG.error(message);
            } else {
                LOG.warn(message);
            }
            return false;
        }
    }

    /**
     * Deletes the webhook password Secret earlier versions wrote.
     *
     * @return true when the Secret is gone (deleted now, or absent)
     */
    private static boolean removeLegacyCredentialsSecret(ViewContext ctx, KubernetesService k8sSvc,
                                                         String webhookName) {
        if (k8sSvc == null || k8sSvc.getClient() == null) {
            return false;
        }
        String ns = prop(ctx, webhookName, "namespace", "ambari-mutating-webhooks");
        String secret = prop(ctx, webhookName, "backend.webhookUserSecret", LEGACY_CREDENTIALS_SECRET);
        try {
            if (new WebHookConfigurationService(ctx, k8sSvc).deleteSecretIfPresent(ns, secret)) {
                LOG.warn("Deleted Secret {}/{}, which held the password of the keytab webhook's Ambari account. "
                        + "Earlier versions made that account an Ambari administrator: see the release notes.", ns,
                        secret);
            }
            return true;
        } catch (Exception e) {
            LOG.warn("Could not delete the webhook password Secret {}/{} earlier versions wrote; delete it by hand: "
                    + "{}", ns, secret, e.toString());
            return false;
        }
    }

    /**
     * Withdraws the keytab webhook's access when the WEBHOOK injection mode is not in use: the password Secret of
     * earlier versions, the account earlier versions shared between all the view instances, and the Ambari account
     * this view instance created for its webhook (only the one the credential store records for this instance). The
     * account of this instance is kept while the keytab webhook is still installed in the Kubernetes cluster, or when
     * the view cannot tell: the pods of releases deployed in WEBHOOK mode still go through the webhook when they are
     * created, and without its account the webhook refuses them. That account may only open this view instance. Best
     * effort: a failure is logged, never thrown.
     *
     * @param ctx           the view instance
     * @param k8sSvc        the view's Kubernetes access; may be null
     * @param callerHeaders the request of an Ambari administrator, whose credentials remove the accounts
     * @return a warning for the administrator when an account was kept (the webhook is still installed, or Ambari
     *         refused to delete it), else null
     */
    public static String retireWebhookAccess(ViewContext ctx, KubernetesService k8sSvc,
                                             MultivaluedMap<String, String> callerHeaders) {
        try {
            AmbariAliasResolver aliases = new AmbariAliasResolver(ctx);
            removeLegacyCredentials(ctx, k8sSvc, aliases, KEYTAB_WEBHOOK);
            Set<String> recorded = aliases.listAliases();
            if (!recorded.contains(accountAlias(ctx)) && !recorded.contains(USER_ALIAS)) {
                return null;
            }
            AmbariActionClient ambari = new AmbariActionClient(ctx, AmbariLoopbackUrlResolver.resolveApiBase(ctx),
                    AmbariActionClient.toAuthHeaders(callerHeaders));
            return retireWebhookAccount(ctx, ambari, aliases, keytabWebhookInstalled(ctx, k8sSvc));
        } catch (Exception e) {
            LOG.warn("Could not remove the keytab webhook's Ambari account; remove it in Ambari (Users): {}",
                    e.toString());
            return null;
        }
    }

    /**
     * Removes the Ambari accounts of {@link #retireWebhookAccess}: the one earlier versions shared, and the one of
     * this view instance unless its webhook may still be installed.
     *
     * @param webhookInstalled whether the keytab webhook is still installed in the Kubernetes cluster; null when the
     *                         view cannot tell
     * @return a warning for the administrator when an account was kept, else null. An account is only forgotten once
     *         Ambari deleted it or said it does not exist.
     */
    static String retireWebhookAccount(ViewContext ctx, AmbariActionClient ambari, AmbariAliasResolver aliases,
                                       Boolean webhookInstalled) throws Exception {
        String alias = accountAlias(ctx);
        String own = aliases.listAliases().contains(alias) ? new String(aliases.getPasswordForAlias(alias)) : null;
        String legacyWarning = removeSharedLegacyAccount(ctx, ambari, aliases, own);
        if (own == null) {
            return legacyWarning;
        }
        if (!Boolean.FALSE.equals(webhookInstalled)) {
            String why = webhookInstalled == null
                    ? "KDPS could not check whether the Kerberos keytab webhook is still installed in the Kubernetes "
                      + "cluster, so its Ambari account '" + own + "' was kept."
                    : "The Kerberos keytab webhook is still installed in the Kubernetes cluster, so its Ambari account '"
                      + own + "' was kept.";
            LOG.warn("Kerberos injection mode is not WEBHOOK, but the keytab webhook's Ambari account '{}' is kept: {}",
                    own, webhookInstalled == null ? "the webhook may still be installed" : "the webhook is installed");
            return warnings(legacyWarning, why + " Without it, the webhook refuses the pods of releases deployed in "
                    + "Webhook mode when they restart or scale. Redeploy those releases, uninstall the keytab webhook, "
                    + "then save the settings again to remove the account. The account may only open this view "
                    + "instance.");
        }
        try {
            ambari.deleteUser(own);
        } catch (Exception e) {
            // The record is kept, so that saving the settings again still removes the account.
            LOG.warn("Could not remove the keytab webhook's Ambari account '{}': {}", own, e.toString());
            return warnings(legacyWarning, "KDPS could not remove the Kerberos keytab webhook's Ambari account '" + own
                    + "' (" + e.getMessage() + "). Ask an Ambari administrator to save these settings, or to delete "
                    + "that account in Ambari (Manage Ambari > Users). The account may only open this view instance.");
        }
        aliases.removeAlias(alias);
        LOG.info("Kerberos injection mode is not WEBHOOK: removed the keytab webhook's Ambari account '{}'", own);
        return legacyWarning;
    }

    /**
     * Whether the keytab webhook is installed in the Kubernetes cluster: a mutating admission webhook served from its
     * namespace is registered.
     *
     * @return true or false; null when the view has no cluster or cannot list the admission webhooks
     */
    static Boolean keytabWebhookInstalled(ViewContext ctx, KubernetesService k8sSvc) {
        String ns = prop(ctx, KEYTAB_WEBHOOK, "namespace", "ambari-mutating-webhooks");
        try {
            KubernetesClient client = k8sSvc == null ? null : k8sSvc.getClient();
            if (client == null) {
                return null;
            }
            for (MutatingWebhookConfiguration configuration
                    : client.admissionRegistration().v1().mutatingWebhookConfigurations().list().getItems()) {
                for (MutatingWebhook webhook : Optional.ofNullable(configuration.getWebhooks()).orElse(List.of())) {
                    if (webhook.getClientConfig() != null && webhook.getClientConfig().getService() != null
                            && ns.equals(webhook.getClientConfig().getService().getNamespace())) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            LOG.info("Could not list the mutating admission webhooks of the cluster: {}", e.toString());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /** Read a passthrough property with default: k8s.view.webhooks.<webhook>.<key> */
    private static String prop(ViewContext ctx, String webhookName, String key, String def) {
        String fullKey = "k8s.view.webhooks." + webhookName + "." + key;
        String val = null;
        try {
            val = ctx.getAmbariProperty(fullKey);
        } catch (Exception ignored) {
            // Some ViewContext impls may not throw; keep it defensive.
        }
        if (val != null && !val.isBlank()) return val.trim();
        return def;
    }

    /** Create or replace Kubernetes TLS secret with PEM data (keys: tls.crt, tls.key) */
    private static void createOrReplaceTlsSecret(KubernetesClient client, String ns, String name, String certPem, String keyPem) {
        Secret desired = new SecretBuilder()
                .withNewMetadata().withName(name).withNamespace(ns)
                .addToLabels("managed-by", "ambari-k8s-view")
                .addToLabels("component", "webhook-serving-tls")
                .endMetadata()
                .withType("kubernetes.io/tls")
                .addToData("tls.crt", Base64.getEncoder().encodeToString(certPem.getBytes(StandardCharsets.UTF_8)))
                .addToData("tls.key", Base64.getEncoder().encodeToString(keyPem.getBytes(StandardCharsets.UTF_8)))
                .build();

        Secret existing = client.secrets().inNamespace(ns).withName(name).get();
        if (existing == null) {
            client.secrets().inNamespace(ns).resource(desired).create();
            LOG.debug("Created serving TLS Secret {}/{}", ns, name);
        } else {
            client.secrets().inNamespace(ns).resource(desired).createOrReplace();
            LOG.debug("Updated serving TLS Secret {}/{}", ns, name);
        }
    }

    /** Simple holder for generated server certificate material. */
    private record ServerCert(String certPem, String keyPem) {}

    /** Issue a server certificate signed by the Ambari CA for the given DNS SANs. */
    private static ServerCert issueServerCert(WebHookConfigurationService.CertificateAuthorityMaterial ca,
                                              List<String> dnsSans,
                                              int validityDays) {
        try {
            KeyPair kp = generateEcKey();
            X509Certificate caCert = parseCert(ca.caCertificatePem());

            // Reuse your existing PEM parser if accessible; else local PKCS#8 EC parser
            PrivateKey caKey;
            try {
                var m = WebHookConfigurationService.class.getDeclaredMethod("parsePrivateKeyFromPem", String.class);
                m.setAccessible(true);
                caKey = (PrivateKey) m.invoke(null, ca.caPrivateKeyPem());
            } catch (Throwable t) {
                caKey = parsePrivateKeyPkcs8Ec(ca.caPrivateKeyPem());
            }

            X500Name issuer = new X500Name(caCert.getSubjectX500Principal().getName());
            String cn = dnsSans.isEmpty() ? "webhook.svc" : dnsSans.get(0);
            X500Name subject = new X500Name(new X500Principal("CN=" + cn).getName());
            Instant notBefore = Instant.now().minus(1, ChronoUnit.DAYS);
            Instant notAfter  = notBefore.plus(validityDays, ChronoUnit.DAYS);
            BigInteger serial = new BigInteger(64, new SecureRandom());

            JcaX509ExtensionUtils ext = new JcaX509ExtensionUtils();
            X509v3CertificateBuilder b = new X509v3CertificateBuilder(
                    issuer, serial, Date.from(notBefore), Date.from(notAfter), subject,
                    SubjectPublicKeyInfo.getInstance(kp.getPublic().getEncoded()));

            // serverAuth extensions
            b.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
            b.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
            b.addExtension(Extension.subjectKeyIdentifier, false, ext.createSubjectKeyIdentifier(kp.getPublic()));

            if (!dnsSans.isEmpty()) {
                List<GeneralName> san = new ArrayList<>();
                for (String dns : dnsSans) if (dns != null && !dns.isBlank())
                    san.add(new GeneralName(GeneralName.dNSName, dns));
                b.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(san.toArray(new GeneralName[0])));
            }

            ContentSigner signer = new JcaContentSignerBuilder("SHA256withECDSA").setProvider(BC).build(caKey);
            X509CertificateHolder holder = b.build(signer);
            X509Certificate cert = new JcaX509CertificateConverter().setProvider(BC).getCertificate(holder);

            return new ServerCert(toPemCert(cert), toPemKey(kp.getPrivate()));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to issue server TLS cert", e);
        }
    }

    private static KeyPair generateEcKey() throws GeneralSecurityException {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(256);
        return gen.generateKeyPair();
    }

    private static X509Certificate parseCert(String pem) {
        try {
            String normalized = pem.replace("-----BEGIN CERTIFICATE-----", "")
                    .replace("-----END CERTIFICATE-----", "").replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(normalized);
            return (X509Certificate) java.security.cert.CertificateFactory
                    .getInstance("X.509").generateCertificate(new java.io.ByteArrayInputStream(der));
        } catch (Exception e) {
            throw new IllegalStateException("Invalid certificate PEM", e);
        }
    }

    private static PrivateKey parsePrivateKeyPkcs8Ec(String pem) {
        try {
            String normalized = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(normalized);
            return KeyFactory.getInstance("EC").generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("Invalid EC private key PEM", e);
        }
    }

    private static String toPemCert(X509Certificate cert) {
        try {
            String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                    .encodeToString(cert.getEncoded());
            return "-----BEGIN CERTIFICATE-----\n" + base64 + "\n-----END CERTIFICATE-----\n";
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static String toPemKey(PrivateKey key) {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(key.getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n";
    }

    private static String toBase64Der(String caCertPem) {
        try {
            X509Certificate ca = parseCert(caCertPem);
            return Base64.getEncoder().encodeToString(ca.getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute caBundle", e);
        }
    }


}
