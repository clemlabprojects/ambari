import json, os, uuid, hashlib, hmac
import re
from resource_management import *
from resource_management.core import sudo
from resource_management.core.resources.system import File, Directory
from resource_management.core.utils import PasswordString
from resource_management.core.source import StaticFile
from resource_management.core.logger import Logger
from resource_management.libraries.functions import format
from resource_management.libraries.functions.decorator import retry
from resource_management.core import shell
from resource_management.core.exceptions import Fail

script_dir = os.path.dirname(__file__)
files_dir = os.path.realpath(os.path.join(os.path.dirname(script_dir), 'files'))
param_delim = '||'

# TODO remove all of this mapping and replacements and go for direct argument passing and build in format
# TODO instead of replace.

KEYSTORE = "KEYSTORE"
KEYSTORE_TYPE = "KEYSTORE_TYPE"
KEYSTORE_PASSWD = "KEYSTORE_PASSWD"
KEY_PASSWD = "KEY_PASSWD"
TRUSTSTORE_TYPE = "TRUSTSTORE_TYPE"
TRUSTSTORE_PASSWD = "TRUSTSTORE_PASSWD"
TRUSTSTORE = "TRUSTSTORE"
KSPWD = "KSPWD"
KPWD = "KPWD"
TSPWD = "TSPWD"
SECURITY_CONFIG = "SECURITY_CONFIG"

KEY_PASSWD_PROTECTED = "KEY_PASSWD_PROTECTED"
KEYSTORE_PASSWD_PROTECTED = "KEYSTORE_PASSWD_PROTECTED"
TRUSTSTORE_PASSWD_PROTECTED = "TRUSTSTORE_PASSWD_PROTECTED"

NIFI = "nifi"
NIFI_REGISTRY = "nifi_registry"

NIFI_SECURITY_KEYSTORE = "nifi.security.keystore"
NIFI_SECURITY_KEY_PASSWD = 'nifi.security.keyPasswd'
NIFI_SECURITY_KEYSTORE_PASSWD = 'nifi.security.keystorePasswd'
NIFI_SECURITY_KEYSTORE_TYPE = 'nifi.security.keystoreType'
NIFI_SECURITY_TRUSTSTORE = 'nifi.security.truststore'
NIFI_SECURITY_TRUSTSTORE_PASSWD = 'nifi.security.truststorePasswd'
NIFI_SECURITY_TRUSTSTORE_TYPE = 'nifi.security.truststoreType'

NIFI_REGISTRY_SECURITY_KEYSTORE = 'nifi.registry.security.keystore'
NIFI_REGISTRY_SECURITY_KEY_PASSWD = 'nifi.registry.security.keyPasswd'
NIFI_REGISTRY_SECURITY_KEYSTORE_PASSWD = 'nifi.registry.security.keystorePasswd'
NIFI_REGISTRY_SECURITY_KEYSTORE_TYPE = 'nifi.registry.security.keystoreType'
NIFI_REGISTRY_SECURITY_TRUSTSTORE = 'nifi.registry.security.truststore'
NIFI_REGISTRY_SECURITY_TRUSTSTORE_PASSWD = 'nifi.registry.security.truststorePasswd'
NIFI_REGISTRY_SECURITY_TRUSTSTORE_TYPE = 'nifi.registry.security.truststoreType'

KEYS = {
  NIFI: {
    KEYSTORE: NIFI_SECURITY_KEYSTORE,
    KEYSTORE_TYPE: NIFI_SECURITY_KEYSTORE_TYPE,
    KEYSTORE_PASSWD: NIFI_SECURITY_KEYSTORE_PASSWD,
    KEY_PASSWD: NIFI_SECURITY_KEY_PASSWD,
    TRUSTSTORE: NIFI_SECURITY_TRUSTSTORE,
    TRUSTSTORE_TYPE: NIFI_SECURITY_TRUSTSTORE_TYPE,
    TRUSTSTORE_PASSWD: NIFI_SECURITY_TRUSTSTORE_PASSWD,
    KSPWD: '#nifi.security.ambari.hash.kspwd',
    KPWD: '#nifi.security.ambari.hash.kpwd',
    TSPWD: '#nifi.security.ambari.hash.tspwd',
    KEY_PASSWD_PROTECTED: 'nifi.security.keyPasswd.protected',
    KEYSTORE_PASSWD_PROTECTED: 'nifi.security.keystorePasswd.protected',
    TRUSTSTORE_PASSWD_PROTECTED: 'nifi.security.truststorePasswd.protected',
    SECURITY_CONFIG: 'nifi-ambari-ssl-config'

  },
  NIFI_REGISTRY: {
    KEYSTORE: NIFI_REGISTRY_SECURITY_KEYSTORE,
    KEYSTORE_TYPE: NIFI_REGISTRY_SECURITY_KEYSTORE_TYPE,
    KEYSTORE_PASSWD: NIFI_REGISTRY_SECURITY_KEYSTORE_PASSWD,
    KEY_PASSWD: NIFI_REGISTRY_SECURITY_KEY_PASSWD,
    TRUSTSTORE: NIFI_REGISTRY_SECURITY_TRUSTSTORE,
    TRUSTSTORE_TYPE: NIFI_REGISTRY_SECURITY_TRUSTSTORE_TYPE,
    TRUSTSTORE_PASSWD: NIFI_REGISTRY_SECURITY_TRUSTSTORE_PASSWD,
    KSPWD: '#nifi.registry.security.ambari.hash.kspwd',
    KPWD: '#nifi.registry.security.ambari.hash.kpwd',
    TSPWD: '#nifi.registry.security.ambari.hash.tspwd',
    KEY_PASSWD_PROTECTED: 'nifi.registry.security.keyPasswd.protected',
    KEYSTORE_PASSWD_PROTECTED: 'nifi.registry.security.keystorePasswd.protected',
    TRUSTSTORE_PASSWD_PROTECTED: 'nifi.registry.security.truststorePasswd.protected',
    SECURITY_CONFIG: 'nifi-registry-ambari-ssl-config'
  }
}

REPLACERS = {
  NIFI: {
    TRUSTSTORE: (('{nifi_node_ssl_host}', 'nifi_node_host'), ('{{nifi_config_dir}}', 'nifi_config_dir')),
    KEYSTORE: (('{nifi_node_ssl_host}', 'nifi_node_host'), ('{{nifi_config_dir}}', 'nifi_config_dir')),
    KEY_PASSWD: (('{{nifi_keyPasswd}}', 'nifi_keyPasswd'),),
    KEYSTORE_PASSWD: (('{{nifi_keystorePasswd}}', 'nifi_keystorePasswd'),),
    TRUSTSTORE_PASSWD: (('{{nifi_truststorePasswd}}', 'nifi_truststorePasswd'),),
  },
  NIFI_REGISTRY: {
    TRUSTSTORE: (
      ('{nifi_registry_ssl_host}', 'nifi_registry_host'), ('{{nifi_registry_config_dir}}', 'nifi_registry_config_dir')),
    KEYSTORE: (
      ('{nifi_registry_ssl_host}', 'nifi_registry_host'), ('{{nifi_registry_config_dir}}', 'nifi_registry_config_dir')),
    KEY_PASSWD: (('{{nifi_registry_keyPasswd}}', 'nifi_registry_keyPasswd'),),
    KEYSTORE_PASSWD: (('{{nifi_registry_keystorePasswd}}', 'nifi_registry_keystorePasswd'),),
    TRUSTSTORE_PASSWD: (('{{nifi_registry_truststorePasswd}}', 'nifi_registry_truststorePasswd'),),
  }
}

HOST_PROPERTY = {
  NIFI: 'nifi_node_host',
  NIFI_REGISTRY: 'nifi_registry_host'
}

VERSION_RE = r"(((\d+\.\d+)\.\d+)\.\d+)-\d+"


def _do_replacement(prop_value, service, replacement_key):
  import params
  for to, what in REPLACERS[service][replacement_key]:
    prop_value = prop_value.replace(to, getattr(params, what))
  return prop_value


def _get_best_prefixes_match(prefix):
  match = re.match(VERSION_RE, prefix)
  if match:
    return prefix, match.group(1), match.group(2), match.group(3)
  else:
    return prefix,


def load(config_json):
  if sudo.path_isfile(config_json):
    contents = sudo.read_file(config_json)
    if len(contents) > 0:
      return json.loads(contents)
  return {}


def dump(config_json, config_dict, user, group):
  File(config_json,
       owner=user,
       group=group,
       mode=0o640,
       content=PasswordString(json.dumps(config_dict, sort_keys=True, indent=4))
       )


def overlay(config_dict, overlay_dict):
  for k, v in overlay_dict.items():
    if (k not in config_dict) or not (overlay_dict[k] == config_dict[k]):
      config_dict[k] = v


def get_toolkit_script(scriptName, scriptDir=files_dir, toolkitDirPrefix='nifi-toolkit-'):
  import params

  if params.stack_support_nifi_toolkit_package:
    result = os.path.join(params.stack_root, 'current', 'nifi-toolkit', 'bin', scriptName)
    Logger.info("Using repository toolkit script: " + result)
    return result
  else:
    Logger.info("Trying to find toolkit script dir with prefix: " + toolkitDirPrefix)

    # in case user did not aligned mpack and repo version we can fallback to partly-match toolkit version
    # for example HDF=3.2.1.0-111, and toolkit bundled is 3.2.0.0-333, code will check for prefixes
    # 3.2.1.0, 3.2.1, 3.2, and as result will use 3.2.0.0-333 toolkit, most likely this will work
    prefixes = _get_best_prefixes_match(toolkitDirPrefix)

    for prefix in prefixes:
      for dir in os.listdir(scriptDir):
        if prefix in dir and dir.startswith('nifi-toolkit'):
          if prefix != toolkitDirPrefix:
            Logger.error("Looks like your HDF stack repository not aligned to HDF mpack version, but we found " + dir +
                         " toolkit directory, and will try to use it. If you facing some problems, please, " +
                         "align your version as mentioned in documentation.")
          nifiToolkitDir = os.path.join(scriptDir, dir)
          result = nifiToolkitDir + '/bin/' + scriptName
          if not sudo.path_isfile(result):
            raise Exception("Couldn't find file " + result)
          Logger.info("Using bundled toolkit script: " + result)
          return result

    raise Exception("Couldn't find nifi toolkit directory in " + scriptDir)


def copy_toolkit_scripts(toolkit_files_dir, toolkit_tmp_dir, user, group, upgrade_type, service):
  import params

  if service == NIFI:
    run_ca_tmp_script = os.path.join(toolkit_tmp_dir, 'run_ca.sh')
    new_run_ca_tmp_script = StaticFile("run_ca.sh")

    if not sudo.path_isfile(run_ca_tmp_script) or sudo.read_file(run_ca_tmp_script) != new_run_ca_tmp_script:
      File(format(run_ca_tmp_script), content=new_run_ca_tmp_script, mode=0o755, owner=user, group=group)

  if not params.stack_support_nifi_toolkit_package:
    nifiToolkitDirFilesPath = None
    nifiToolkitDirTmpPath = None

    Logger.info("Toolkit files dir is " + toolkit_files_dir)
    Logger.info("Toolkit tmp dir is " + toolkit_tmp_dir)

    for dir in os.listdir(toolkit_files_dir):
      if dir.startswith('nifi-toolkit-'):
        nifiToolkitDirFilesPath = os.path.join(toolkit_files_dir, dir)
        nifiToolkitDirTmpPath = os.path.join(toolkit_tmp_dir, dir)

    if not sudo.path_isdir(nifiToolkitDirTmpPath) or not (upgrade_type is None):
      os.system("\cp -r " + nifiToolkitDirFilesPath + " " + toolkit_tmp_dir)
      Directory(nifiToolkitDirTmpPath, owner=user, group=group, create_parents=False, recursive_ownership=True,
                cd_access="a", mode=0o755)
      os.system("\/var/lib/ambari-agent/ambari-sudo.sh chmod -R 755 " + nifiToolkitDirTmpPath)
  else:
    Logger.info("Changing owner of package files")
    package_toolkit_dir = os.path.join(params.stack_root, 'current', 'nifi-toolkit')
    Directory(package_toolkit_dir, owner=user, group=group, create_parents=False, recursive_ownership=True,
              cd_access="a", mode=0o755,
              recursion_follow_links=True)


# done
def update_nifi_ca_properties(client_dict, nifi_properties, service):
  nifi_properties[KEYS[service][KEYSTORE_TYPE]] = client_dict['keyStoreType']
  nifi_properties[KEYS[service][KEYSTORE_PASSWD]] = client_dict['keyStorePassword']
  nifi_properties[KEYS[service][KEY_PASSWD]] = client_dict['keyPassword']
  nifi_properties[KEYS[service][TRUSTSTORE_TYPE]] = client_dict['trustStoreType']
  nifi_properties[KEYS[service][TRUSTSTORE_PASSWD]] = client_dict['trustStorePassword']


# done
def update_nifi_ssl_properties(nifi_properties, nifi_truststore,
                               nifi_truststoreType, nifi_truststorePasswd,
                               nifi_keystore, nifi_keystoreType, nifi_keystorePasswd, nifi_keyPasswd, service):
  nifi_truststore = _do_replacement(nifi_truststore, service, TRUSTSTORE)
  nifi_properties[KEYS[service][TRUSTSTORE]] = nifi_truststore
  nifi_properties[KEYS[service][TRUSTSTORE_TYPE]] = nifi_truststoreType
  nifi_properties[KEYS[service][TRUSTSTORE_PASSWD]] = nifi_truststorePasswd
  nifi_keystore = _do_replacement(nifi_keystore, service, KEYSTORE)
  nifi_properties[KEYS[service][KEYSTORE]] = nifi_keystore
  nifi_properties[KEYS[service][KEYSTORE_TYPE]] = nifi_keystoreType
  nifi_properties[KEYS[service][KEYSTORE_PASSWD]] = nifi_keystorePasswd
  nifi_properties[KEYS[service][KEY_PASSWD]] = nifi_keyPasswd
  return nifi_properties


# done
def update_nifi_ambari_hash_properties(nifi_truststorePasswd, nifi_keystorePasswd, nifi_keyPasswd, master_key, service):
  nifi_properties = {}
  nifi_properties[KEYS[service][KSPWD]] = hash(nifi_keystorePasswd, master_key)
  nifi_properties[KEYS[service][KPWD]] = hash(nifi_keyPasswd, master_key)
  nifi_properties[KEYS[service][TSPWD]] = hash(nifi_truststorePasswd, master_key)
  return nifi_properties


def store_exists(client_dict, key):
  if key not in client_dict:
    return False
  return sudo.path_isfile(client_dict[key])


def hash(value, master_key):
  m = hashlib.sha512()
  m.update(master_key)
  derived_key = m.hexdigest()[0:32]
  h = hmac.new(derived_key, value, hashlib.sha256)
  return h.hexdigest()


def match(a, b):
  if len(a) != len(b):
    return False
  result = 0
  for x, y in zip(a, b):
    result |= int(x, base=16) ^ int(y, base=16)
  return result == 0


def move_store(client_dict, key):
  if store_exists(client_dict, key):
    num = 0
    name = client_dict[key]
    while sudo.path_isfile(name + '.bak.' + str(num)):
      num += 1
    sudo.copy(name, name + '.bak.' + str(num))
    sudo.unlink(name)


# done
def move_keystore_truststore(client_dict, service):
  move_store(client_dict, KEYS[service][KEYSTORE])
  move_store(client_dict, KEYS[service][TRUSTSTORE])


def convert_properties_to_dict(prop_file):
  dict = {}
  if sudo.path_isfile(prop_file):
    lines = sudo.read_file(prop_file).split('\n')
    for line in lines:
      props = line.rstrip().split('=')
      if len(props) == 2:
        dict[props[0]] = props[1]
      elif len(props) == 1:
        dict[props[0]] = ''
  return dict


# done
def generate_keystore_truststore(orig_client_dict, new_client_dict, master_key, service):
  if not (store_exists(new_client_dict, KEYS[service][KEYSTORE]) and store_exists(new_client_dict,
                                                                                  KEYS[service][TRUSTSTORE])):
    return True
  elif orig_client_dict[KEYS[service][KEYSTORE_TYPE]] != new_client_dict[KEYS[service][KEYSTORE_TYPE]]:
    return True
  elif (KEYS[service][KSPWD] not in orig_client_dict) or not match(orig_client_dict[KEYS[service][KSPWD]],
                                                                   hash(new_client_dict[KEYS[service][KEYSTORE_PASSWD]],
                                                                        master_key)):
    return True
  elif (KEYS[service][KPWD] not in orig_client_dict) or not match(orig_client_dict[KEYS[service][KPWD]],
                                                                  hash(new_client_dict[KEYS[service][KEY_PASSWD]],
                                                                       master_key)):
    return True
  elif orig_client_dict[KEYS[service][TRUSTSTORE_TYPE]] != new_client_dict[KEYS[service][TRUSTSTORE_TYPE]]:
    return True
  elif (KEYS[service][TSPWD] not in orig_client_dict) or not match(orig_client_dict[KEYS[service][TSPWD]], hash(
      new_client_dict[KEYS[service][TRUSTSTORE_PASSWD]], master_key)):
    return True
  elif orig_client_dict[KEYS[service][KEYSTORE]] != new_client_dict[KEYS[service][KEYSTORE]]:
    return True
  elif orig_client_dict[KEYS[service][TRUSTSTORE]] != new_client_dict[KEYS[service][TRUSTSTORE]]:
    return True
  else:
    return False


def contains_providers(provider_file, tag):
  from xml.dom.minidom import parseString
  import xml.dom.minidom

  if sudo.path_isfile(provider_file):
    content = sudo.read_file(provider_file)
    dom = xml.dom.minidom.parseString(content)
    collection = dom.documentElement
    if collection.getElementsByTagName(tag):
      return True
    else:
      return False

  else:
    return False


# done
def populate_ssl_properties(old_prop, new_prop, params, service):
  if old_prop and len(old_prop) > 0:

    newKeyPasswd = _do_replacement(new_prop[KEYS[service][KEY_PASSWD]], service, KEY_PASSWD)
    newKeystorePasswd = _do_replacement(new_prop[KEYS[service][KEYSTORE_PASSWD]], service, KEYSTORE_PASSWD)
    newTruststorePasswd = _do_replacement(new_prop[KEYS[service][TRUSTSTORE_PASSWD]], service, TRUSTSTORE_PASSWD)

    if len(newKeyPasswd) == 0 and len(old_prop[KEYS[service][KEY_PASSWD]]) > 0:
      new_prop[KEYS[service][KEY_PASSWD]] = old_prop[KEYS[service][KEY_PASSWD]]
      if KEYS[service][KEY_PASSWD_PROTECTED] in old_prop:
        new_prop[KEYS[service][KEY_PASSWD_PROTECTED]] = old_prop[KEYS[service][KEY_PASSWD_PROTECTED]]

    if len(newKeystorePasswd) == 0 and len(old_prop[KEYS[service][KEYSTORE_PASSWD]]) > 0:
      new_prop[KEYS[service][KEYSTORE_PASSWD]] = old_prop[KEYS[service][KEYSTORE_PASSWD]]
      if KEYS[service][KEYSTORE_PASSWD_PROTECTED] in old_prop:
        new_prop[KEYS[service][KEYSTORE_PASSWD_PROTECTED]] = old_prop[KEYS[service][KEYSTORE_PASSWD_PROTECTED]]

    if len(newTruststorePasswd) == 0 and len(old_prop[KEYS[service][TRUSTSTORE_PASSWD]]) > 0:
      new_prop[KEYS[service][TRUSTSTORE_PASSWD]] = old_prop[KEYS[service][TRUSTSTORE_PASSWD]]
      if KEYS[service][TRUSTSTORE_PASSWD_PROTECTED] in old_prop:
        new_prop[KEYS[service][TRUSTSTORE_PASSWD_PROTECTED]] = old_prop[KEYS[service][TRUSTSTORE_PASSWD_PROTECTED]]

  return new_prop


# done
def get_nifi_ca_client_dict(config, params, service):
  if not config or len(config) == 0:
    return {}
  else:
    keystore = _do_replacement(config['configurations'][KEYS[service][SECURITY_CONFIG]][KEYS[service][KEYSTORE]], service, KEYSTORE)
    keystoreType = config['configurations'][KEYS[service][SECURITY_CONFIG]][KEYS[service][KEYSTORE_TYPE]]
    keystorePasswd = config['configurations'][KEYS[service][SECURITY_CONFIG]][KEYS[service][KEYSTORE_PASSWD]]
    keyPasswd = config['configurations'][KEYS[service][SECURITY_CONFIG]][KEYS[service][KEY_PASSWD]]
    truststore = _do_replacement(config['configurations'][KEYS[service][SECURITY_CONFIG]][KEYS[service][TRUSTSTORE]], service, TRUSTSTORE)
    truststoreType = config['configurations'][KEYS[service][SECURITY_CONFIG]][KEYS[service][TRUSTSTORE_TYPE]]
    truststorePasswd = config['configurations'][KEYS[service][SECURITY_CONFIG]][KEYS[service][TRUSTSTORE_PASSWD]]

    # default keystore/truststore type if empty
    keystoreType = '' if len(keystoreType) == 0 else keystoreType
    truststoreType = '' if len(truststoreType) == 0 else truststoreType

    nifi_ca_parent_config = config['configurations']['nifi-ambari-ssl-config']
    nifi_toolkit_tls_token = nifi_ca_parent_config['nifi.toolkit.tls.token']
    nifi_toolkit_tls_helper_days = nifi_ca_parent_config['nifi.toolkit.tls.helper.days']
    nifi_toolkit_tls_port = nifi_ca_parent_config['nifi.toolkit.tls.port']
    nifi_toolkit_dn_prefix = nifi_ca_parent_config['nifi.toolkit.dn.prefix']
    nifi_toolkit_dn_suffix = nifi_ca_parent_config['nifi.toolkit.dn.suffix']

    nifi_ca_client_config = {
      "days": int(nifi_toolkit_tls_helper_days),
      "keyStore": keystore,
      "keyStoreType": keystoreType,
      "keyStorePassword": keystorePasswd,
      "keyPassword": keyPasswd,
      "token": nifi_toolkit_tls_token,
      "dn": nifi_toolkit_dn_prefix + getattr(params, HOST_PROPERTY[service]) + nifi_toolkit_dn_suffix,
      "port": int(nifi_toolkit_tls_port),
      "caHostname": params.nifi_ca_host,
      "trustStore": truststore,
      "trustStoreType": truststoreType,
      "trustStorePassword": truststorePasswd
    }

    return nifi_ca_client_config


def existing_cluster(params):
  import re

  ZK_CONNECT_ERROR = "ConnectionLoss"
  ZK_NODE_NOT_EXIST = "Node does not exist"

  if params.security_enabled:
    kinit_cmd = "{0} -kt {1} {2}; ".format(params.kinit_path_local,
                                           params.nifi_properties['nifi.kerberos.service.keytab.location'],
                                           params.nifi_properties['nifi.kerberos.service.principal'])
  else:
    kinit_cmd = ""

  # For every zk server try to find nifi zk dir
  zookeeper_server_list = params.config['clusterHostInfo'][params.zk_hosts_property]

  for zookeeper_server in zookeeper_server_list:

    # Determine where the zkCli.sh shell script is
    # When we are on HDP the stack_root will be /usr/hdf, but ZK will be in /usr/hdp, so use zk_root and not stack_root
    zk_command_location = os.path.join(params.zk_root, "current", "zookeeper-client", "bin", "zkCli.sh")

    if params.stack_version_buildnum is not None:
      zk_command_location = os.path.join(params.zk_root, params.zk_stack_version_buildnum, "zookeeper", "bin",
                                         "zkCli.sh")

    # create the ZooKeeper query command e.g.
    command = "{0} -server {1}:{2} ls {3}".format(zk_command_location, zookeeper_server, params.zookeeper_port,
                                                  params.nifi_znode)

    Logger.info("Running command: " + command)

    code, out = shell.call(kinit_cmd + command, logoutput=True, quiet=False, timeout=20)

    if not out or re.search(ZK_CONNECT_ERROR, out):
      Logger.info("Unable to query Zookeeper: " + zookeeper_server + ". Skipping and trying next ZK server")
      continue
    elif re.search(ZK_NODE_NOT_EXIST, out):
      Logger.info("Nifi ZNode does not exist, so no pre-existing cluster.: " + params.nifi_znode)
      return False
    else:
      Logger.info("Nifi ZNode exists, so a cluster is defined: " + params.nifi_znode)
      return True

  return False


@retry(times=20, sleep_time=5, max_sleep_time=20, backoff_factor=2, err_class=Fail)
def run_toolkit_client(ca_client_dict, config_dir, jdk64_home, java_options, user, group,
                       toolkit_tmp_dir, stack_version_buildnum, no_client_file=False):
  import params
  Logger.info("Generating NiFi Keystore and Truststore")
  ca_client_script = get_toolkit_script('tls-toolkit.sh', toolkit_tmp_dir, stack_version_buildnum)
  File(ca_client_script, mode=0o755)
  if no_client_file:
    ca_client_json_dump = json.dumps(ca_client_dict)
    cert_command = (
                     'echo \'%(ca_client_json_dump)s\''
                     ' | ambari-sudo.sh'
                     ' JAVA_HOME="%(jdk64_home)s"'
                     ' JAVA_OPTS="%(java_options)s"'
                     ' %(ca_client_script)s'
                     ' client -f /dev/stdout --configJsonIn /dev/stdin'
                   ) % locals()
    code, out = shell.call(cert_command, quiet=True, logoutput=False)
    if code > 0:
      raise Fail("Call to tls-toolkit encountered error: {0}".format(out))
    else:
      json_out = out[out.index('{'):len(out)]
      updated_properties = json.loads(json_out)
      shell.call(['chown', user + ':' + group, updated_properties['keyStore']], sudo=True)
      shell.call(['chown', user + ':' + group, updated_properties['trustStore']], sudo=True)
  else:
    ca_client_json = os.path.realpath(os.path.join(config_dir, 'nifi-certificate-authority-client.json'))
    dump(ca_client_json, ca_client_dict, user, group)
    environment = {'JAVA_HOME': jdk64_home, 'JAVA_OPTS': java_options}
    Execute((ca_client_script, 'client', '-F', '-f', ca_client_json), user=user, environment=environment)
    updated_properties = load(ca_client_json)

  return updated_properties


# done
def clean_toolkit_client_files(old_nifi_properties, new_nifi_properties, service):
  move_keystore_truststore(old_nifi_properties, service)
  new_nifi_properties[KEYS[service][KEYSTORE]] = ''
  new_nifi_properties[KEYS[service][TRUSTSTORE]] = ''
  return new_nifi_properties


def get_client_opts():
  import params
  encrypt_config_script = get_toolkit_script('encrypt-config.sh', params.toolkit_tmp_dir, params.stack_version_buildnum)
  environment = {'JAVA_HOME': params.jdk64_home, 'JAVA_OPTS': params.nifi_toolkit_java_options}
  command_args = (encrypt_config_script, '-c', '-b', params.nifi_config_dir + '/bootstrap.conf', '-n',
                  params.nifi_config_dir + '/nifi.properties')
  code, out = shell.call(command_args, env=environment, logoutput=False, quiet=True, user=params.nifi_user)
  if code == 0:
    result = {}
    for line in [l for l in out.splitlines() if l]:
      try:
        name, value = line.split("=")
        result[name] = value
      except ValueError:
        pass
    return result
  else:
    raise Fail("Unable to get parameters for client.")


# done
def encrypt_sensitive_properties(
    nifi_config_dir,
    jdk64_home,
    java_options,
    nifi_user,
    last_master_key,
    master_key_password,
    is_starting,
    toolkit_tmp_dir,
    stack_version_buildnum,
    service,
    nifi_flow_config_dir=None,
    nifi_sensitive_props_key=None,
    support_encrypt_authorizers=None,
):
  encrypt_config_script = get_toolkit_script('encrypt-config.sh', toolkit_tmp_dir, stack_version_buildnum)
  encrypt_config_command = (encrypt_config_script,)
  environment = {'JAVA_HOME': jdk64_home, 'JAVA_OPTS': java_options}
  File(encrypt_config_script, mode=0o755)

  if is_starting:
    if service == NIFI:
      Logger.info("Encrypting NiFi sensitive configuration properties")
      encrypt_config_command += ('-v', '-b', nifi_config_dir + '/bootstrap.conf')
      encrypt_config_command += ('-n', nifi_config_dir + '/nifi.properties')

      if (sudo.path_isfile(nifi_flow_config_dir + '/flow.xml.gz')
          and len(sudo.read_file(nifi_flow_config_dir + '/flow.xml.gz')) > 0):
        encrypt_config_command += (
          '-f', nifi_flow_config_dir + '/flow.xml.gz', '-s', PasswordString(nifi_sensitive_props_key))

      if contains_providers(nifi_config_dir + '/login-identity-providers.xml', "provider"):
        encrypt_config_command += ('-l', nifi_config_dir + '/login-identity-providers.xml')

      if support_encrypt_authorizers and contains_providers(nifi_config_dir + '/authorizers.xml', "authorizer"):
        encrypt_config_command += ('-a', nifi_config_dir + '/authorizers.xml')

      if last_master_key:
        encrypt_config_command += ('-m', '-e', PasswordString(last_master_key))

      encrypt_config_command += ('-p', PasswordString(master_key_password))
      Execute(encrypt_config_command, user=nifi_user, logoutput=False, environment=environment)

    elif service == NIFI_REGISTRY:
      Logger.info("Encrypting NiFi Registry sensitive configuration properties")
      encrypt_config_command += ('--nifiRegistry', '-v', '-b', nifi_config_dir + '/bootstrap.conf')
      encrypt_config_command += ('-r', nifi_config_dir + '/nifi-registry.properties')

      if contains_providers(nifi_config_dir + '/identity-providers.xml', "provider"):
        encrypt_config_command += ('-i', nifi_config_dir + '/identity-providers.xml')

      if contains_providers(nifi_config_dir + '/authorizers.xml', "authorizer"):
        encrypt_config_command += ('-a', nifi_config_dir + '/authorizers.xml')

      if last_master_key:
        encrypt_config_command += ('--oldKey', PasswordString(last_master_key))

      encrypt_config_command += ('-p', PasswordString(master_key_password))
      Execute(encrypt_config_command, user=nifi_user, logoutput=False, environment=environment)


# done
def create_keystore_truststore(properties_dict, is_starting, java_opts, conf_dir, user, group, service):
  import params
  if is_starting:
    updated_properties = run_toolkit_client(get_nifi_ca_client_dict(params.config, params, service), conf_dir,
                                            params.jdk64_home, java_opts,
                                            user, group,
                                            params.toolkit_tmp_dir, params.stack_version_buildnum,
                                            params.stack_support_toolkit_update)

    update_nifi_ca_properties(updated_properties, properties_dict, service)

  return properties_dict
