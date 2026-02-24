#!/usr/bin/env python3

'''
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
'''

import errno
import os
import tarfile
import tempfile
from contextlib import closing
import subprocess

from ambari_commons import os_utils
from resource_management.core.resources.system import Execute

def archive_dir(output_filename, input_dir, follow_links=False):
    """
    Creates a gzip tar archive of input_dir at output_filename.

    Writes through our own file descriptor to avoid sticky-dir + O_CREAT/O_TRUNC issues.
    """
    # Build tar command that writes to stdout ("-")
    # -c create, -z gzip, -f - (stdout), optional -h to follow symlinks
    tar_cmd = ['tar']
    if follow_links:
        tar_cmd += ['-h']
    tar_cmd += ['-czf', '-', '-C', input_dir, '.']

    output_dir = os.path.dirname(output_filename)
    if output_dir:
        try:
            os.makedirs(output_dir)
        except OSError as exc:
            if exc.errno != errno.EEXIST:
                raise

    # IMPORTANT: open the destination file here (agent user), then give it to Execute as stdout
    # If output path already exists and you don't own it, you'll still get a PermissionError here —
    # pick a different name/path in that case.
    with open(output_filename, 'wb', buffering=0) as out_fp:
        Execute(
            tuple(tar_cmd),
            sudo=True,                 # still run tar with sudo to read any protected input files
            tries=3,
            try_sleep=1,
            stdout=out_fp.fileno(),    # use our open FD as child's stdout
            stderr=subprocess.PIPE,    # optional: capture tar's stderr if you want logging
        )


def archive_directory_dereference(archive, directory):
  archive_dir(archive, directory, follow_links=True)


def archive_dir_via_temp_file(archive, directory):
  _, temp_output = tempfile.mkstemp()
  try:
    archive_directory_dereference(temp_output, directory)
  except:
    os_utils.remove_file(temp_output)
    raise
  else:
    Execute(("mv", temp_output, archive))


def untar_archive(archive, directory, silent=True):
  """
  Extracts a tarball using the system's tar utility. This is more
  efficient than Python 2.x's tarfile module.

  :param directory:   can be a symlink and is followed
  :param silent:  True if the output should be suppressed. This is a good
  idea in most cases as the streamed output of a huge tarball can cause
  a performance degredation
  """
  options = "-xf" if silent else "-xvf"

  Execute(('tar',options,archive,'-C',directory+"/"),
    sudo = True,
    tries = 3,
    try_sleep = 1,
  )

def get_archive_root_dir(archive):
  root_dir = None
  with closing(tarfile.open(archive, mode(archive))) as tar:
    names = tar.getnames()
    if names:
      root_dir = os.path.commonprefix(names)
  return root_dir

def mode(archive):
  if archive.endswith('.tar.gz') or archive.endswith('.tgz'):
    return 'r:gz'
  elif archive.endswith('.tar.bz2') or archive.endswith('.tbz'):
    return 'r:bz2'
  else:
    raise ValueError("Could not extract `%s` as no appropriate extractor is found" % archive)
