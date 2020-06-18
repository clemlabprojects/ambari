import json
import time

from resource_management.core import shell
from resource_management.core.exceptions import Fail
from resource_management.core.logger import Logger
from resource_management.libraries.functions import format

import nifi_toolkit_util_common


def nifi_cli(command=None, subcommand=None, errors_retries=12, retries_pause=10, acceptable_errors=None,
             **command_args):
  """
  Executes nifi cli command and returns its output.

  We need execute command several times because nifi becomes responsive after some among of time.
  On non super-fast vm it takes 1.5 minutes to get nifi responding for cli calls.
  Also some commands can produce different errors but after some time that errors disappear.
  In other works - this cli is hard to use in automated environments :).

  :param command: main cli command(nifi, registry, session, etc)
  :param subcommand: sub-command of main command(nifi list-reg-clients, etc)
  :param errors_retries: retries count on acceptable errors
  :param retries_pause: pause between call retries
  :param acceptable_errors: errors that is acceptable for retry("Connection refused" error always in this list)
  :param command_args: long version of command parameters
  :return: command output
  """
  import params
  cli_env = {"JAVA_HOME": params.java_home}
  cli_script = nifi_toolkit_util_common.get_toolkit_script("cli.sh", params.toolkit_tmp_dir, params.stack_version_buildnum)

  if errors_retries < 1:
    errors_retries = 1

  if acceptable_errors is None:
    acceptable_errors = []
  acceptable_errors.append("Connection refused")

  def do_retry(output):
    for acceptable_error in acceptable_errors:
      if acceptable_error in output:
        return True
    return False

  cmd = [cli_script, command]
  if subcommand is not None:
    cmd.append(subcommand)

  client_opts = nifi_toolkit_util_common.get_client_opts()
  if params.nifi_ssl_enabled:
    command_args.update(nifi_toolkit_util_common.get_client_opts())
    command_args["proxiedEntity"] = params.nifi_initial_admin_id
  else:
    command_args["baseUrl"] = client_opts["baseUrl"]

  for arg_name, arg_value in command_args.iteritems():
    cmd.append("--" + arg_name)
    cmd.append(arg_value)

  for _ in range(0, errors_retries):
    errors_retries -= 1

    code, out = shell.call(cmd, sudo=True, env=cli_env, logoutput=False, quiet=True)

    if code != 0 and do_retry(out) and errors_retries != 0:
      time.sleep(retries_pause)
      continue
    elif code == 0:
      return out
    else:
      raise Fail("Failed to execute nifi cli.sh command")


def _update_impl(client_name=None, client_id=None, client_url=None, existing_clients=None):
  old_name = None
  old_url = None

  if not client_id:
    if not client_name:
      raise Fail("For client update 'client_name' or 'client_id' must be specified")
    for description, name, uuid, url in existing_clients:
      if name == client_name:
        client_id = uuid
        old_name = name
        old_url = url
        break
  else:
    for description, name, uuid, url in existing_clients:
      if uuid == client_id:
        old_name = name
        old_url = url

  arguments = {
    "registryClientId": client_id
  }

  do_update = False

  if client_name:
    if client_name != old_name:
      arguments["registryClientName"] = client_name
      do_update = True
      Logger.info(format("Trying to update NIFI Client name '{old_name}' to '{client_name}'"))

  if client_url:
    if client_url != old_url:
      arguments["registryClientUrl"] = client_url
      do_update = True
      Logger.info(
        format("Trying update url from '{old_url}' to '{client_url}' for NIFI Client with name '{old_name}'"))

  if do_update:
    nifi_cli(
      command="nifi",
      subcommand="update-reg-client",
      **arguments
    )
    Logger.info(format("NIFI Client '{old_name}' updated"))
  else:
    Logger.info(format("NIFI Client '{old_name}' is already up-to-date"))

  return client_id

def create_reg_client(client_name, client_url):
  client_uuid = nifi_cli(
    command="nifi",
    subcommand="create-reg-client",
    registryClientName=client_name,
    registryClientUrl=client_url
  ).strip()
  Logger.info(format("Created NIFI client '{client_name}' with url '{client_url}'"))
  return client_uuid


def list_reg_clients():
  acceptable_errors = ["Error retrieving registry clients"]
  Logger.info(format("Trying to retrieve NIFI clients..."))
  command_result = nifi_cli(
    command="nifi",
    subcommand="list-reg-clients",
    acceptable_errors=acceptable_errors,
    outputType="json"
  )
  result_json = json.loads(command_result)

  result = []

  if "registries" in result_json:
    for registry in result_json["registries"]:
      if "component" in registry:
        component = registry["component"]
        if "description" in component:
          description = component["description"]
        else:
          description = ''
        result.append((description, component["name"], component["id"], component["uri"]))

  Logger.info("Retrieved:" + str(len(result)) + " clients")
  return result


def update_reg_client(client_name=None, client_id=None, client_url=None):
  existing_clients = list_reg_clients()
  return _update_impl(
    client_name=client_name,
    client_id=client_id,
    client_url=client_url,
    existing_clients=existing_clients
  )


def create_or_update_reg_client(client_name, client_url):
  existing_clients = list_reg_clients()
  for _, name, uuid, uri in existing_clients:
    if uri == client_url:
      Logger.info("Skipping registering '{0}', already registered.".format(uri))
      return uuid
    if name == client_name:
      return _update_impl(
        client_id=uuid,
        client_url=client_url,
        existing_clients=existing_clients
      )
  return create_reg_client(client_name, client_url)
