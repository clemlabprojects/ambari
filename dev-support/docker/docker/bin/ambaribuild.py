#!/usr/bin/env python
# coding: utf-8
#   Licensed under the Apache License, Version 2.0 (the "License");
#   you may not use this file except in compliance with the License.
#   You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#   Unless required by applicable law or agreed to in writing, software
#   distributed under the License is distributed on an "AS IS" BASIS,
#   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#   See the License for the specific language governing permissions and
#   limitations under the License.

import os
import glob
import subprocess, time, sys
import shutil
import json
import datetime
from optparse import OptionParser

SKIP_TEST="-DskipTests"
MVN_EXTRA_ARGS = os.environ.get("AMBARI_MVN_EXTRA_ARGS", "").strip()
AMBARI_AUTH_HEADERS = "--header 'Authorization:Basic YWRtaW46YWRtaW4=' --header 'X-Requested-By: PIVOTAL'"
AMBARI_BUILD_DOCKER_ROOT = "/tmp/ambari-build-docker"
NO_EXIT_SLEEP_TIME=60
RETRY_MAX=20
RPM_ARCH = os.environ.get("AMBARI_RPM_ARCH", "").strip()

def get_rpm_arch():
	if RPM_ARCH:
		return RPM_ARCH
	try:
		arch = subprocess.check_output("uname -m", shell=True).decode("utf-8").strip()
		return arch if arch else "x86_64"
	except Exception:
		return "x86_64"

def git_deep_cleaning():
	proc = subprocess.Popen("git clean -xdf",
			shell=True,
			cwd="/tmp/ambari")
	return proc.wait()

def ambariUnitTest():
	extra_args = (" " + MVN_EXTRA_ARGS) if MVN_EXTRA_ARGS else ""
	proc = subprocess.Popen("mvn -fae clean install" + extra_args,
			shell=True,
			cwd="/tmp/ambari")
	return proc.wait()

def buildAmbari(stack_distribution):
	stack_distribution_param = ""
	if stack_distribution is not None:
		stack_distribution_param = "-Dstack.distribution=" + stack_distribution
	extra_args = (" " + MVN_EXTRA_ARGS) if MVN_EXTRA_ARGS else ""
	proc = subprocess.Popen("mvn -B clean install package rpm:rpm -Dmaven.clover.skip=true -Dfindbugs.skip=true "
						+ SKIP_TEST + " "
						+ stack_distribution_param + extra_args + " -Dpython.ver=\"python >= 2.6\"",
			shell=True,
			cwd="/tmp/ambari")
	return proc.wait()

def install_ambari_server():
	arch = get_rpm_arch()
	proc = subprocess.Popen(
		"sudo yum install -y /tmp/ambari/ambari-server/target/rpm/ambari-server/RPMS/" + arch + "/ambari-server-*.rpm",
		shell=True,
	)
	retcode = proc.wait()
	if retcode != 0:
		return retcode
	retcode = install_ambari_addons(arch)
	return retcode

def _rpm_query(rpm_path, field):
	try:
		cmd = "rpm -qp --qf '%{{{0}}}' {1}".format(field, rpm_path)
		return subprocess.check_output(cmd, shell=True).decode("utf-8").strip()
	except Exception:
		return None

def _select_rpms(rpm_paths, prefer_arch):
	selected = {}
	for rpm_path in rpm_paths:
		name = _rpm_query(rpm_path, "NAME")
		arch = _rpm_query(rpm_path, "ARCH")
		if not name or not arch:
			continue
		# skip IT/test artifacts
		if name.endswith("-it"):
			continue
		existing = selected.get(name)
		if not existing:
			selected[name] = (arch, rpm_path)
			continue
		existing_arch, _ = existing
		if arch == prefer_arch and existing_arch == "noarch":
			selected[name] = (arch, rpm_path)
	return [rpm for _, rpm in sorted(selected.values())]

def _install_rpms(label, rpm_paths):
	if not rpm_paths:
		print("WARNING: no {0} RPMs found; skipping install".format(label))
		return 0
	cmd = "sudo yum install -y " + " ".join(rpm_paths)
	print("Installing {0} RPMs...".format(label))
	proc = subprocess.Popen(cmd, shell=True)
	return proc.wait()

def install_ambari_addons(arch):
	infra_candidates = glob.glob("/tmp/ambari/ambari-infra/**/target/rpm/**/RPMS/*/ambari-infra-*.rpm", recursive=True)
	metrics_candidates = glob.glob("/tmp/ambari/ambari-metrics/**/target/rpm/**/RPMS/*/ambari-metrics-*.rpm", recursive=True)

	infra_rpms = _select_rpms(sorted(set(infra_candidates)), arch)
	metrics_rpms = _select_rpms(sorted(set(metrics_candidates)), arch)

	retcode = _install_rpms("ambari-infra", infra_rpms)
	if retcode != 0:
		return retcode
	retcode = _install_rpms("ambari-metrics", metrics_rpms)
	return retcode

def install_ambari_agent():
	arch = get_rpm_arch()
	proc = subprocess.Popen(
		"sudo yum install -y /tmp/ambari/ambari-agent/target/rpm/ambari-agent/RPMS/" + arch + "/ambari-agent-*.rpm",
		shell=True,
	)
	return proc.wait()

def setup_ambari_server():
	java_home = os.environ.get("AMBARI_JAVA_HOME", "/opt/java").strip() or "/opt/java"
	db_host = os.environ.get("AMBARI_DB_HOST", "db-ambari-test").strip() or "db-ambari-test"
	db_port = os.environ.get("AMBARI_DB_PORT", "5432").strip() or "5432"
	db_name = os.environ.get("AMBARI_DB_NAME", "ambari_db").strip() or "ambari_db"
	db_user = os.environ.get("AMBARI_DB_USER", "ambari_user").strip() or "ambari_user"
	db_pass = os.environ.get("AMBARI_DB_PASS", "ambari123").strip() or "ambari123"
	jdbc_driver = os.environ.get("AMBARI_JDBC_DRIVER", "").strip()
	if not jdbc_driver:
		candidates = glob.glob("/usr/lib/ambari-server/postgresql-*.jar")
		jdbc_driver = candidates[0] if candidates else "/usr/lib/ambari-server/postgresql-42.2.2.jar"
	setup_cmd = (
		"if [ ! -d \"{java}/jre/lib/security\" ] && [ -d \"{java}/lib/security\" ]; then "
		"sudo mkdir -p \"{java}/jre/lib\" && sudo ln -sfn \"{java}/lib/security\" \"{java}/jre/lib/security\"; "
		"fi; "
		"sudo ambari-server setup -s "
		"--enable-lzo-under-gpl-license "
		"--java-home={java} "
		"--ambari-java-home={java} "
		"--stack-java-home={java} "
		"--database=postgres "
		"--databasehost={db_host} "
		"--databaseport={db_port} "
		"--databasename={db_name} "
		"--databaseusername={db_user} "
		"--databasepassword={db_pass} "
		"--jdbc-db=postgres "
		"--jdbc-driver={jdbc_driver}"
	).format(
		java=java_home,
		db_host=db_host,
		db_port=db_port,
		db_name=db_name,
		db_user=db_user,
		db_pass=db_pass,
		jdbc_driver=jdbc_driver
	)
	proc = subprocess.Popen(setup_cmd, shell=True)
	retcode = proc.wait()
	if retcode != 0:
		return retcode

	# Ensure DB properties are persisted in ambari.properties (setup sometimes skips them in containers).
	jdbc_url = "jdbc:postgresql://{0}:{1}/{2}".format(db_host, db_port, db_name)
	password_file = "/etc/ambari-server/conf/password.dat"
	# Try to detect OS family/type for ambari-server validation.
	os_family = "redhat"
	os_type = "redhat"
	try:
		with open("/etc/os-release", "r") as handle:
			data = handle.read()
		version_id = None
		for line in data.splitlines():
			if line.startswith("VERSION_ID="):
				version_id = line.split("=", 1)[1].strip().strip('"')
				break
		if version_id:
			major = version_id.split(".")[0]
			os_family = "redhat{0}".format(major)
			os_type = "redhat{0}".format(major)
	except Exception:
		pass
	props_cmd = (
		"sudo sh -c '"
		"set -euo pipefail; "
		"prop_file=/etc/ambari-server/conf/ambari.properties; "
		"if [ ! -e /usr/jdk64 ]; then ln -sfn {java_home} /usr/jdk64; fi; "
		"set_prop() {{ "
		"  local key=\"$1\"; local value=\"$2\"; "
		"  if grep -q \"^${{key}}=\" \"$prop_file\"; then "
		"    sed -i \"s|^${{key}}=.*|${{key}}=${{value}}|\" \"$prop_file\"; "
		"  else "
		"    echo \"${{key}}=${{value}}\" >> \"$prop_file\"; "
		"  fi; "
		"}}; "
		"set_prop server.jdbc.database postgres; "
		"set_prop server.jdbc.database_name {db_name}; "
		"set_prop server.jdbc.user.name {db_user}; "
		"set_prop server.jdbc.user.passwd {password_file}; "
		"set_prop server.jdbc.url {jdbc_url}; "
		"set_prop server.jdbc.driver org.postgresql.Driver; "
		"set_prop server.jdbc.connection-pool c3p0; "
		"set_prop server.persistence.type remote; "
		"set_prop ambari.java.home {java_home}; "
		"set_prop java.home {java_home}; "
		"set_prop jdk1.8.home /usr/jdk64/; "
		"set_prop server.os_family {os_family}; "
		"set_prop server.os_type {os_type}; "
		"if [ ! -f {password_file} ]; then "
		"  echo -n {db_pass} > {password_file}; "
		"  chmod 600 {password_file}; "
		"fi; "
		"'"
	).format(
		db_name=db_name,
		db_user=db_user,
		db_pass=db_pass,
		jdbc_url=jdbc_url,
		password_file=password_file,
		java_home=java_home,
		os_family=os_family,
		os_type=os_type
	)
	proc = subprocess.Popen(props_cmd, shell=True)
	return proc.wait()

def ensure_ambari_user():
	ambari_user = os.environ.get("AMBARI_SERVER_USER", "root").strip() or "root"
	proc = subprocess.Popen(
		"sudo sh -c \"grep -q '^ambari-server.user=' /etc/ambari-server/conf/ambari.properties || "
		"echo 'ambari-server.user={0}' >> /etc/ambari-server/conf/ambari.properties\"; "
		"if [ '{0}' != 'root' ]; then "
		"getent group {0} >/dev/null 2>&1 || sudo groupadd {0}; "
		"id {0} >/dev/null 2>&1 || sudo useradd -g {0} -m {0}; "
		"fi".format(ambari_user),
		shell=True,
	)
	return proc.wait()

def start_ambari_server(debug=False):
	proc = subprocess.Popen("sudo ambari-server start" + (" --debug" if debug else ""),
			shell=True)
	return proc.wait()

def start_dependant_services():
	retcode = 0
	has_service = shutil.which("service") is not None

	# Start SSHD with a fallback that works on newer distros/containers.
	proc = subprocess.Popen("sudo mkdir -p /var/run/sshd", shell=True)
	retcode += proc.wait()
	sshd_rc = 1
	if has_service:
		proc = subprocess.Popen("sudo service sshd start", shell=True)
		sshd_rc = proc.wait()
	if sshd_rc != 0 and os.path.exists("/usr/sbin/sshd"):
		proc = subprocess.Popen("sudo /usr/sbin/sshd", shell=True)
		sshd_rc = proc.wait()
	elif sshd_rc != 0 and not os.path.exists("/usr/sbin/sshd"):
		print("WARNING: sshd not available; skipping")
	retcode += sshd_rc

	# Try a time service, but don't fail the build if it's unavailable.
	time_rc = 1
	if has_service:
		for svc in ("ntpd", "chronyd"):
			proc = subprocess.Popen("sudo service {0} start".format(svc), shell=True)
			time_rc = proc.wait()
			if time_rc == 0:
				break
		if time_rc != 0:
			print("WARNING: unable to start ntpd/chronyd; continuing")
	else:
		print("WARNING: service command not found; skipping ntpd/chronyd")

	return retcode

def configure_ambari_agent():
	proc = subprocess.Popen("hostname -f", stdout=subprocess.PIPE, shell=True)
	hostname = proc.stdout.read().rstrip()
	if isinstance(hostname, bytes):
		hostname = hostname.decode("utf-8")
	proc = subprocess.Popen("sudo sed -i 's/hostname=localhost/hostname=" + hostname + "/g' /etc/ambari-agent/conf/ambari-agent.ini",
			shell=True)
	return proc.wait()

def start_ambari_agent(wait_until_registered = True):
	proc = subprocess.Popen("sudo service ambari-agent start || sudo ambari-agent start",
			shell=True)
	retcode = proc.wait()
	if wait_until_registered:
		if wait_until_ambari_agent_registered():
			return 0
		print("ERROR: ambari-agent was not registered.")
		sys.exit(1)

	return retcode

def wait_until_ambari_agent_registered():
	'''
	return True if ambari agent is found registered.
	return False if timeout
	'''
	count = 0
	while count < RETRY_MAX:
		count += 1
		proc = subprocess.Popen("curl " +
				"http://localhost:8080/api/v1/hosts " +
				AMBARI_AUTH_HEADERS,
				stdout=subprocess.PIPE,
				shell=True)
		hosts_result_string = proc.stdout.read()
		hosts_result_json = json.loads(hosts_result_string)
		if len(hosts_result_json["items"]) != 0:
			return True
		time.sleep(5)
	return False

def post_blueprint():
	proc = subprocess.Popen("curl -X POST -D - " +
			"-d @single-node-HDP-2.1-blueprint1.json http://localhost:8080/api/v1/blueprints/myblueprint1 " +
			AMBARI_AUTH_HEADERS ,
			cwd=AMBARI_BUILD_DOCKER_ROOT + "/blueprints",
			shell=True)
	return proc.wait()

def create_cluster():
	proc = subprocess.Popen("curl -X POST -D - " +
			"-d @single-node-hostmapping1.json http://localhost:8080/api/v1/clusters/mycluster1 " +
			AMBARI_AUTH_HEADERS ,
			cwd=AMBARI_BUILD_DOCKER_ROOT + "/blueprints",
			shell=True)
	return proc.wait()

# Loop to not to exit Docker container
def no_exit():
	print("")
	print("loop to not to exit docker container...")
	print("")
	while True:
		time.sleep(NO_EXIT_SLEEP_TIME)

class ParseResult:
	is_deep_clean = False
	is_rebuild = False
	stack_distribution = None
	is_test = False
	is_install_server = False
	is_install_agent = False
	is_deploy = False
	is_server_debug = False

def parse(argv):
	result = ParseResult()
	if len(argv) >=2:
		parser = OptionParser()
		parser.add_option("-c", "--clean",
				dest="is_deep_clean",
				action="store_true",
				default=False,
				help="if this option is set, git clean -xdf is executed for the ambari local git repo")

		parser.add_option("-b", "--rebuild",
				dest="is_rebuild",
				action="store_true",
				default=False,
				help="set this flag if you want to rebuild Ambari code")

		parser.add_option("-s", "--stack_distribution",
				dest="stack_distribution",
				help="set a stack distribution. [HDP|PHD|BIGTOP]. Make sure -b is also set when you set a stack distribution")

		parser.add_option("-d", "--server_debug",
				dest="is_server_debug",
				action="store_true",
				default=False,
				help="set a debug option for ambari-server")

		(options, args) = parser.parse_args(argv[1:])
		if options.is_deep_clean:
			result.is_deep_clean = True
		if options.is_rebuild:
			result.is_rebuild = True
		if options.stack_distribution:
			result.stack_distribution = options.stack_distribution
		if options.is_server_debug:
			result.is_server_debug = True

	if argv[0] == "test":
		result.is_test = True

	if argv[0] == "server":
		result.is_install_server = True

	if argv[0] == "agent":
		result.is_install_server = True
		result.is_install_agent = True

	if argv[0] == "deploy":
		result.is_install_server = True
		result.is_install_agent = True
		result.is_deploy = True

	return result

if __name__ == "__main__":

	if len(sys.argv) == 1:
		print("specify one of test, server, agent or deploy")
		sys.exit(1)

	start = datetime.datetime.utcnow()

	# test: execute unit test
	# server: install ambari-server
	#    with or without rebuild
	# agent: install ambari-server and ambari-agent
	#    with or without rebuild
	# deploy: install ambari-server, ambari-agent and deploy Hadoop
	#    with or without rebuild

	parsed_args = parse(sys.argv[1:])

	if parsed_args.is_deep_clean:
		retcode = git_deep_cleaning()
		if retcode != 0: sys.exit(retcode)

	if parsed_args.is_test:
		retcode = ambariUnitTest()
		end = datetime.datetime.utcnow()
		print("")
		print("Duration: " + str((end-start).seconds) + " seconds")
		sys.exit(retcode)

	if parsed_args.is_rebuild:
		retcode = buildAmbari(parsed_args.stack_distribution)
		if retcode != 0: sys.exit(retcode)

	if parsed_args.is_install_server:
		retcode = install_ambari_server()
		if retcode != 0: sys.exit(retcode)
		retcode = setup_ambari_server()
		if retcode != 0: sys.exit(retcode)
		retcode = ensure_ambari_user()
		if retcode != 0: sys.exit(retcode)
		retcode = start_ambari_server(parsed_args.is_server_debug)
		if retcode != 0: sys.exit(retcode)
		retcode = start_dependant_services()
		if retcode != 0: sys.exit(retcode)

	if parsed_args.is_install_agent:
		retcode = install_ambari_agent()
		if retcode != 0: sys.exit(retcode)
		retcode = configure_ambari_agent()
		if retcode != 0: sys.exit(retcode)
		retcode = start_ambari_agent()
		if retcode != 0: sys.exit(retcode)

	if parsed_args.is_deploy:
		retcode = post_blueprint()
		if retcode != 0: sys.exit(retcode)
		retcode = create_cluster()
		if retcode != 0: sys.exit(retcode)

	end = datetime.datetime.utcnow()

	print("")
	print("Duration: " + str((end-start).seconds) + " seconds")
	print("Parameters: " + str(sys.argv))
	if os.environ.get("AMBARI_NO_EXIT") == "1":
		sys.exit(0)
	no_exit()
