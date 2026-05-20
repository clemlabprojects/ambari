#!/usr/bin/env python3
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""
s4u2self.py — perform a Kerberos S4U2Self impersonation and write the resulting
credential cache to a file.

This is the heart of the C1 constrained-delegation design for KDPS JupyterHub:
the renewer sidecar holds the jupyterhub service keytab; it uses S4U2Self to
mint a TGT-equivalent ticket for the OIDC-authenticated user, drops it in a
shared volume, and the notebook container then accesses HDFS/Hive/etc as that
real user (with S4U2Proxy happening automatically per downstream service call).

The trick: from MIT Kerberos's perspective, S4U2Self looks like
  "I am principal X. Please give me a service ticket for myself, but in the
   name of user Y, because user Y has authenticated to me out-of-band."

The KDC will return the ticket only if:
  - X is marked ok-as-delegate (we did this in the ansible task), AND
  - There's a servicedelegationrule binding X to a target list (likewise).

python-gssapi exposes this via gssapi.Credentials(usage='impersonate', ...).
"""

import argparse
import os
import sys

import gssapi
from gssapi.raw.exceptions import GSSError


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--impersonator", required=True,
                    help="Service principal whose keytab is in KRB5CCNAME (e.g. jupyterhub/host@REALM)")
    ap.add_argument("--target-user", required=True,
                    help="User principal to impersonate (e.g. lbakalian@REALM)")
    ap.add_argument("--output", required=True,
                    help="Path to write the impersonated ccache (FILE:<path> implied)")
    args = ap.parse_args()

    src_cc = os.environ.get("KRB5CCNAME")
    if not src_cc:
        print("KRB5CCNAME must point to the impersonator's ccache", file=sys.stderr)
        return 2

    # Acquire impersonator credentials from the source ccache.
    try:
        impersonator_name = gssapi.Name(
            args.impersonator,
            name_type=gssapi.NameType.kerberos_principal,
        )
        impersonator_creds = gssapi.Credentials(
            name=impersonator_name,
            usage="initiate",
        )
    except GSSError as e:
        print(f"failed to load impersonator creds: {e}", file=sys.stderr)
        return 3

    # Perform S4U2Self for the target user.
    try:
        target_name = gssapi.Name(
            args.target_user,
            name_type=gssapi.NameType.kerberos_principal,
        )
        impersonated = gssapi.Credentials(
            name=target_name,
            impersonator=impersonator_creds,
            usage="initiate",
        )
    except GSSError as e:
        # The most common errors here:
        #   "KDC can't fulfill requested option" → ok-as-delegate not set on impersonator
        #   "KDC policy rejects request"        → no servicedelegationrule binding
        #   "Server not found in Kerberos database" → target user does not exist in FreeIPA
        print(f"S4U2Self failed for {args.target_user}: {e}", file=sys.stderr)
        return 4

    # Store the impersonated ticket in the output ccache.
    # python-gssapi's store() writes to KRB5CCNAME by default; we need to redirect.
    try:
        gssapi.raw.store_cred_into(
            {"ccache": f"FILE:{args.output}"},
            impersonated,
            usage="initiate",
            overwrite=True,
            set_default=True,
        )
    except GSSError as e:
        print(f"failed to write impersonated ccache to {args.output}: {e}", file=sys.stderr)
        return 5

    print(f"S4U2Self ok: wrote impersonated ticket for {args.target_user} to {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
