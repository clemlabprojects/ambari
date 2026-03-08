# OzoneResource (Ambari common)

`OzoneResource` is a reusable Ambari resource wrapper for Ozone CLI operations.

Files:
- `resource_management/libraries/resources/ozone_resource.py`
- `resource_management/libraries/providers/ozone_resource.py`

## Actions

### `action="run"`
Runs a custom Ozone CLI command:

```python
OzoneResource(
  "ozone-list-volumes",
  action="run",
  command="sh volume list",
  user="ozone",
  conf_dir="/etc/hadoop/conf",
  ozone_cmd="/usr/odp/current/ozone-client/bin/ozone",
  tries=3,
  try_sleep=2,
)
```

### `action="generate_s3_credentials"`
Generates or fetches S3 credentials:

- If `secret_key` is provided: `ozone s3 setsecret -u <access_id> -s <secret_key>` (`access_id` required)
- If `secret_key` is empty: `ozone s3 getsecret` (optionally `-u <access_id>` when provided)

```python
OzoneResource(
  "polaris-ozone-s3-secret",
  action="generate_s3_credentials",
  user="ozone",
  conf_dir="/etc/hadoop/conf",
  ozone_cmd="/usr/odp/current/ozone-client/bin/ozone",
  access_id="ozone-engine",
  secret_key="my-static-secret",
  om_service_id="ozone1",
  security_enabled=True,
  kinit_path_local="/usr/bin/kinit",
  keytab="/etc/security/keytabs/ozone.service.keytab",
  principal_name="ozone/_HOST@EXAMPLE.COM",
)
```

## Retry behavior

`generate_s3_credentials` has built-in retry for transient OM/startup errors.

- If `tries` is `1` or not set, action default is:
  - `tries=5`
  - `try_sleep=5`
- If `tries` / `try_sleep` are explicitly set, those values are used.
- Retry is attempted only for known transient failures (for example connection refused, timeout, not leader, service not ready).
- Provider also validates CLI output and fails fast on known functional errors, including:
  - `operation works only when security is enabled`

## Kerberos behavior

When `security_enabled=true`, provider executes:

```bash
kinit -kt <keytab> <principal>; ozone ...
```

Required Kerberos fields:
- `kinit_path_local`
- `keytab`
- `principal_name`
