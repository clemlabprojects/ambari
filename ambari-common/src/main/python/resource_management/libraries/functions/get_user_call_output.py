#!/usr/bin/env python3
"""
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

Ambari Agent

"""

import os, sys, shlex, tempfile, subprocess, pwd, threading
from resource_management.core import shell
from resource_management.core.logger import Logger
from resource_management.core.exceptions import ExecutionFailed, ExecuteTimeoutException
from resource_management.core.signal_utils import TerminateStrategy, terminate_process
from ambari_commons.constants import AMBARI_SUDO_BINARY
from functools import reduce

# --- helpers to decide when a shell is needed ---
_SHELL_TOKENS = {
    "|", "||", "&&", ";", "<", ">", ">>", "2>", "2>>", "2>&1",
    "&", "(", ")", "{", "}", "*", "?", "~", "$(", "`"
}

def _needs_shell_from_list(argv):
    # If caller passed shell syntax as separate items (e.g. "|", "&&"), use a shell.
    return any(tok in _SHELL_TOKENS for tok in argv)

def _to_shell_string(cmd):
    # Ensure a single string for bash -c/-lc; preserve shell tokens
    if isinstance(cmd, (list, tuple)):
        parts = []
        for item in cmd:
            s = str(item)
            if s in _SHELL_TOKENS:
                parts.append(s)
            else:
                parts.append(shlex.quote(s))
        return " ".join(parts)
    return cmd

def _on_timeout(proc, timeout_event, terminate_strategy):
    timeout_event.set()
    terminate_process(proc, terminate_strategy)

def quote_bash_args(command):
  # (left as-is from your code)
  import string
  if not command:
    return "''"
  if not isinstance(command, str):
    raise Exception(f"Command should be a list of strings, found '{str(command)}' in command list elements")
  valid = set(string.ascii_letters + string.digits + '@%_-+=:,./')
  for char in command:
    if char not in valid:
      return "'" + command.replace("'", "'\"'\"'") + "'"
  return command

def _add_current_path_to_env(env):
  result = {} if not env else dict(env)
  if 'PATH' not in result:
    result['PATH'] = os.environ.get('PATH', '')
  if not set(os.environ.get('PATH','').split(os.pathsep)).issubset(result['PATH'].split(os.pathsep)):
    result['PATH'] = os.pathsep.join([os.environ.get('PATH',''), result['PATH']])
  return result

def _prefix_env_for_sudo(argv, env):
  if not env:
    return list(argv)
  env_args = [f"{key}={value}" for key, value in env.items()]
  return ["/usr/bin/env"] + env_args + list(argv)

def _get_sudo_binary():
  return AMBARI_SUDO_BINARY

def _get_environment_str(env):
  return reduce(lambda s,x: f'{s} {x}={quote_bash_args(env[x])}', env, '')

def get_user_call_output(command, user, quiet=False, is_checked_call=True, **call_kwargs):
    """
    Run `command` as `user`, capturing stdout/stderr into temp files (no shell redirection).
    Accepts either a string (may contain pipes, quotes, etc.) or a list/tuple.
    Returns (code, stdout_text, stderr_text).
    """
    # ---- temp files for output ----
    fout = tempfile.NamedTemporaryFile(mode="ab", delete=False)
    ferr = tempfile.NamedTemporaryFile(mode="ab", delete=False)

    try:
        os.chmod(fout.name, 0o666)
        os.chmod(ferr.name, 0o666)

        # ---- privilege drop setup ----
        pw = pwd.getpwnam(user)
        uid, gid, name = pw.pw_uid, pw.pw_gid, pw.pw_name

        def _drop_privs():
            os.setgid(gid)
            try:
                os.initgroups(name, gid)
            except Exception:
                try:
                    os.setgroups([])
                except Exception:
                    pass
            os.setuid(uid)
            os.umask(0o22)

        # ---- decide argv & preexec based on command type and shell need ----
        env = _add_current_path_to_env(call_kwargs.get('env', None))
        path = call_kwargs.get('path')
        if path:
            path_str = os.pathsep.join(path) if isinstance(path, (list, tuple)) else path
            env['PATH'] = os.pathsep.join([env.get('PATH', ''), path_str])
        cwd = call_kwargs.get('cwd', None)
        timeout = call_kwargs.get('timeout', None)
        timeout_kill_strategy = call_kwargs.get('timeout_kill_strategy', TerminateStrategy.TERMINATE_PARENT)
        use_shell = False
        if isinstance(command, str):
            # Strings likely contain shell syntax (and must be one arg to bash -c)
            use_shell = True
            command_str = command
        elif isinstance(command, (list, tuple)):
            if _needs_shell_from_list(command):
                use_shell = True
                command_str = _to_shell_string(command)
            else:
                # Pure argv, no shell required
                argv = list(command)
        else:
            raise TypeError("command must be a string or a list/tuple")

        # For non-root callers keep old semantics: run as login shell for target user.
        # This avoids Kerberos/session regressions seen with direct `sudo -u ... <argv>`.
        if os.geteuid() != 0:
            if use_shell:
                command_to_run = command_str
            else:
                command_to_run = shell.string_cmd_from_args_list(argv)

            as_user_command = shell.as_user(command_to_run, name, env=env)
            argv = ["/bin/bash", "--login", "--noprofile", "-c", as_user_command]
            preexec = None
        else:
            if use_shell:
                argv = ["bash", "-lc", command_str]
            preexec = _drop_privs

        # ---- spawn ----
        p = subprocess.Popen(
            argv,
            stdout=fout,
            stderr=ferr,
            env=env,
            cwd=cwd,
            preexec_fn=preexec,
        )
        timeout_event = None
        timer = None
        if timeout:
            timeout_event = threading.Event()
            timer = threading.Timer(timeout, _on_timeout, [p, timeout_event, timeout_kill_strategy])
            timer.start()

        code = p.wait()

        # ---- read outputs ----
        fout.flush(); os.fsync(fout.fileno())
        ferr.flush(); os.fsync(ferr.fileno())
        fout.close(); ferr.close()

        with open(fout.name, "rb") as rfout, open(ferr.name, "rb") as rferr:
            out = rfout.read().decode("utf-8", errors="replace").rstrip("\n")
            err = rferr.read().decode("utf-8", errors="replace").rstrip("\n")

        if timeout:
            if not timeout_event.is_set():
                timer.cancel()
            else:
                err_msg = Logger.filter_text(f"Execution of {argv!r} was killed due timeout after {timeout} seconds")
                raise ExecuteTimeoutException(err_msg)

        # ---- error handling & logging ----
        if code:
            all_output = f"{err}\n{out}".strip()
            err_msg = Logger.filter_text(f"Execution of {argv!r} returned {code}. {all_output}")
            if is_checked_call:
                raise ExecutionFailed(err_msg, code, out, err)
            else:
                Logger.warning(err_msg)

        result = (code, out, err)

        caller_filename = sys._getframe(1).f_code.co_filename
        is_internal_call = shell.NOT_LOGGED_FOLDER in caller_filename
        if quiet is False or (quiet is None and not is_internal_call):
            Logger.info(f"{get_user_call_output.__name__} returned {result}")

        return result

    finally:
        try: fout.close()
        except Exception: pass
        try: ferr.close()
        except Exception: pass
        try: os.remove(fout.name)
        except Exception: pass
        try: os.remove(ferr.name)
        except Exception: pass
