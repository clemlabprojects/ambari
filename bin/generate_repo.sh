#!/bin/bash
cat > $REPO_TARGET_FILE <<EOF
[ambari-clemlabs-2.7.11.0.0-$BUILD_NUMBER]
name=Ambari Clemlab's release
baseurl=https://clemlabs.s3.eu-west-3.amazonaws.com/$OS_TARGET/ambari-release/2.7.11.0.0-$BUILD_NUMBER/rpms/
enabled=1
gpgkey=https://clemlabs.s3.eu-west-3.amazonaws.com/$RPM_GPG_KEY
gpgcheck=1
EOF
