'''
Copyright (c) 2011-2018, Hortonworks Inc.  All rights reserved.
Except as expressly permitted in a written agreement between you
or your company and Hortonworks, Inc, any use, reproduction,
modification,
redistribution, sharing, lending or other exploitation
of all or any part of the contents of this file is strictly prohibited.
'''

from resource_management import Script, Execute
import os

class HstServiceCheck(Script):
  def service_check(self, env):
    import params
    env.set_params(params)

    Execute("hst status",
            logoutput=True,
            tries=3,
            try_sleep=20
    )

if __name__ == "__main__":
  HstServiceCheck().execute()
