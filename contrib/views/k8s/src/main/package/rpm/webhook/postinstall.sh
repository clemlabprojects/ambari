#!/usr/bin/env bash
set -euo pipefail
echo "Installed ${RPM_PACKAGE_NAME:-clemlab-kerberos-keytab-webhook}."
echo "Image: $(cat /usr/lib/clemlab-kerberos-keytab-webhook/IMAGE.txt || true)"
