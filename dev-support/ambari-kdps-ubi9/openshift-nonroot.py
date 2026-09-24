#!/usr/bin/env python3
"""Let the Ambari launcher run as an arbitrary non-root UID in group 0.

OpenShift assigns a random UID in group 0 and, with allowPrivilegeEscalation disabled, setuid sudo
cannot elevate — so neither branch of the launcher's guard can pass and every ambari-server call
dies with "You can't perform this operation as non-sudoer user". Every directory Ambari writes to
is already chgrp 0 / chmod g=u in this image, so membership of group 0 is the right test to add.
The sudo path is kept for anyone running the image outside OpenShift.

It also makes the resource-files keeper tolerate a failed chmod. The keeper re-packs each stack's
package directory into archive.zip and then chmods it; the file shipped by the RPM is owned by root,
and a process that is not its owner cannot chmod it however permissive the mode already is, so the
server aborts at start-up with EPERM. The mode is already what the keeper wants, so failing to set
it again is not a problem worth refusing to start over.
"""
import sys

PATH = "/usr/sbin/ambari-server"
KEEPER = "/usr/lib/ambari-server/lib/ambari_server/resourceFilesKeeper.py"
OLD = '''echo "" | sudo -S -l > /dev/null 2>&1
if [ "$?" != "0" ] && [ "$EUID" -ne 0 ] ; then
 echo "You can't perform this operation as non-sudoer user. Please re-login or configure sudo access for this user."
 exit -1
fi'''
NEW = '''if [ "$EUID" -ne 0 ] && [ "$(id -g)" -ne 0 ] ; then
 echo "" | sudo -S -l > /dev/null 2>&1
 if [ "$?" != "0" ] ; then
  echo "You can't perform this operation as non-sudoer user. Please re-login or configure sudo access for this user."
  exit -1
 fi
fi'''

src = open(PATH).read()
if NEW in src:
    print("ambari-server launcher already patched")
    sys.exit(0)
if src.count(OLD) != 1:
    sys.exit(f"ambari-server launcher guard not found as expected in {PATH}; refusing to patch blindly")
open(PATH, "w").write(src.replace(OLD, NEW))
print("ambari-server launcher accepts a non-root member of group 0")


def soften_chmod(text, call, indent):
    """Turn a bare os.chmod into a best-effort one, keeping the file's own indentation."""
    guarded = (
        "try:\n"
        + indent + "  " + call + "\n"
        + indent + "except OSError:\n"
        + indent + "  pass  # not our file: the mode it already has is the mode we wanted"
    )
    if text.count(indent + call) != 1:
        sys.exit(f"expected exactly one '{call}' in {KEEPER}; refusing to patch blindly")
    return text.replace(indent + call, indent + guarded)


keeper = open(KEEPER).read()
if "not our file" in keeper:
    print("resource-files keeper already patched")
else:
    keeper = soften_chmod(keeper, "os.chmod(hash_file, 0o644)", "      ")
    keeper = soften_chmod(keeper, "os.chmod(zip_file_path, 0o755)", "      ")
    open(KEEPER, "w").write(keeper)
    print("resource-files keeper survives a chmod it is not allowed to make")
