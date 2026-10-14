#!/usr/bin/env python3
"""Fail unless a KDPS view jar carries the Helm native library of the architecture it runs on.

The view calls Helm in-process through helm-java, whose Go runtime is a per-architecture jar
(WEB-INF/lib/linux-<arch>-<version>.jar). The view jar is built once per architecture and ships in the
architecture-specific ambari-server package; a jar built for the other architecture loads and serves
every page that only talks to Kubernetes, then fails on each deploy, upgrade, uninstall and release
list. Nothing else notices, so the packaging is checked here: exactly one native jar, and it is the
one that matches the machine.

Usage: check-helm-native-arch.py [--arch amd64|arm64] VIEW_JAR [VIEW_JAR ...]

Without --arch the architecture is taken from this machine (uname -m), which is the right test inside
an image build. A package or release job that inspects a jar built for another machine passes --arch.
"""
import argparse
import platform
import re
import sys
import zipfile

NATIVE = re.compile(r"^WEB-INF/lib/(linux|darwin|windows)-[a-z0-9_]+-[^/]+\.jar$")
HELM_ARCH = {"x86_64": "amd64", "amd64": "amd64", "aarch64": "arm64", "arm64": "arm64"}


def check(jar, wanted):
    """Return the problem found in the view jar, or None when it ships exactly the wanted native jar."""
    try:
        with zipfile.ZipFile(jar) as z:
            found = sorted(n for n in z.namelist() if NATIVE.match(n))
    except (OSError, zipfile.BadZipFile) as e:
        return "cannot read %s: %s" % (jar, e)
    expected = "WEB-INF/lib/linux-%s-" % wanted
    if len(found) == 1 and found[0].startswith(expected):
        return None
    return "%s must contain exactly one Helm native jar, linux-%s-*.jar, but contains: %s" % (
        jar, wanted, ", ".join(n.rsplit("/", 1)[1] for n in found) or "none")


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--arch", choices=sorted(set(HELM_ARCH.values())),
                        help="Helm architecture the jar must carry (default: this machine)")
    parser.add_argument("jars", nargs="+", metavar="VIEW_JAR")
    args = parser.parse_args()

    wanted = args.arch or HELM_ARCH.get(platform.machine())
    if wanted is None:
        print("unsupported machine architecture %s" % platform.machine(), file=sys.stderr)
        return 1

    failed = False
    for jar in args.jars:
        problem = check(jar, wanted)
        if problem:
            print("ERROR: " + problem, file=sys.stderr)
            failed = True
        else:
            print("%s: Helm native library linux-%s" % (jar, wanted))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
