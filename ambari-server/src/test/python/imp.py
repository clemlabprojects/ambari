#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""
Compatibility shim for Python 3.12+ where the stdlib imp module was removed.
Only the subset used by the Ambari test suite is implemented.
"""

import importlib.util
import sys

PY_SOURCE = 1


def load_module(name, fp, pathname, description):
    if fp:
        try:
            fp.close()
        except Exception:
            pass
    spec = importlib.util.spec_from_file_location(name, pathname)
    if spec is None or spec.loader is None:
        raise ImportError("Cannot load module %s from %s" % (name, pathname))
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    _maybe_register_stack_advisor_aliases(pathname, module)
    return module


def load_source(name, pathname):
    return load_module(name, None, pathname, (".py", "rb", PY_SOURCE))


def _maybe_register_stack_advisor_aliases(pathname, module):
    if not pathname:
        return
    normalized_path = pathname.replace("\\", "/")
    alias_map = {
        "/HDP/2.0.6/services/stack_advisor.py": "stack_advisor_hdp206",
        "/HDP/2.1/services/stack_advisor.py": "stack_advisor_hdp21",
        "/HDP/2.2/services/stack_advisor.py": "stack_advisor_hdp22",
        "/HDP/2.3/services/stack_advisor.py": "stack_advisor_hdp23",
        "/HDP/2.4/services/stack_advisor.py": "stack_advisor_hdp24",
        "/HDP/2.5/services/stack_advisor.py": "stack_advisor_hdp25",
    }
    for suffix, alias in alias_map.items():
        if normalized_path.endswith(suffix):
            sys.modules.setdefault(alias, module)
            break
