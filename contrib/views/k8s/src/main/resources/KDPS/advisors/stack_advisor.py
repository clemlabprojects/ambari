#!/usr/bin/env python3
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
"""KDPS service advisor (analogous to the Ambari stack advisor).

The KDPS view shells out to this script when an operator selects a platform
context in the service-install wizard. It reads a JSON request on **stdin** and
writes a JSON response on **stdout** — recommending, per service form field that
declares an ``advisor`` key, whether that field's enable/disable toggle should
default on or off, with a human-readable reason.

Why Python (while the view engine is Java): these rules are meant to be
**editable by operators/integrators without rebuilding the view jar**. Add or
change a ``recommend_*`` function below and reference it from a field's
``advisor`` key in that service's ``service.json``. A field whose advisor name
has no matching function here is simply left untouched (no recommendation), so a
service only gets advice for the capabilities it actually opts into — e.g. a
service whose ``service.json`` never references ``recommend_ranger`` gets no
Ranger advice at all.

Request (stdin)::

    {
      "service": "OPENMETADATA",
      "capabilities": {"atlas": true, "hive": true, "ranger": false,
                       "kerberos": true, "oidc": false, "trino": false},
      "fields": [
        {"name": "atlasFederation.enabled", "advisor": "recommend_atlas"},
        {"name": "baseIngestion.hiveEnabled", "advisor": "recommend_hive"}
      ]
    }

Response (stdout)::

    {"recommendations": [
      {"field": "atlasFederation.enabled", "recommend": true,
       "reason": "Atlas detected in the selected context."}
    ]}

SECURITY: this script is given only capability *presence* booleans and field
names. It is never given endpoints, usernames, passwords, or any secret, and it
must stay that way — the recommendation only depends on whether a capability
exists in the selected context, never on its credentials.
"""
import json
import sys


# --- Per-capability rules ---------------------------------------------------
# Each rule takes the detected-capability map and returns (recommend, reason).
# `recommend` is a bool (or None to abstain / leave the field untouched).

def recommend_atlas(capabilities):
    present = bool(capabilities.get("atlas"))
    if present:
        return True, "Atlas detected in the selected context — enable Atlas federation."
    return False, "No Atlas capability in the selected context — federation left off."


def recommend_hive(capabilities):
    present = bool(capabilities.get("hive"))
    if present:
        return True, "HiveServer2 detected in the selected context — enable base ingestion."
    return False, "No Hive capability in the selected context — base ingestion left off."


def recommend_ranger(capabilities):
    present = bool(capabilities.get("ranger"))
    if present:
        return True, "Ranger detected in the selected context — enable Ranger integration."
    return False, "No Ranger capability in the selected context — Ranger left off."


def recommend_trino(capabilities):
    present = bool(capabilities.get("trino"))
    if present:
        return True, "Trino detected in the selected context — enable the Trino connector."
    return False, "No Trino capability in the selected context — Trino connector left off."


# Registry of available advisor functions. A field's ``advisor`` value must match
# a key here; unknown names are ignored (the field keeps its static default).
ADVISORS = {
    "recommend_atlas": recommend_atlas,
    "recommend_hive": recommend_hive,
    "recommend_ranger": recommend_ranger,
    "recommend_trino": recommend_trino,
}


def advise(request):
    """Run each requested field's advisor rule and collect recommendations.

    A failure in one custom rule is isolated: it is reported as a non-fatal note
    and the remaining fields are still evaluated.
    """
    capabilities = request.get("capabilities") or {}
    recommendations = []
    for field in request.get("fields") or []:
        name = field.get("name")
        advisor_name = field.get("advisor")
        rule = ADVISORS.get(advisor_name)
        if not name or rule is None:
            continue
        try:
            recommend, reason = rule(capabilities)
        except Exception as exc:  # noqa: BLE001 - a buggy rule must not break the rest
            recommend, reason = None, "advisor '%s' failed: %s" % (advisor_name, exc)
        if recommend is None:
            continue
        recommendations.append(
            {"field": name, "recommend": bool(recommend), "reason": str(reason)}
        )
    return {"recommendations": recommendations}


def main():
    try:
        request = json.load(sys.stdin)
    except Exception as exc:  # noqa: BLE001 - malformed input must not crash the view
        json.dump({"recommendations": [], "error": "invalid request: %s" % exc}, sys.stdout)
        return
    json.dump(advise(request), sys.stdout)


if __name__ == "__main__":
    main()
