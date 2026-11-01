#!/usr/bin/env python3
"""
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
"""
import re

OPTIONS_LINE = "options='{{hue_db_options}}'"


def with_database_options(template):
  """The hue.ini template with the database options (hue_db_options) in its [[database]] section.
  Templates referencing hue_db_options already render them; a [[database]] section already holding
  an options= line is left alone (configobj refuses duplicate keys)."""
  if "hue_db_options" in template:
    return template
  section = re.search(r"\[\[database\]\](.*?)(?=\n[ \t]*\[|\Z)", template, re.S)
  if section is None or re.search(r"^[ \t]*options[ \t]*=", section.group(1), re.M):
    return template
  return template[:section.end(1)] + "\n" + OPTIONS_LINE + template[section.end(1):]
