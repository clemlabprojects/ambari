/*
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
 *
 * s4u2self — Kerberos S4U2Self impersonation tool for JupyterHub KDPS sidecar.
 *
 * Usage:  s4u2self <IMPERSONATOR> <TARGET_USER> <KEYTAB> <OUTPUT_CCACHE>
 *
 * Why C and not Python:
 *   - python-gssapi.Credentials.impersonate() calls krb5_get_credentials_for_user
 *     with options=0, producing a non-forwardable S4U2Self ticket. MIT KDC then
 *     refuses S4U2Proxy with "EVIDENCE_TKT_NOT_FORWARDABLE".
 *   - python-krb5 (pykrb5) does not expose krb5_get_credentials_for_user in the
 *     0.9.x public API.
 *   - This program calls the libkrb5 C API directly with explicit
 *     KRB5_GC_FORWARDABLE, getting a usable evidence ticket for downstream
 *     S4U2Proxy calls.
 *
 * Output ccache layout (so curl --negotiate as the impersonated user "just works"):
 *   Default principal: <TARGET_USER>@REALM
 *   Config entry:
 *     - proxy_impersonator = <IMPERSONATOR>@REALM  (tells krb5_get_credentials()
 *       to auto-trigger S4U2Proxy when the caller asks for a service ticket;
 *       MIT krb5 >= 1.13, see src/lib/krb5/krb/get_creds.c::s4u_identify_user)
 *   Tickets:
 *     - krbtgt/REALM@REALM  for client <IMPERSONATOR>@REALM  (FORWARDABLE)
 *     - <IMPERSONATOR>@REALM  with client=<TARGET_USER>      (S4U2Self,
 *                                                             FORWARDABLE)
 *
 * Without the proxy_impersonator config, curl-gssapi's krb5_get_credentials()
 * looks for a TGT matching (client=<TARGET_USER>, server=krbtgt) and fails with
 * KRB5_CC_NOTFOUND ("Matching credential not found") because only the impersonator
 * has a TGT in the ccache. The config flips on automatic S4U2Proxy.
 */

#include <krb5.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static void die(krb5_context ctx, krb5_error_code rc, const char *what) {
    const char *msg = (ctx && rc) ? krb5_get_error_message(ctx, rc) : "no context";
    fprintf(stderr, "s4u2self: %s: %s (%d)\n", what, msg, rc);
    if (ctx && rc) krb5_free_error_message(ctx, msg);
    exit(rc ? (rc & 0xff ? rc & 0xff : 1) : 1);
}

int main(int argc, char **argv) {
    if (argc != 5) {
        fprintf(stderr,
            "usage: %s IMPERSONATOR TARGET_USER KEYTAB OUTPUT_CCACHE\n"
            "  IMPERSONATOR    e.g. jupyterhub/host.example.com@REALM\n"
            "  TARGET_USER     e.g. alice@REALM\n"
            "  KEYTAB          path to keytab file containing impersonator key\n"
            "  OUTPUT_CCACHE   path for the resulting ccache (no FILE: prefix needed)\n",
            argv[0]);
        return 2;
    }
    const char *impersonator_str = argv[1];
    const char *target_str       = argv[2];
    const char *keytab_path      = argv[3];
    const char *output_path      = argv[4];

    krb5_context ctx = NULL;
    krb5_error_code rc;

    rc = krb5_init_context(&ctx);
    if (rc) die(NULL, rc, "krb5_init_context");

    krb5_principal impersonator_princ = NULL, target_princ = NULL;
    rc = krb5_parse_name(ctx, impersonator_str, &impersonator_princ);
    if (rc) die(ctx, rc, "krb5_parse_name(impersonator)");
    rc = krb5_parse_name(ctx, target_str, &target_princ);
    if (rc) die(ctx, rc, "krb5_parse_name(target)");

    krb5_keytab kt = NULL;
    rc = krb5_kt_resolve(ctx, keytab_path, &kt);
    if (rc) die(ctx, rc, "krb5_kt_resolve");

    /* Step 1: kinit-equivalent for the impersonator, forwardable + proxiable. */
    krb5_get_init_creds_opt *opts = NULL;
    rc = krb5_get_init_creds_opt_alloc(ctx, &opts);
    if (rc) die(ctx, rc, "krb5_get_init_creds_opt_alloc");
    krb5_get_init_creds_opt_set_forwardable(opts, 1);
    krb5_get_init_creds_opt_set_proxiable(opts, 1);

    krb5_creds tgt_creds;
    memset(&tgt_creds, 0, sizeof(tgt_creds));
    rc = krb5_get_init_creds_keytab(
        ctx, &tgt_creds, impersonator_princ, kt,
        /*start_time=*/0, /*in_tkt_service=*/NULL, opts);
    if (rc) die(ctx, rc, "krb5_get_init_creds_keytab (kinit-equivalent)");

    /* We need a working ccache to pass to krb5_get_credentials_for_user; use a
     * MEMORY ccache so we don't touch disk. Initialize it with the TGT. */
    krb5_ccache mem_cc = NULL;
    rc = krb5_cc_new_unique(ctx, "MEMORY", NULL, &mem_cc);
    if (rc) die(ctx, rc, "krb5_cc_new_unique(MEMORY)");
    rc = krb5_cc_initialize(ctx, mem_cc, impersonator_princ);
    if (rc) die(ctx, rc, "krb5_cc_initialize(MEMORY) with impersonator");
    rc = krb5_cc_store_cred(ctx, mem_cc, &tgt_creds);
    if (rc) die(ctx, rc, "krb5_cc_store_cred(TGT into MEMORY)");

    /* Step 2: S4U2Self with explicit KRB5_GC_FORWARDABLE so the resulting
     * evidence ticket has the FORWARDABLE flag set — required by MIT KDC for
     * downstream S4U2Proxy. */
    krb5_creds in_creds;
    memset(&in_creds, 0, sizeof(in_creds));
    in_creds.client = target_princ;          /* impersonated user */
    in_creds.server = impersonator_princ;    /* service-for-user target = impersonator */

    krb5_creds *s4u_creds = NULL;
    rc = krb5_get_credentials_for_user(
        ctx,
        KRB5_GC_FORWARDABLE,
        mem_cc,
        &in_creds,
        /*subject_cert=*/NULL,
        &s4u_creds);
    if (rc) die(ctx, rc, "krb5_get_credentials_for_user (S4U2Self)");

    /* Step 3: write the output ccache with target_user as default principal,
     * containing both the impersonator's TGT (auth credential) and the S4U2Self
     * ticket (evidence). This is the layout the krb5 client library expects to
     * auto-do S4U2Proxy when the caller asks for a service ticket. */
    char out_with_prefix[512];
    snprintf(out_with_prefix, sizeof(out_with_prefix), "FILE:%s", output_path);
    krb5_ccache out_cc = NULL;
    rc = krb5_cc_resolve(ctx, out_with_prefix, &out_cc);
    if (rc) die(ctx, rc, "krb5_cc_resolve(output)");
    rc = krb5_cc_initialize(ctx, out_cc, target_princ);
    if (rc) die(ctx, rc, "krb5_cc_initialize(output) with target_user");

    /* Critical: mark this ccache as a proxy-impersonator ccache so MIT krb5's
     * krb5_get_credentials() will auto-S4U2Proxy when the caller requests a
     * service ticket. Without this, curl-gssapi gets KRB5_CC_NOTFOUND because
     * the ccache has no TGT for <TARGET_USER> (we can't have one — we don't have
     * the user's keytab; that's the whole point of S4U). */
    /* "proxy_impersonator" is the documented key, MIT internal-header constant
     * KRB5_CC_CONF_PROXY_IMPERSONATOR. Use the string literal so we don't depend
     * on k5-int.h being available at build time. */
    krb5_data imp_data;
    imp_data.magic  = KV5M_DATA;
    imp_data.data   = (char *)impersonator_str;
    imp_data.length = (unsigned int)strlen(impersonator_str);
    rc = krb5_cc_set_config(ctx, out_cc, /*principal=*/NULL,
                            "proxy_impersonator", &imp_data);
    if (rc) die(ctx, rc, "krb5_cc_set_config(proxy_impersonator)");

    /* Store the impersonator's TGT first, then the S4U2Self ticket. Order matters
     * for some krb5 client lookups that pick the first match. */
    rc = krb5_cc_store_cred(ctx, out_cc, &tgt_creds);
    if (rc) die(ctx, rc, "krb5_cc_store_cred(TGT into output)");
    rc = krb5_cc_store_cred(ctx, out_cc, s4u_creds);
    if (rc) die(ctx, rc, "krb5_cc_store_cred(S4U2Self into output)");

    printf("S4U2Self ok: wrote impersonated+forwardable ticket for %s via %s to %s\n",
           target_str, impersonator_str, output_path);

    /* Cleanup. */
    krb5_cc_close(ctx, out_cc);
    krb5_free_creds(ctx, s4u_creds);
    krb5_cc_close(ctx, mem_cc);
    krb5_free_cred_contents(ctx, &tgt_creds);
    krb5_get_init_creds_opt_free(ctx, opts);
    krb5_kt_close(ctx, kt);
    krb5_free_principal(ctx, target_princ);
    krb5_free_principal(ctx, impersonator_princ);
    krb5_free_context(ctx);
    return 0;
}
