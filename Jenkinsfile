pipeline {
    agent none
    stages {
        stage("Slack Notif Start"){
            steps {
                slackSend channel: 'build', message: "AMBARI Release FULL Build Started - ${env.JOB_NAME} ${env.BUILD_NUMBER} (<${env.BUILD_URL}|Open>)"
            }
        }
        stage('Build AMBARI rhel7') {
            agent { 
                label 'rhel7'
            }
            steps {
                runODPBUILD('centos7','rhel7')

            }
        }
        stage('Build AMBARI rhel8') {
            agent { 
                label 'rhel8'
            }
            steps {
                runODPBUILD('centos8','rhel8')
            } 
        }        
    
  }
}

void runODPBUILD(osType, osTarget) {
  script {
        withCredentials([string(credentialsId: 'builder', variable: 'GITLAB_API_TOKEN')]){
            withEnv(["OS_TARGET=${osType}","REPO_TARGET_FILE=/var/www/html/ambari-release/dist/${osType}/1.x/BUILDS/2.7.6.0-$BUILD_NUMBER/ambari.repo","RELEASE_DIR=/var/www/html/ambari-release/dist/${osType}/1.x/BUILDS/2.7.6.0-$BUILD_NUMBER/"]){
                try {
                    def odpReleaseNumber = 270
                    sh 'cat /etc/redhat-release'
                    git branch: 'master', credentialsId: 'lucasbak', url: 'ssh://git@github.com/clemlabprojects/ambari.git'
                    
                    stage("ODP Release number"){
                        echo "Last Success Build Number: ${odpReleaseNumber}"
                    }
                    stage("build Apache Ambari Release"){
                        sh """
                            cat /etc/redhat-release
                            mvn clean "-Dodp.release.number=${odpReleaseNumber}"
                            mvn -B install package rpm:rpm "-Dmaven.clover.skip=true" "-DskipTests" "-Dstack.distribution=ODP" "-Drat.ignoreErrors=true" -Dpython.ver="python >= 2.6" -Dfindbugs.skip=true -DnewVersion=2.7.6.0.0 -DbuildNumber=7ee807e194f55e732298abdb8c672413f267c2f344cc573c50f76803fe38f5e1708db3605086048560dfefa6a2cda1ac6e704ee1686156fd1e9acce1dc60def7 -Dviews -Prpm "-Dodp.release.number=${odpReleaseNumber}" -DaltReleaseDeploymentRepository=nexus::default::https://nexus.luc-data.com/repository/maven-releases/ -DaltDeploymentRepository=nexus::default::https://nexus.luc-data.com/repository/maven-releases/
                        """
                    }
                    stage("build Apache Ambari Metrics"){
                        sh """
                            cd ambari-metrics
                            mvn clean package -Dbuild-rpm -DskipTests "-Dodp.release.number=${odpReleaseNumber}"
                        """
                    }
                    stage('Copy Ambari RPM') {
                        sh """
                            export RPMS_RELEASE_DIR=/var/www/html/ambari-release/dist/${osType}/1.x/BUILDS/2.7.6.0-$BUILD_NUMBER/rpms
                            mkdir -p /var/www/html/ambari-release/dist/${osType}/1.x/BUILDS/2.7.6.0-$BUILD_NUMBER/rpms
                            cp /var/lib/jenkins/workspace/ambari-full-AMBARI-15/ambari-agent/target/rpm/ambari-agent/RPMS/x86_64/ambari-agent-2.7.6.0-0.x86_64.rpm /var/www/html/ambari-release/dist/${osType}/1.x/BUILDS/2.7.6.0-$BUILD_NUMBER/rpms
                            cp /var/lib/jenkins/workspace/ambari-full-AMBARI-15/ambari-infra/target/rpm/ambari-infra/RPMS/noarch/ambari-infra-2.7.6.0-0.noarch.rpm /var/www/html/ambari-release/dist/${osType}/1.x/BUILDS/2.7.6.0-$BUILD_NUMBER/rpms
                            cp /var/lib/jenkins/workspace/ambari-full-AMBARI-15/ambari-infra/ambari-infra-manager/target/rpm/ambari-infra-manager/RPMS/noarch/ambari-infra-manager-2.7.6.0-0.noarch.rpm /var/www/html/ambari-release/dist/${osType}/1.x/BUILDS/2.7.6.0-$BUILD_NUMBER/rpms
                            cp /var/lib/jenkins/workspace/ambari-full-AMBARI-15/ambari-infra/ambari-infra-solr-client/target/rpm/ambari-infra-solr-client/RPMS/noarch/ambari-infra-solr-client-2.7.6.0-0.noarch.rpm /var/www/html/ambari-release/dist/${osType}/1.x/BUILDS/2.7.6.0-$BUILD_NUMBER/rpms
                            cp /var/lib/jenkins/workspace/ambari-full-AMBARI-15/ambari-infra/ambari-infra-assembly/target/rpm/ambari-infra-solr/RPMS/noarch/ambari-infra-solr-2.7.6.0-0.noarch.rpm /var/www/html/ambari-release/dist/${osType}/1.x/BUILDS/2.7.6.0-$BUILD_NUMBER/rpms
                            cp /var/lib/jenkins/workspace/ambari-full-AMBARI-15/ambari-server/target/rpm/ambari-server/RPMS/x86_64/ambari-server-2.7.6.0-0.x86_64.rpm /var/www/html/ambari-release/dist/${osType}/1.x/BUILDS/2.7.6.0-$BUILD_NUMBER/rpms
                            cp /var/lib/jenkins/workspace/ambari-full-AMBARI-15/ambari-views/target/rpm/ambari-views/RPMS/noarch/ambari-views-2.7.6.0-0.noarch.rpm /var/www/html/ambari-release/dist/${osType}/1.x/BUILDS/2.7.6.0-$BUILD_NUMBER/rpms
                            cp /var/lib/jenkins/workspace/ambari-full-AMBARI-15/contrib/views/files/target/rpm/files/RPMS/noarch/files-1.0.0.0-SNAPSHOT.noarch.rpm /var/www/html/ambari-release/dist/${osType}/1.x/BUILDS/2.7.6.0-$BUILD_NUMBER/rpms
                            cp /var/lib/jenkins/workspace/ambari-full-AMBARI-15/contrib/views/capacity-scheduler/target/rpm/capacity-scheduler/RPMS/noarch/capacity-scheduler-1.0.0.0-SNAPSHOT.noarch.rpm /var/www/html/ambari-release/dist/${osType}/1.x/BUILDS/2.7.6.0-$BUILD_NUMBER/rpms
                            cp /var/lib/jenkins/workspace/ambari-full-AMBARI-15/ambari-metrics/ambari-metrics-assembly/target/rpm/ambari-metrics-*/RPMS/x86_64/*.rpm /var/www/html/ambari-release/dist/${osType}/1.x/BUILDS/2.7.6.0-$BUILD_NUMBER/rpms
                            /var/lib/jenkins/signall_rpms.sh /var/www/html/ambari-release/dist/${osType}/1.x/BUILDS/2.7.6.0-$BUILD_NUMBER/rpms
                            createrepo /var/www/html/ambari-release/dist/${osType}/1.x/BUILDS/2.7.6.0-$BUILD_NUMBER/rpms
                        """
                    }
                    stage('Generate REPO-FILE') {
                    
                      sh './bin/generate_repo.sh'
                    
                    }
                    
                    stage('Generate repos-ambari.tar.gz') {
                       sh "tar -czf /var/www/html/repos-ambari.tar.gz /var/www/html/ambari-release/dist/${osType}/1.x/BUILDS/2.7.6.0-$BUILD_NUMBER/"
                    }
                    
                    stage('Upload Realease dir') {
                        sh "aws s3 cp $RELEASE_DIR s3://clemlabs/${osType}/ambari-tests/2.7.6.0-$BUILD_NUMBER --recursive"
                        sh "aws s3 cp /var/www/html/repos-ambari.tar.gz s3://clemlabs/${osType}/ambari-tests/2.7.6.0-$BUILD_NUMBER/repos-ambari.tar.gz"
                        sh 'aws s3 website --index-document index.htm s3://clemlabs'

                    }
                    
                    stage('Send Notifications') {
                        slackSend channel: 'build', message: "Ambari ${osTarget} Build Successfull - ${env.JOB_NAME} ${env.BUILD_NUMBER} (<${env.BUILD_URL}|Open>)"
                    }
                }
                catch (e) {
                    slackSend channel: 'build', message: "Ambari ${osTarget} Release  Build Failed - ${env.JOB_NAME} ${env.BUILD_NUMBER} (<${env.BUILD_URL}|Open>)"
                    // Since we're catching the exception in order to report on it,
                    // we need to re-throw it, to ensure that the build is marked as failed
                    throw e
                } 
                        
            }   }
    }
  
}
