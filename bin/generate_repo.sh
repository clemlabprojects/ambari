#!/bin/bash
# Writes the yum/dnf repository file for one Ambari build. The release pipeline publishes the
# x86_64 packages under s3://clemlabs/<os>/... and the aarch64 packages under s3://clemlabs/<os>-aarch64/...
# (see the "Upload Release dir" stage), so the baseurl must follow the architecture the packages
# were built for (OS_ARCH, exported by the pipeline) or an aarch64 host is pointed at x86_64 RPMs.
S3_OS="${OS_TARGET}"
case "${OS_ARCH:-x86_64}" in
  aarch64|arm64)
    case "${S3_OS}" in *-aarch64) ;; *) S3_OS="${S3_OS}-aarch64" ;; esac ;;
esac
cat > $REPO_TARGET_FILE <<REPO
[ambari-clemlabs-2.8.2.0.0-$BUILD_NUMBER]
name=Ambari Clemlab's release
baseurl=https://clemlabs.s3.eu-west-3.amazonaws.com/$S3_OS/ambari-release/2.8.2.0.0-$BUILD_NUMBER/rpms/
enabled=1
gpgkey=https://clemlabs.s3.eu-west-3.amazonaws.com/$RPM_GPG_KEY
gpgcheck=1
REPO
