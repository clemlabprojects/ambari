<!--
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
-->

# Trino memory sizing (KDPS)

How KDPS sizes the Java heap and the Kubernetes resources of a Trino release, and how to fix a release
deployed before chart `clemlab-trino` 1.43.15.

## 1. Why the heap must stay below the memory limit

Kubernetes kills a container (`OOMKilled`, exit code 137) as soon as the whole process uses more memory than
its limit. A Java process uses memory outside the heap too: JIT code cache (up to 512 MB in Trino's
`jvm.config`), metaspace for the plugins, thread stacks, direct buffers, the JMX agent. With a heap as large as
the limit (`-Xmx8G` with an `8Gi` limit, the setting of charts before 1.43.15), a busy coordinator or worker
grows its heap toward the limit and the kernel kills it before Java can raise an `OutOfMemoryError`. A killed
coordinator aborts every running query; a killed worker fails the queries running on it.

## 2. What a new deployment gets

| Wizard field | Default | Effect |
|---|---|---|
| Memory limit (coordinator, per worker) | `8Gi` | Memory cap of the container. |
| Memory request | `8Gi` | Same as the limit. |
| CPU request / CPU limit | `2` / `2` | Same as the limit. |
| Java heap size (optional) | blank | The chart renders `-XX:MaxRAMPercentage=75` and `-XX:InitialRAMPercentage=75` instead of `-Xmx`: the heap is 75% of the memory limit (6 GiB for `8Gi`, 3 GiB for `4Gi`) and follows the limit when it changes. Without a memory limit, the chart falls back to a fixed `-Xmx8G`. |

Requests equal to limits put the pods in the **Guaranteed** QoS class: Kubernetes reserves the memory and the
pods are not among the first evicted when a node runs short of memory. KDPS also gives the chart's own init
containers (keystore, Ranger directories, Hadoop configuration) equal requests and limits (100m CPU, 128Mi),
because Kubernetes counts init containers when it sets the QoS class. Requests count against the project quota:
plan for 2 CPU and 8 GiB per pod, for the coordinator and for every worker autoscaling may add.

To set the heap yourself, use the Java format (`6G`, `6144M`; Java reads `G` as GiB, so not `6Gi`) and keep it at
70-80% of the memory limit. The wizard refuses a heap of 85% of the limit or more, and a request higher than its
limit (Kubernetes would refuse the pod), and says which field to fix. When you lower the memory limit, lower the
memory request with it.

Trino itself refuses to start when the heap cannot hold `query.max-memory-per-node` (1GB by default) plus its heap
headroom (30% of the heap by default). With the default 75% heap, keep the memory limit at `2Gi` or more, or
lower `query.max-memory-per-node` with it.

Check a running pod:

```
kubectl -n <namespace> exec <coordinator-pod> -- cat /etc/trino/jvm.config | grep -E 'Xmx|RAMPercentage'
kubectl -n <namespace> exec <coordinator-pod> -- jcmd 1 VM.flags | tr ' ' '\n' | grep MaxHeapSize
kubectl -n <namespace> get pod <coordinator-pod> -o jsonpath='{.status.qosClass}'
```

## 3. Releases deployed before chart 1.43.15

They run with `-Xmx8G` (the old chart default) and a `4Gi` request. Two ways to fix them; both restart the pods.

**Move the release to chart 1.43.15** (recommended). Releases page → *Upgrade chart* to 1.43.15, or *Upgrade* and
set *Version* to 1.43.15. The heap then follows the memory limit, unless the release sets
`coordinator.jvm.maxHeapSize` / `worker.jvm.maxHeapSize` itself. In the *Upgrade* wizard, also set the memory and
CPU requests equal to the limits to get the Guaranteed QoS class.

**Stay on the current chart.** Releases page → *Upgrade / Config*, keep the version and leave the *Java heap size*
fields blank. An older chart cannot size the heap from the memory limit, so the wizard sends an explicit heap of
75% of the limit instead (`6G` for `8Gi`, `3G` for `4Gi`); the YAML editor shows it before you deploy. Clearing
the heap in the YAML editor (`maxHeapSize: ""` or `maxHeapSize:`) or deleting the key there works like a blank
field: the heap is computed from the memory limit sent, YAML editor included. That heap is then part of the
release's values: the next *Upgrade / Config* shows it in the *Java heap size* fields and refuses it if the memory
limit is lowered without it. Clear the fields to have it computed from the new limit again.

A release upgraded outside the wizard needs the explicit heap in its values, at about 75% of the memory limit (an
empty value only works from chart 1.43.15):

```yaml
coordinator:
  jvm:
    maxHeapSize: "6G"   # for an 8Gi coordinator memory limit
worker:
  jvm:
    maxHeapSize: "6G"   # for an 8Gi worker memory limit
```

In the *Upgrade / Config* wizard, the *Java heap size* fields or the YAML editor set the same values.
