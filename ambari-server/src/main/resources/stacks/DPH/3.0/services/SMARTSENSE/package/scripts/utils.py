'''
Copyright (c) 2011-2018, Hortonworks Inc.  All rights reserved.
Except as expressly permitted in a written agreement between you
or your company and Hortonworks, Inc, any use, reproduction,
modification,
redistribution, sharing, lending or other exploitation
of all or any part of the contents of this file is strictly prohibited.
'''

import os
import platform

def get_os():
  system = platform.system()
  
  if system == "Linux" :
    distname, version, id = platform.linux_distribution()
    return distname.lower(), version
  if system == "Windows" :
    release, version, csd, ptype = platform.win32_ver()
    return 'win', version
  
