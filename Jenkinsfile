def getEnvFromBranch(branch) {
  if (branch == 'develop') {
    return 'dev'
  } else if (branch == 'master'){
    return 'prod'
  } else if (branch == 'release'){
    return 'qa'
  } else {
    return 'test'
  }
}
def getProjectFromBranch(branch) {
  if (branch == 'develop') {
    return 'dev'
  } else if (branch == 'master'){
    return 'prod'
  } else if (branch == 'release'){
    return 'dev'
  } else {
    return 'test'
  }
}
pipeline {
  agent {
    kubernetes {
      yaml '''
        apiVersion: v1
        kind: Pod
        metadata:
          labels:
            jenkins-slave-es: jenkins-slave-es
        spec:
          tolerations:
          - key: "jenkinsSlave"
            operator: "Equal"
            value: "true"
            effect: "NoExecute"
          containers:
          - name: jnlp
            volumeMounts:
              - mountPath: "/home/jenkins/ansible-vault-password"
                name: ansible-secrets
                readOnly: true
              - mountPath: "/home/jenkins/service-account-keys"
                name: account-secrets
                readOnly: true
              - name: dockersock
                mountPath: "/var/run/docker.sock"
              - name: nfs-maven-cache-3
                mountPath: "/home/jenkins/.m2"
              - name: nfs-node-cache-3
                mountPath: "/home/jenkins/.npm"
              - name: workspace
                mountPath: "/home/jenkins/agent/workspace"
              - name: workspace-volume
                mountPath: /home/jenkins/agent
            image: europe-north1-docker.pkg.dev/lupapiste-dev/cloudpermit-fi-docker-repo/jenkins-inbound-agent:3.1.22
            tty: true
            env:
            - name: ANSIBLE_VAULT_PASSWORD_FILE
              value: "/home/jenkins/ansible-vault-password/password"
            - name: CLOUDPERMIT_GITHUB_USERNAME
              value: "lupapiste-ci"
            - name: CLOUDPERMIT_GITHUB_TOKEN
              valueFrom:
                secretKeyRef:
                  name: lupapiste-ci
                  key: text
            resources:
              limits:
                memory: 8Gi
              requests:
                cpu: "6"
                memory: 8Gi
          - name: elasticsearch
            image: docker.elastic.co/elasticsearch/elasticsearch:7.17.23
            ports:
              - containerPort: 9200
            tty: true
            env:
            - name: node.name
              value: "elasticsearch1"
            - name: discovery.type
              value: "single-node"
            - name: xpack.security.enabled
              value: "false"
            - name: http.port
              value: "9200"
          volumes:
            - name: ansible-secrets
              secret:
                secretName: ansible-vault-password
            - name: account-secrets
              secret:
                secretName: service-account-keys
            - name: dockersock
              hostPath:
                path: /var/run/docker.sock
            - name: nfs-maven-cache-3
              persistentVolumeClaim:
                claimName: nfs-maven-cache-3
            - name: nfs-node-cache-3
              persistentVolumeClaim:
                claimName: nfs-node-cache-3
            - name: workspace
              emptyDir:
                medium: Memory
            - emptyDir: {}
              name: workspace-volume
          nodeSelector:
            cloud.google.com/gke-nodepool: jenkins-build-slave-pool
        '''
      }
  }
  options {
    disableConcurrentBuilds()
    buildDiscarder(logRotator(numToKeepStr: "10"))
  }
  environment {
    targetedEnv = getEnvFromBranch(BRANCH_NAME)
    targetedProject = getProjectFromBranch(BRANCH_NAME)
  }

  triggers { pollSCM( scmpoll_spec: BRANCH_NAME == "master" ? "30 22 * * *"  : "" , ignorePostCommitHooks: BRANCH_NAME == "master" ? 'true'  : "" )}

  stages{
    stage('Build') {
      steps {
        echo "Building in ${env.targetedEnv}"
        sh 'lein deps'
        sh './build.sh'
      }
    }
    stage('Image') {
      when {
        anyOf {
          expression { BRANCH_NAME == "develop" }
          expression { BRANCH_NAME == "release" }
          expression { BRANCH_NAME == "master" }
        }
      }
      steps {
        script{
          sh "gcloud auth activate-service-account --key-file=/home/jenkins/service-account-keys/dev.json"
          sh "gcloud auth configure-docker europe-north1-docker.pkg.dev"
          sh "docker build -t europe-north1-docker.pkg.dev/lupapiste-dev/cloudpermit-fi-docker-repo/onkalo:${env.targetedEnv}-\${GIT_COMMIT} \
          -t europe-north1-docker.pkg.dev/lupapiste-dev/cloudpermit-fi-docker-repo/onkalo:latest \
          -t europe-north1-docker.pkg.dev/lupapiste-dev/cloudpermit-fi-docker-repo/onkalo:master-latest ."
          sh "docker push europe-north1-docker.pkg.dev/lupapiste-dev/cloudpermit-fi-docker-repo/onkalo:${env.targetedEnv}-\${GIT_COMMIT}"
          if( BRANCH_NAME == "develop")
            sh "docker push europe-north1-docker.pkg.dev/lupapiste-dev/cloudpermit-fi-docker-repo/onkalo:latest"
          if( BRANCH_NAME == "master")
            sh "docker push europe-north1-docker.pkg.dev/lupapiste-dev/cloudpermit-fi-docker-repo/onkalo:master-latest"
        }
      }
    }
    stage('deploy') {
      when {
        anyOf {
          expression { BRANCH_NAME == "develop" }
          expression { BRANCH_NAME == "release" }
        }
      }
      steps {
        script{
          sh 'mkdir -p gcp-infra'
          dir("gcp-infra"){
            git branch: "master",
            changelog: false,
            poll: false,
            credentialsId: 'github-key',
            url: 'git@github.com:cloudpermit/gcp-infra.git'
          }
          sh "gcloud container clusters get-credentials lupis --zone europe-north1-b --project lupapiste-${env.targetedProject}"
          dir("gcp-infra/ansible"){
              sh """
                kcmd.sh -s onkalo -n lupapiste-${env.targetedEnv} -- curl http://localhost:8012/internal/lock || true
                ansible-playbook -i lupapiste/${env.targetedEnv} -e onkalo_tag=${env.targetedEnv}-\${GIT_COMMIT} onkalo.yml ; kcmd.sh -s onkalo -n lupapiste-${env.targetedEnv} -- curl http://localhost:8012/internal/unlock || true
                """
          }
        }
      }
    }
    stage('deploy-prod') {
      when {
        anyOf {
          allOf {
            expression { BRANCH_NAME == "master" }
            triggeredBy 'SCMTrigger'
          }
          allOf {
            expression { BRANCH_NAME == "master" }
            triggeredBy 'UserIdCause'
          }
        }
      }
      steps {
        script{
          sh 'mkdir -p gcp-infra'
          dir("gcp-infra"){
            git branch: "master",
            changelog: false,
            poll: false,
            credentialsId: 'github-key',
            url: 'git@github.com:cloudpermit/gcp-infra.git'
          }
          sh "gcloud auth activate-service-account --key-file=/home/jenkins/service-account-keys/prod.json"
          sh "gcloud container clusters get-credentials lupis --region europe-north1 --project lupapiste-${env.targetedProject}"
          dir("gcp-infra/ansible"){
              sh """
                kcmd.sh -s onkalo -n lupapiste -- curl http://localhost:8012/internal/lock || true
                ansible-playbook -i lupapiste/${env.targetedEnv} -e onkalo_tag=${env.targetedEnv}-\${GIT_COMMIT} --tags=deploy onkalo.yml ; kcmd.sh -s onkalo -n lupapiste -- curl http://localhost:8012/internal/unlock || true
                """
          }
        }
      }
    }
  }
  post {
   failure {
      script{
        if ( BRANCH_NAME == "develop" | BRANCH_NAME == "master" | BRANCH_NAME == "release")
          slackSend (color: '#FF0000', channel: "#dev-lupapiste", message: "FAILED: Job '${env.JOB_NAME} - ${env.BRANCH_NAME} [${env.BUILD_NUMBER}]' (${env.BUILD_URL})")
      }
   }
   fixed {
      script{
        if ( BRANCH_NAME == "develop" | BRANCH_NAME == "master" | BRANCH_NAME == "release")
          slackSend (color: '#00FF00', channel: "#dev-lupapiste", message: "Back to normal: Job '${env.JOB_NAME} - ${env.BRANCH_NAME} [${env.BUILD_NUMBER}]' (${env.BUILD_URL})")
      }
   }
   success {
     script{
        if ( BRANCH_NAME == "master")
          slackSend (color: '#00FF00', channel: "#dev-lupapiste", message: "Production deployment: Job '${env.JOB_NAME} - ${env.BRANCH_NAME} [${env.BUILD_NUMBER}]' (${env.BUILD_URL})")
     }
   }
  }
}
