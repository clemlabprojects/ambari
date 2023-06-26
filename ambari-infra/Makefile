# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

GIT_REV_SHORT = $(shell git rev-parse --short HEAD)
MAVEN_BINARY ?= mvn

ifeq ("$(INFRA_JDK_11)", "true")
  INFRA_JAVA_VERSION = "11"
else
  INFRA_JAVA_VERSION = "1.8"
endif

package:
	$(MAVEN_BINARY) clean package -Djdk.version=$(INFRA_JAVA_VERSION)

install:
	$(MAVEN_BINARY) clean install -Djdk.version=$(INFRA_JAVA_VERSION)

test:
	$(MAVEN_BINARY) clean test -Djdk.version=$(INFRA_JAVA_VERSION)

update-version:
	$(MAVEN_BINARY) versions:set-property -Dproperty=revision -DnewVersion=$(new-version) -DgenerateBackupPoms=false -Djdk.version=$(INFRA_JAVA_VERSION)

rpm:
	$(MAVEN_BINARY) clean package -Dbuild-rpm -DskipTests -Djdk.version=$(INFRA_JAVA_VERSION)

deb:
	$(MAVEN_BINARY) clean package -Dbuild-deb -DskipTests -Djdk.version=$(INFRA_JAVA_VERSION)

docker-build:
	$(MAVEN_BINARY) clean package docker:build -DskipTests -Dbuild-deb -Djdk.version=$(INFRA_JAVA_VERSION)

docker-push:
	$(MAVEN_BINARY) clean package docker:build docker:push -DskipTests -Dbuild-deb -Djdk.version=$(INFRA_JAVA_VERSION)
