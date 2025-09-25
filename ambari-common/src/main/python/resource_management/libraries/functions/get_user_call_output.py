#!/usr/bin/env python3
import os, sys, shlex, tempfile, subprocess, pwd
from resource_management.core import shell
from resource_management.core.logger import Logger
from resource_management.core.exceptions import ExecutionFailed
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
    # Ensure a single string for bash -c/-lc
    if isinstance(cmd, (list, tuple)):
        return " ".join(shlex.quote(x) for x in cmd)
    return cmd

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

        if use_shell:
            # Use a login shell (keeps behavior you were aiming at); switch to "-c" if you don't want login env.
            bash_bits = ["bash", "-lc", command_str]
            if os.geteuid() != 0:
                argv = ["sudo", "-n", "-u", name, "--"] + bash_bits
                preexec = None
            else:
                argv = bash_bits
                preexec = _drop_privs
        else:
            # No shell path
            if os.geteuid() != 0:
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
        )
        code = p.wait()

        # ---- read outputs ----
        fout.flush(); os.fsync(fout.fileno())
        ferr.flush(); os.fsync(ferr.fileno())
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
        try: fout.close()
        except Exception: pass
        try: ferr.close()
        except Exception: pass
        try: os.remove(fout.name)
        except Exception: pass
        try: os.remove(ferr.name)
        except Exception: pass
