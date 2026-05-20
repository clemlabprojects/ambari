"""
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0
"""

import glob
import os

from resource_management.libraries.script.script import Script
from resource_management.core.resources.system import Directory, Execute, File
from resource_management.core.exceptions import Fail
from resource_management.core.logger import Logger
from resource_management.libraries.functions.check_process_status import check_process_status
from resource_management.libraries.functions.format import format


class ImpalaAtlasExtractor(Script):
    """Long-running daemon co-located with IMPALA_DAEMON that watches the impalad
    `lineage_event_log_dir` for new files and feeds them into
    `org.apache.atlas.impala.ImpalaLineageTool`, which publishes Atlas
    HookNotifications to Kafka.

    Mirrors Cloudera's atlas-impala-extractor service: there's no inline
    runtime hook in Impala (the coordinator is C++), so the bridge is a poll
    loop that processes lineage JSON files as impalad writes them. Latency is
    bounded by `--poll-seconds` (default 5s).

    This is a thin Python wrapper around a bash loop because we want POSIX
    signal-based stop semantics (PID file + SIGTERM) and don't want to keep a
    long-lived Python process pinned to the ambari-agent."""

    PID_FILE = "/var/run/impala/impala-atlas-extractor.pid"
    LOG_FILE = "/var/log/impala/impala-atlas-extractor.log"
    LAUNCHER = "/usr/odp/current/impala-daemon/sbin/impala-atlas-extractor"

    def install(self, env):
        self.install_packages(env)
        self.configure(env)

    def configure(self, env):
        import params
        env.set_params(params)

        Directory(
            "/var/run/impala",
            owner=params.impala_user,
            group=params.impala_group,
            create_parents=True,
        )
        Directory(
            params.impala_log_dir,
            owner=params.impala_user,
            group=params.impala_group,
            create_parents=True,
        )
        # The lineage log dir is shared between impalad (writer) and this
        # extractor (reader). impalad creates it on start when -lineage_event_log_dir
        # is set; we pre-create with the same perms so a START racing impalad's
        # first lineage emission doesn't see an empty/missing path.
        if params.impala_lineage_event_log_dir:
            Directory(
                params.impala_lineage_event_log_dir,
                owner=params.impala_user,
                group=params.impala_group,
                mode=0o755,
                create_parents=True,
            )

        # Render the launcher script. Kept as a separate file (rather than
        # inline `Execute`) so manual operator debug via
        # `sudo -u impala /usr/odp/current/impala-daemon/sbin/impala-atlas-extractor` works.
        Directory(
            os.path.dirname(self.LAUNCHER),
            owner="root",
            group="root",
            mode=0o755,
            create_parents=True,
        )
        File(
            self.LAUNCHER,
            content=self._launcher_content(params),
            owner="root",
            group="root",
            mode=0o755,
        )

    def start(self, env):
        import params
        env.set_params(params)
        self.configure(env)

        if not params.atlas_in_cluster:
            Logger.info(
                "Atlas is not in the cluster; IMPALA_ATLAS_EXTRACTOR is a no-op start. "
                "Component remains in STARTED state to satisfy Ambari but does not spawn a daemon."
            )
            return
        if not params.impala_lineage_event_log_dir:
            raise Fail(
                "IMPALA_ATLAS_EXTRACTOR requires impala-env/lineage_event_log_dir to be set. "
                "Atlas is in the cluster but lineage capture is disabled."
            )

        # nohup + setsid so the daemon survives the ambari-agent fork tree;
        # PID captured into our pidfile and trapped by `stop`/`status`.
        Execute(
            format(
                "nohup setsid {LAUNCHER} >/dev/null 2>&1 < /dev/null & "
                "echo $! > {PID_FILE}",
                LAUNCHER=self.LAUNCHER,
                PID_FILE=self.PID_FILE,
            ),
            user=params.impala_user,
            logoutput=True,
        )

    def stop(self, env):
        import params
        env.set_params(params)
        # SIGTERM to the bash launcher first; the loop traps and exits between
        # invocations of ImpalaLineageTool so we don't leave a half-published
        # batch of lineage messages.  Wait up to 30s, then SIGKILL.
        Execute(
            format(
                "if [ -f {PID_FILE} ]; then "
                "  pid=$(cat {PID_FILE}); "
                "  if kill -0 \"$pid\" 2>/dev/null; then "
                "    kill -TERM \"$pid\" || true; "
                "    for i in $(seq 1 30); do "
                "      kill -0 \"$pid\" 2>/dev/null || break; "
                "      sleep 1; "
                "    done; "
                "    kill -KILL \"$pid\" 2>/dev/null || true; "
                "  fi; "
                "  rm -f {PID_FILE}; "
                "fi",
                PID_FILE=self.PID_FILE,
            ),
            user=params.impala_user,
            logoutput=True,
        )

    def status(self, env):
        check_process_status(self.PID_FILE)

    # ------------------------------------------------------------------
    # internals
    # ------------------------------------------------------------------
    def _launcher_content(self, params):
        """The bash daemon that wraps ImpalaLineageTool in a poll loop.

        Classpath strategy: the atlas-impala-hook plugin dir provides the
        atlas-* jars + kafka client; impala-frontend supplies the
        QueryEventHook interface that ImpalaLineageTool extends; hadoop-client
        supplies SLF4J/log4j and Hadoop config. Glob expansion handles
        version-specific jar names so this script doesn't need an update on
        every ODP build."""
        kerberos_block = ""
        if params.security_enabled:
            kerberos_block = (
                "if [ -n \"${KRB5CCNAME:-}\" ]; then\n"
                "  :  # ccache already set by ambari\n"
                "elif [ -f \"%s\" ]; then\n"
                "  kinit -kt %s %s || echo \"kinit failed; continuing\"\n"
                "fi\n"
            ) % (params.impala_keytab, params.impala_keytab, params.impala_principal)

        # NOTE: tabs in heredoc keep the shell quoting unambiguous when this is
        # rendered into a regular shell file.
        return (
            "#!/usr/bin/env bash\n"
            "# Generated by Ambari IMPALA_ATLAS_EXTRACTOR component. Do not edit.\n"
            "set -uo pipefail\n"
            "\n"
            "LINEAGE_DIR=\"%s\"\n"
            "POLL_SECONDS=\"%d\"\n"
            "FILE_PREFIX=\"%s\"\n"
            "ATLAS_CONF_DIR=\"%s\"\n"
            "JAVA_HOME=\"%s\"\n"
            "LOG_FILE=\"%s\"\n"
            "STACK_ROOT=\"/usr/odp/current\"\n"
            "STACK_VERSION_ROOT=\"%s\"\n"
            "\n"
            "trap 'echo \"$(date -Iseconds) caught SIGTERM, exiting\" >> \"$LOG_FILE\"; exit 0' TERM INT\n"
            "\n"
            "%s"
            "\n"
            "# Build classpath: Atlas conf first (atlas-application.properties),\n"
            "# then atlas-impala-hook plugin jars, the bridge shim, impala-frontend\n"
            "# (provides org.apache.impala.hooks.QueryEventHook), and finally\n"
            "# hadoop client libs (SLF4J/log4j/Hadoop config).\n"
            "CP=\"$ATLAS_CONF_DIR\"\n"
            "CP=\"$CP:$STACK_VERSION_ROOT/atlas-impala-hook/hook/hook/impala/atlas-impala-plugin-impl/*\"\n"
            "CP=\"$CP:$STACK_VERSION_ROOT/atlas-impala-hook/hook/hook/impala/*\"\n"
            "for jar in $STACK_VERSION_ROOT/impala/lib/jars/impala-frontend-*.jar; do CP=\"$CP:$jar\"; done\n"
            "CP=\"$CP:$STACK_VERSION_ROOT/hadoop/lib/*\"\n"
            "CP=\"$CP:$STACK_VERSION_ROOT/hadoop/share/hadoop/common/lib/*\"\n"
            "\n"
            "while true; do\n"
            "  if [ -d \"$LINEAGE_DIR\" ]; then\n"
            "    # ImpalaLineageTool maintains its own checkpoint (FILE_PREFIX*.wal)\n"
            "    # inside LINEAGE_DIR so we can re-run it without re-publishing.\n"
            "    \"$JAVA_HOME/bin/java\" -cp \"$CP\" org.apache.atlas.impala.ImpalaLineageTool \\\n"
            "       -d \"$LINEAGE_DIR\" -p \"$FILE_PREFIX\" >> \"$LOG_FILE\" 2>&1 || \\\n"
            "       echo \"$(date -Iseconds) ImpalaLineageTool exit $?\" >> \"$LOG_FILE\"\n"
            "  fi\n"
            "  sleep \"$POLL_SECONDS\"\n"
            "done\n"
        ) % (
            params.impala_lineage_event_log_dir,
            params.impala_atlas_extractor_poll_seconds,
            params.impala_lineage_log_prefix,
            params.atlas_conf_dir,
            params.java_home,
            self.LOG_FILE,
            params.stack_version_root,
            kerberos_block,
        )


if __name__ == "__main__":
    ImpalaAtlasExtractor().execute()
