def SHARED_WORKSPACE_PATH = "/mnt/bx500/workspaces/tesla_android-radxa_zero"
def BASE_PATH = "/mnt/bx500/overlays/tesla_android-radxa_zero"

def getRepoURL() {
    sh "git config --get remote.origin.url > .git/remote-url"
    return readFile(".git/remote-url").trim()
}

def getCommitSha() {
    sh "git rev-parse HEAD > .git/current-commit"
    return readFile(".git/current-commit").trim()
}

def getCurrentBranch() {
    return env.BRANCH_NAME;
}

def getBuildNumber() {
    return env.BUILD_NUMBER;
}

def getVersion(file) {
    def version = file =~ /ro\.tesla-android\.build\.version\s*=\s*([0-9]+\.[0-9]+\.[0-9]+(?:\.[0-9]+)?)/;
    def fullVersion = version[0][0];
    def versionNumber = fullVersion.split('=')[1].trim()
    return versionNumber;
}

void setBuildStatus(String message, String state) {
    repoUrl = getRepoURL();
    commitSha = getCommitSha();
    step([
             $class            : 'GitHubCommitStatusSetter',
             reposSource       : [$class: "ManuallyEnteredRepositorySource", url: repoUrl],
             commitShaSource   : [$class: "ManuallyEnteredShaSource", sha: commitSha],
             errorHandlers     : [[$class: 'ShallowAnyErrorHandler']],
             statusResultSource: [$class: "ConditionalStatusResultSource", results: [[$class: "AnyBuildResult", message: message, state: state]]]
         ]);
}

pipeline {
    agent { label 'linux' }

    options {
        buildDiscarder(logRotator(artifactNumToKeepStr: '5', artifactDaysToKeepStr:'90'))
    }

    stages {
        stage('Setup OverlayFS') {
            steps {
                script {
                    if (getCurrentBranch() == 'main') {
                        sh "mkdir -p ${SHARED_WORKSPACE_PATH} ${BASE_PATH}/merged"
                        sh """
                            if ! mountpoint -q ${BASE_PATH}/merged; then
                        		sudo mount --bind ${SHARED_WORKSPACE_PATH} ${BASE_PATH}/merged
                            fi
                        """
                    } else {
                        sh "mkdir -p ${BASE_PATH}/upper ${BASE_PATH}/work ${BASE_PATH}/merged"
                        sh """
                            if ! mountpoint -q ${BASE_PATH}/merged; then
                                sudo mount -t overlay overlay -olowerdir=${SHARED_WORKSPACE_PATH},upperdir=${BASE_PATH}/upper,workdir=${BASE_PATH}/work ${BASE_PATH}/merged
                            fi
                        """
                    }
                }
            }
        }
        stage('Checkout') {
            steps {
                dir("${BASE_PATH}/merged") {
                    checkout scm
                }
            }
        }
        stage('Enable BETA channel') {
            steps {
                dir("${BASE_PATH}/merged") {
                    sh '''
                        mkdir -p patches-aosp/vendor || true
                        mkdir -p patches-aosp/vendor/tesla-android || true
                        cp -R jenkins/0001-CI-Disable-release-keys-switch-to-beta-channel.patch patches-aosp/vendor/tesla-android/0001-CI-Disable-release-keys-switch-to-beta-channel.patch
                    '''
                }
            }
        }
        stage('Unfold AOSP repo') {
            steps {
                dir("${BASE_PATH}/merged") {
                    sh './unfold_aosp.sh'
                }
            }
        }
        stage('Copy signing keys') {
            steps {
                dir("${BASE_PATH}/merged") {
                    sh 'cp -R /home/jenkins/tesla-android/signing aosptree/vendor/tesla-android/signing'
                }
            }
        }
        stage('Copy SSL certificates') {
            steps {
                dir("${BASE_PATH}/merged") {
                    sh 'cp -R /home/jenkins/tesla-android/certificates aosptree/vendor/tesla-android/services/lighttpd/certificates'
                }
            }
        }
        stage('Compile') {
            steps {
                dir("${BASE_PATH}/merged") {
                    sh './build.sh'
                }
            }
        }
        stage('Capture artifacts') {
            steps {
                script {
                    file = readFile("${BASE_PATH}/merged/aosptree/vendor/tesla-android/vendor.mk");
                    VERSION = getVersion(file);
                    ARTIFACT_NAME = 'TeslaAndroid-' + VERSION + '-CI-' + getCurrentBranch()  + '-' + getCommitSha() + '-BUILD-' + getBuildNumber() + '-radxa_zero'
                }
                dir("${BASE_PATH}/merged/out") {
                    sh """
                        mv tesla_android_radxa_zero-ota-${env.BUILD_NUMBER}.zip ${ARTIFACT_NAME}-OTA.zip
                        mv sdcard.img ${ARTIFACT_NAME}-single-image-installer.img
                        zip ${ARTIFACT_NAME}-single-image-installer.img.zip ${ARTIFACT_NAME}-single-image-installer.img
                    """
                    archiveArtifacts artifacts: "${ARTIFACT_NAME}-single-image-installer.img.zip", fingerprint: true
                    archiveArtifacts artifacts: "${ARTIFACT_NAME}-OTA.zip", fingerprint: true
                }
            }
        }
        stage('Remove artifacts') {
            steps {
                dir("${BASE_PATH}/merged/out") {
                    sh '''
                        rm -f *.img
                        rm -f *.zip
                    '''
                }
            }
        }
    }
    post {
	    success {
	        script {
	            setBuildStatus("Build succeeded", "SUCCESS");
	            	sh "sudo fuser -km ${BASE_PATH}/merged || true"
	                sh "sudo umount -l ${BASE_PATH}/merged"
	            if (getCurrentBranch() != 'main') {
	                sh "sudo rm -rf ${BASE_PATH}/upper ${BASE_PATH}/work"
	            }
	        }
	    }
	    failure {
	        script {
	            setBuildStatus("Build failed", "FAILURE");
	            sh "sudo fuser -km ${BASE_PATH}/merged || true"
	            if (getCurrentBranch() == 'main') {
	                sh "sudo umount -l ${BASE_PATH}/merged"
	                sh "sudo rm -rf ${SHARED_WORKSPACE_PATH}"
	            } else {
	                sh "sudo umount -l ${BASE_PATH}/merged"
	                sh "sudo rm -rf ${BASE_PATH}/upper ${BASE_PATH}/work"
	            }
	        }
	    }
      }
}
