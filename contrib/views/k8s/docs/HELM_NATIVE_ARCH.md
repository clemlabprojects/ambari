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
  build when `linux-${ambari.grafana.arch}-0.0.15.jar` is not among the
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
holds `WEB-INF/lib/linux-amd64-0.0.15.jar` in its `k8s-view-*.jar`, and that on
the amd64 image `GET .../api/helm/releases` answers 200: the namespaces call
alone passes even with the wrong library, because it does not use Helm.

## Why not auto-detect at runtime?

helm-java upstream considers this an explicit design choice — they expect
consumers to pin to exactly one arch at build time via dependency exclusions
(or Maven profile activation). Auto-detection would require shipping all
arch jars on every node, which costs ~93 MB per unused native lib in the
final WAR. After exclusions, the K8S view jar dropped from 185 MB to 137 MB.
