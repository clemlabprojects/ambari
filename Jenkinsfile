pipeline {
    agent any

    stages {
        stage("Slack Notif Start"){
            steps {
                slackSend channel: 'build', message: "Ambari Build Started - ${env.JOB_NAME} ${env.BUILD_NUMBER} (<${env.BUILD_URL}|Open>)"
            }
        }
        stage('Clone Repo') {
            steps {
                git branch: 'master', credentialsId: 'clemlabBuilderPass', url: 'https://builder:caDuKq5Wo7Jwa75qyw1j@gitlab.luc-data.com/dph/ambari.git' 
                
            }
        }
    }
}
node {
    withCredentials([string(credentialsId: 'builder', variable: 'GITLAB_API_TOKEN')]){
        withEnv([]){
            try {
                stage("build Apache Ambari Release"){
                    sh """
                        mvn -B install package rpm:rpm "-Dmaven.clover.skip=true" "-DskipTests" "-Dstack.distribution=ODP" "-Drat.ignoreErrors=true" -Dpython.ver="python >= 2.6" -Dfindbugs.skip=true -DnewVersion=2.7.6.0.0 -DbuildNumber=7ee807e194f55e732298abdb8c672413f267c2f344cc573c50f76803fe38f5e1708db3605086048560dfefa6a2cda1ac6e704ee1686156fd1e9acce1dc60def7 -Prpm
                    """
                }
                stage('Copy Ambari RPM') {
                    
                    
                    sh """
                        mkdir -p /var/www/html/ambari-release/dist/centos7/1.x/BUILDS/2.7.6.0-$BUILD_NUMBER/rpms
                        cp /var/lib/jenkins/workspace/ambari-release/ambari-agent/target/rpm/ambari-agent/RPMS/x86_64/ambari-agent-2.7.6.0-0.x86_64.rpm /var/www/html/ambari-release/dist/centos7/1.x/BUILDS/2.7.6.0-$BUILD_NUMBER/rpms
                        cp /var/lib/jenkins/workspace/ambari-release/ambari-infra/target/rpm/ambari-infra/RPMS/noarch/ambari-infra-2.7.6.0-0.noarch.rpm /var/www/html/ambari-release/dist/centos7/1.x/BUILDS/2.7.6.0-$BUILD_NUMBER/rpms
                        cp /var/lib/jenkins/workspace/ambari-release/ambari-infra/ambari-infra-manager/target/rpm/ambari-infra-manager/RPMS/noarch/ambari-infra-manager-2.7.6.0-0.noarch.rpm /var/www/html/ambari-release/dist/centos7/1.x/BUILDS/2.7.6.0-$BUILD_NUMBER/rpms
                        cp /var/lib/jenkins/workspace/ambari-release/ambari-infra/ambari-infra-solr-client/target/rpm/ambari-infra-solr-client/RPMS/noarch/ambari-infra-solr-client-2.7.6.0-0.noarch.rpm /var/www/html/ambari-release/dist/centos7/1.x/BUILDS/2.7.6.0-$BUILD_NUMBER/rpms
                        cp /var/lib/jenkins/workspace/ambari-release/ambari-infra/ambari-infra-assembly/target/rpm/ambari-infra-solr/RPMS/noarch/ambari-infra-solr-2.7.6.0-0.noarch.rpm /var/www/html/ambari-release/dist/centos7/1.x/BUILDS/2.7.6.0-$BUILD_NUMBER/rpms
                        cp /var/lib/jenkins/workspace/ambari-release/ambari-server/target/rpm/ambari-server/RPMS/x86_64/ambari-server-2.7.6.0-0.x86_64.rpm /var/www/html/ambari-release/dist/centos7/1.x/BUILDS/2.7.6.0-$BUILD_NUMBER/rpms
                        cp /var/lib/jenkins/workspace/ambari-release/ambari-views/target/rpm/ambari-views/RPMS/noarch/ambari-views-2.7.6.0-0.noarch.rpm /var/www/html/ambari-release/dist/centos7/1.x/BUILDS/2.7.6.0-$BUILD_NUMBER/rpms
                        createrepo /var/www/html/ambari-release/dist/centos7/1.x/BUILDS/2.7.6.0-$BUILD_NUMBER/rpms
                    """
                }
                stage('Send Notifications') {
                    slackSend channel: 'build', message: "Ambari Build Successfull - ${env.JOB_NAME} ${env.BUILD_NUMBER} (<${env.BUILD_URL}|Open>)"
                }
            }
            catch (e) {
                slackSend channel: 'build', message: "Ambari Build Failed - ${env.JOB_NAME} ${env.BUILD_NUMBER} (<${env.BUILD_URL}|Open>)"
                // Since we're catching the exception in order to report on it,
                // we need to re-throw it, to ensure that the build is marked as failed
                throw e
            } 
                    
        }   
    }
}
