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
#!/usr/bin/env python3
import os, sys, shlex, tempfile, subprocess, pwd
from resource_management.core import shell
from resource_management.core.logger import Logger
from resource_management.core.exceptions import ExecutionFailed
from functools import reduce

def quote_bash_args(command):
  if not command:
    return "''"
  
  if not isinstance(command, str):
    raise Fail(f"Command should be a list of strings, found '{str(command)}' in command list elements")
  
  valid = set(string.ascii_letters + string.digits + '@%_-+=:,./')
  for char in command:
    if char not in valid:
      return "'" + command.replace("'", "'\"'\"'") + "'"
  return command

def _add_current_path_to_env(env):
  result = {} if not env else env
  
  if not 'PATH' in result:
    result['PATH'] = os.environ['PATH']
    
  # don't append current env if already there
  if not set(os.environ['PATH'].split(os.pathsep)).issubset(result['PATH'].split(os.pathsep)):
    result['PATH'] = os.pathsep.join([os.environ['PATH'], result['PATH']])
  
  return result
  
def _get_environment_str(env):
  return reduce(lambda str,x: f'{str} {x}={quote_bash_args(env[x])}', env, '')
  
def get_user_call_output(command, user, quiet=False, is_checked_call=True, **call_kwargs):
    """
    Run `command` as `user`, capturing stdout/stderr into temp files (no shell redirection).
    Returns (code, stdout_text, stderr_text).
    """
    # ---- normalize command to argv ----
    argv = command if isinstance(command, (list, tuple)) else shlex.split(command)

    # ---- temp files for output ----
    fout = tempfile.NamedTemporaryFile(mode="ab", delete=False)
    ferr = tempfile.NamedTemporaryFile(mode="ab", delete=False)

    try:
        # Modes aren't strictly needed now (we write via already-open FDs), but fine to keep.
        os.chmod(fout.name, 0o666)
        os.chmod(ferr.name, 0o666)

        # ---- privilege drop setup ----
        pw = pwd.getpwnam(user)
        uid, gid, name = pw.pw_uid, pw.pw_gid, pw.pw_name

        def _drop_privs():
            # Minimal, order matters; do NOT log here.
            os.setgid(gid)
            # populate supplementary groups for that user
            try:
                os.initgroups(name, gid)
            except Exception:
                # On some platforms, initgroups may fail if NSS is odd; fall back to clearing groups
                try:
                    os.setgroups([])
                except Exception:
                    pass
            os.setuid(uid)
            # optional: set a sane umask
            os.umask(0o22)

        # If we aren't root, we cannot setuid/setgid; fall back to sudo/runuser
        if os.geteuid() != 0:
            # Prefer sudo if present; you can also use runuser -u <user> -- <cmd>
            argv = ["sudo", "-n", "-u", name, "--"] + argv
            preexec = None
        else:
            preexec = _drop_privs

        env = _add_current_path_to_env(call_kwargs.get('env', None))
 
        # ---- spawn ----
        p = subprocess.Popen(
            argv,
            stdout=fout,
            stderr=ferr,
            env=env,
            preexec_fn=preexec,
            # close_fds=True is default on POSIX; good hygiene when handing FDs explicitly
        )
        code = p.wait()

        # ---- read outputs ----
        fout.flush(); os.fsync(fout.fileno())
        ferr.flush(); os.fsync(ferr.fileno())

        # Close writers before reading to avoid races/position issues
        fout.close(); ferr.close()

        with open(fout.name, "rb") as rfout, open(ferr.name, "rb") as rferr:
            out = rfout.read().decode("utf-8", errors="replace").rstrip("\n")
            err = rferr.read().decode("utf-8", errors="replace").rstrip("\n")

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
        # If still open, close; then remove temp files
        try:
            fout.close()
        except Exception:
            pass
        try:
            ferr.close()
        except Exception:
            pass
        try:
            os.remove(fout.name)
        except Exception:
            pass
        try:
            os.remove(ferr.name)
        except Exception:
            pass
