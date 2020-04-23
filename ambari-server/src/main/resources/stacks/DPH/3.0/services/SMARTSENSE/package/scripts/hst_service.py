'''
Copyright (c) 2011-2018, Hortonworks Inc.  All rights reserved.
Except as expressly permitted in a written agreement between you
or your company and Hortonworks, Inc, any use, reproduction,
modification,
redistribution, sharing, lending or other exploitation
of all or any part of the contents of this file is strictly prohibited.
'''

from resource_management import *
from ambari_commons import OSConst
from ambari_commons.os_family_impl import OsFamilyFuncImpl, OsFamilyImpl

@OsFamilyFuncImpl(os_family=OsFamilyImpl.DEFAULT)
def hst_service(action='start'):
  import params
  if action == 'start':
    daemon_cmd = "hst start"
    no_op_test = format("ls {hst_pid_file} >/dev/null 2>&1 && ps -p `cat {zk_pid_file}` >/dev/null 2>&1")
    Execute(daemon_cmd, not_if=no_op_test)
  elif action == 'stop':
    daemon_cmd = "hst stop"
    rm_pid = format("rm -f {hst_pid_file}")
    Execute(daemon_cmd)
    Execute(rm_pid)

@OsFamilyFuncImpl(os_family=OSConst.WINSRV_FAMILY)
def hst_service(action='start'):
  import params
  if action == 'start':
    Service(params.hst_win_service_name, action="start")
  elif action == 'stop':
    Service(params.hst_win_service_name, action="stop")
