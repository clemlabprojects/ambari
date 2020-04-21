'''
Copyright (c) 2011-2018, Hortonworks Inc.  All rights reserved.
Except as expressly permitted in a written agreement between you
or your company and Hortonworks, Inc, any use, reproduction,
modification,
redistribution, sharing, lending or other exploitation
of all or any part of the contents of this file is strictly prohibited.
'''

import os
from resource_management import *
from ambari_commons import OSConst
from ambari_commons.os_family_impl import OsFamilyFuncImpl, OsFamilyImpl

@OsFamilyFuncImpl(os_family=OsFamilyImpl.DEFAULT)
def hst(type=None, rolling_restart=False):
  import params

  Directory(params.hst_conf_dir, recursive=True)

  if (params.log4j_props != None):
    File(format("{params.hst_conf_dir}/log4j.properties"),
      mode=0644,
      content=params.log4j_props
    )

@OsFamilyFuncImpl(os_family=OSConst.WINSRV_FAMILY)
def hst(type=None, rolling_restart=False):
  import params
  if (params.log4j_props != None):
    File(os.path.join(params.hst_conf_dir, "log4j.properties"),
         mode='f',
         content=params.log4j_props
    )
