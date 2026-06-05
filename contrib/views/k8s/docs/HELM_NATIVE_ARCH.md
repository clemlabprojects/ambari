# Helm-Java Native Library Selection

The K8S view embeds [marcnuri/helm-java](https://github.com/marcnuri-jc/helm-java)
to talk to Helm in-process. The library ships its Go runtime as a per-arch
native shared object (`.so` / `.dylib` / `.dll`) bundled in arch-specific
classifier jars: `linux-amd64`, `linux-arm64`, `darwin-amd64`, `darwin-arm64`.

## The bug we hit

`com.marcnuri.helm-java:helm-java:0.0.15` is an **umbrella artifact** that
transitively depends on *every* platform-specific jar:

```
helm-java
├── lib-api
├── darwin-amd64    ← transitive
├── linux-amd64     ← transitive
└── linux-arm64     ← transitive
```

Each per-arch jar registers its own `META-INF/services/com.marcnuri.helm.jni.NativeLibrary`
provider entry. helm-java's `NativeLibrary.getInstance()` does **no architecture
detection** — it just iterates `ServiceLoader.load(NativeLibrary.class)` and
returns whichever implementation the iterator surfaces first. That order is
determined by classpath ordering, which depends on filesystem and classloader
implementation details — i.e. it's not deterministic across builds.

Symptom on `x86_64` master nodes: `/tmp/helm-java<rand>/helm-linux-arm64.so`
extracted, then `UnsatisfiedLinkError` when the Releases tab tries to call
`HelmService.listReleases()`. UI surfaces this as an HTTP 500 on
`/api/cluster/stats`.

## The fix

In `contrib/views/k8s/pom.xml` the umbrella dep declares `<exclusions>` for
all four per-arch transitives, and the explicit `linux-${ambari.grafana.arch}`
dependency below it becomes the single source of truth:

```xml
<dependency>
    <groupId>com.marcnuri.helm-java</groupId>
    <artifactId>helm-java</artifactId>
    <version>0.0.15</version>
    <exclusions>
        <exclusion>
            <groupId>com.marcnuri.helm-java</groupId>
            <artifactId>darwin-amd64</artifactId>
        </exclusion>
        <exclusion>
            <groupId>com.marcnuri.helm-java</groupId>
            <artifactId>darwin-arm64</artifactId>
        </exclusion>
        <exclusion>
            <groupId>com.marcnuri.helm-java</groupId>
            <artifactId>linux-amd64</artifactId>
        </exclusion>
        <exclusion>
            <groupId>com.marcnuri.helm-java</groupId>
            <artifactId>linux-arm64</artifactId>
        </exclusion>
    </exclusions>
</dependency>
<dependency>
    <groupId>com.marcnuri.helm-java</groupId>
    <artifactId>linux-${ambari.grafana.arch}</artifactId>
    <version>0.0.15</version>
</dependency>
```

`ServiceLoader` now sees exactly **one** provider so the loaded native lib
matches the target architecture deterministically, regardless of classpath
order or classloader quirks.

## Build-time arch selection

The arch is chosen at *build* time via the `ambari.grafana.arch` Maven
property:

| OS target (Jenkinsfile) | `ambari.grafana.arch` |
|---|---|
| rhel8, rhel9, ubuntu22, ubuntu24 (x86_64) | `amd64` |
| rhel9-aarch64 (aarch64) | `arm64` |

Jenkins passes `-Dambari.grafana.arch=${arch_grafana}` per build stage in
`Jenkinsfile`. For local builds outside Jenkins, pass it explicitly:

```bash
mvn package -Dambari.grafana.arch=amd64    # x86_64 target
mvn package -Dambari.grafana.arch=arm64    # aarch64 target
```

The pom default is `arm64` for legacy reasons — Jenkins always overrides, so
in CI this doesn't matter, but local developers building for x86_64 must pass
`-Dambari.grafana.arch=amd64` or they'll get a jar that fails on their
target host.

## Why not auto-detect at runtime?

helm-java upstream considers this an explicit design choice — they expect
consumers to pin to exactly one arch at build time via dependency exclusions
(or Maven profile activation). Auto-detection would require shipping all
arch jars on every node, which costs ~93 MB per unused native lib in the
final WAR. After exclusions, the K8S view jar dropped from 185 MB to 137 MB.
