# Helm-Java Native Library Selection

The K8S view embeds [marcnuri/helm-java](https://github.com/marcnuri-jc/helm-java)
to talk to Helm in-process. The library ships its Go runtime as a per-arch
native shared object (`.so` / `.dylib` / `.dll`) bundled in arch-specific
classifier jars: `linux-amd64`, `linux-arm64`, `darwin-amd64`, `darwin-arm64`.

## The bug we hit

`com.marcnuri.helm-java:helm-java` (version `${helm-java.version}` in `pom.xml`) is an **umbrella artifact** that
transitively depends on *every* platform-specific jar:

```
helm-java
├── lib-api
├── darwin-amd64    ← transitive
├── linux-amd64     ← transitive
└── linux-arm64     ← transitive
```

Each per-arch jar registers its own `META-INF/services/com.marcnuri.helm.jni.NativeLibrary`
provider entry. Up to helm-java 0.0.22, `NativeLibrary.getInstance()` did **no architecture
detection**: it returned whichever implementation the classpath order surfaced first, which is
not deterministic across builds. From 0.0.23 the provider that matches the running JVM's OS and
architecture is selected whatever the order, and a view that carries only another architecture's
library fails with an error naming `os.arch`.

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
    <version>${helm-java.version}</version>
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
    <version>${helm-java.version}</version>
</dependency>
```

`ServiceLoader` now sees exactly **one** provider so the loaded native lib
matches the target architecture deterministically, regardless of classpath
order or classloader quirks.

## Build-time arch selection

The arch is chosen at *build* time via the `ambari.grafana.arch` Maven
property, and by default it follows the architecture of the JVM running the
build (`os.arch`):

| Build host `os.arch` | `ambari.grafana.arch` |
|---|---|
| `amd64`, `x86_64` | `amd64` |
| `aarch64`, `arm64` | `arm64` |
| anything else | `amd64` (fallback) |

A build on an x86 agent therefore packages `linux-amd64` and a build on an
aarch64 agent packages `linux-arm64`, with no flag. The view jar goes into the
architecture-specific `ambari-server` package, so the build host and the
package normally agree.

A cross-build (for example an arm64 box producing the x86_64 package) passes
the target explicitly, which always wins over the default:

```bash
mvn package -Dambari.grafana.arch=amd64    # x86_64 target
mvn package -Dambari.grafana.arch=arm64    # aarch64 target
```

## Guards against the wrong library

A view that carries the other architecture's library still starts and serves
every Kubernetes page, then fails each Helm action (deploy, upgrade, uninstall,
release list). Two checks stop that from reaching a host:

- **Build.** The `enforce-single-helm-native-arch` step in `pom.xml` fails the
  build when `linux-${ambari.grafana.arch}-${helm-java.version}.jar` is not among the
  libraries to package, and when anything other than that one native jar is left
  after the clean-up. An unknown value of the property already fails earlier,
  because no such artifact exists.
- **Image.** Right after installing the `ambari-server` package, the OpenShift
  image build (`dev-support/ambari-kdps-ubi9/Dockerfile`) fails unless the view
  jar holds exactly one native jar, the one for the machine's own architecture.
  The check is inline in the Dockerfile, so the image build context needs no
  extra file.
- **Packages.** `dev-support/ambari-kdps-ubi9/check-helm-native-arch.py` makes
  the same check on any view jar. A release job can run it with `--arch amd64` or
  `--arch arm64` on the `k8s-view-*.jar` taken from each package (RHEL 8/9,
  Ubuntu 22/24) before publishing it.

Before a release, confirm that the published x86_64 `ambari-server` package
holds `WEB-INF/lib/linux-amd64-<helm-java version>.jar` in its `k8s-view-*.jar`, and that on
the amd64 image `GET .../api/helm/releases` answers 200: the namespaces call
alone passes even with the wrong library, because it does not use Helm.

## Why only one architecture per view?

helm-java 0.0.23 does select the native library by platform at runtime, so shipping every
architecture would work. The view still packages exactly one, for size: each unused native jar
costs about 27 MB in every `ambari-server` package and image.

## Where the library is extracted

helm-java extracts its native library before loading it, by default under `java.io.tmpdir`,
which hardened hosts often mount `noexec`. The view sets the system property
`com.marcnuri.jkube-helm.tempDir` to `<view working dir>/helm-native` before its first Helm call
(`ViewConfigurationService`); helm-java creates the directory at its first use. If it cannot,
helm-java fails with an error naming the property; it does not fall back to `java.io.tmpdir`.
Until 0.0.22 the view carried a provider of its own for this (`KdpsHelmNativeLibrary`) and
stripped the registration from the native jar at build time; both are gone.
