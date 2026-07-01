pipeline {

    agent any

    environment {
        REPO_OWNER   = 'Archana584-jpg'
        REPO_NAME    = 'sonar-java-gradle'
        APP_DIR      = 'complete'   // change to 'initial' if that's what you're using

        DOCKER_IMAGE = 'sonar-java-gradle-builder:latest'

        SONARQUBE_SERVER_NAME = 'sonarqube'   // must match Manage Jenkins > SonarQube servers
        SONAR_URL_CREDENTIAL  = 'sonar-token' // Sonar token w/ project admin rights (create/delete)

        RECIPIENTS = 'archana.rana@ongrid.in'

        // Real host-side path corresponding to /var/jenkins_home inside the
        // Jenkins container. Confirm via:
        //   docker inspect jenkins --format '{{json .Mounts}}'
        // and update this if it differs.
        JENKINS_HOME_HOST = '/var/lib/docker/volumes/jenkins_jenkins_home/_data'
    }

    stages {

        stage('Checkout') {
            steps {
                checkout scm
                script {
                    // Sanitize branch name into a safe Sonar project key suffix
                    env.SAFE_BRANCH   = env.BRANCH_NAME.replaceAll('[^a-zA-Z0-9_-]', '-')
                    env.SONAR_PROJECT = "sonar-java-gradle-${env.SAFE_BRANCH}"

                    // Translate the in-container workspace path to the real
                    // host-side path, since "docker run" here talks to the
                    // HOST's daemon (DooD) — $(pwd) inside this container
                    // would be meaningless to it.
                    env.HOST_WORKSPACE = env.WORKSPACE.replace('/var/jenkins_home', env.JENKINS_HOME_HOST)

                    echo "Branch               : ${env.BRANCH_NAME}"
                    echo "Sonar Project        : ${env.SONAR_PROJECT}"
                    echo "Container WORKSPACE  : ${env.WORKSPACE}"
                    echo "Host-side WORKSPACE  : ${env.HOST_WORKSPACE}"
                }
            }
        }

        stage('Build Docker Image') {
            steps {
                sh '''
                    docker build -t ${DOCKER_IMAGE} .
                '''
            }
        }

        stage('Ensure Sonar Project Exists') {
            steps {
                withSonarQubeEnv("${SONARQUBE_SERVER_NAME}") {
                    withCredentials([string(credentialsId: "${SONAR_URL_CREDENTIAL}", variable: 'SONAR_ADMIN_TOKEN')]) {
                        sh '''
                            curl -s -u "${SONAR_ADMIN_TOKEN}:" -X POST \
                                "${SONAR_HOST_URL}/api/projects/create" \
                                -d "project=${SONAR_PROJECT}&name=${SONAR_PROJECT}" \
                                || true
                            # "|| true" because this returns an error if the project
                            # already exists — that's expected on every build after the first.
                        '''
                    }
                }
            }
        }

        stage('Test, Coverage & Sonar Scan') {
            steps {
                withSonarQubeEnv("${SONARQUBE_SERVER_NAME}") {
                    sh '''
                        docker run --rm \
                            -v "${HOST_WORKSPACE}":/workspace \
                            -w /workspace/${APP_DIR} \
                            -e SONAR_HOST_URL="${SONAR_HOST_URL}" \
                            -e SONAR_AUTH_TOKEN="${SONAR_AUTH_TOKEN}" \
                            ${DOCKER_IMAGE} \
                            bash -c '
                                set -e
                                chmod +x gradlew
                                ./gradlew test jacocoTestReport sonarqube \
                                    -Dsonar.projectKey='"${SONAR_PROJECT}"' \
                                    -Dsonar.host.url="$SONAR_HOST_URL" \
                                    -Dsonar.login="$SONAR_AUTH_TOKEN" \
                                    -Dsonar.coverage.jacoco.xmlReportPaths=build/reports/jacoco/test/jacocoTestReport.xml
                            '
                    '''
                }
            }
        }

        stage('Quality Gate') {
            steps {
                timeout(time: 10, unit: 'MINUTES') {
                    script {
                        env.QUALITY_GATE_STATUS = waitForQualityGate().status
                        echo "Quality Gate status: ${env.QUALITY_GATE_STATUS}"
                    }
                }
            }
        }

        stage('Fetch Report & Notify') {
            steps {
                withSonarQubeEnv("${SONARQUBE_SERVER_NAME}") {
                    sh '''
                        curl -s "${SONAR_HOST_URL}/api/measures/component?component=${SONAR_PROJECT}&metricKeys=bugs,vulnerabilities,code_smells,coverage,reliability_rating,security_rating,sqale_rating" \
                            -o sonar-report.json
                        cat sonar-report.json | jq . || cat sonar-report.json
                    '''
                    script {
                        def report = readJSON file: 'sonar-report.json'
                        def measures = [:]
                        report.component.measures.each { m -> measures[m.metric] = m.value }

                        env.REPORT_BUGS          = measures['bugs'] ?: 'N/A'
                        env.REPORT_VULNS         = measures['vulnerabilities'] ?: 'N/A'
                        env.REPORT_SMELLS        = measures['code_smells'] ?: 'N/A'
                        env.REPORT_COVERAGE      = measures['coverage'] ?: 'N/A'
                        env.REPORT_RELIABILITY   = measures['reliability_rating'] ?: 'N/A'
                        env.REPORT_SECURITY      = measures['security_rating'] ?: 'N/A'
                        env.REPORT_MAINTAINABILITY = measures['sqale_rating'] ?: 'N/A'
                    }
                }
                archiveArtifacts artifacts: 'sonar-report.json', allowEmptyArchive: true
            }
        }
    }

    post {

        success {
            script {
                if (env.CHANGE_ID) {
                    githubNotify context: 'sonarqube/quality-gate',
                        description: "Quality Gate: ${env.QUALITY_GATE_STATUS}",
                        status: (env.QUALITY_GATE_STATUS == 'OK' ? 'SUCCESS' : 'FAILURE'),
                        targetUrl: env.BUILD_URL
                }
            }
            emailext(
                to: "${RECIPIENTS}",
                subject: "SonarQube Report — ${env.SONAR_PROJECT} (${env.QUALITY_GATE_STATUS})",
                mimeType: 'text/html',
                body: """
                    <h3>SonarQube Scan Report</h3>
                    <table cellpadding="6" style="border-collapse:collapse;">
                      <tr><td><b>Branch</b></td><td>${env.BRANCH_NAME}</td></tr>
                      <tr><td><b>Sonar Project</b></td><td>${env.SONAR_PROJECT}</td></tr>
                      <tr><td><b>Quality Gate</b></td><td>${env.QUALITY_GATE_STATUS}</td></tr>
                      <tr><td><b>Bugs</b></td><td>${env.REPORT_BUGS}</td></tr>
                      <tr><td><b>Vulnerabilities</b></td><td>${env.REPORT_VULNS}</td></tr>
                      <tr><td><b>Code Smells</b></td><td>${env.REPORT_SMELLS}</td></tr>
                      <tr><td><b>Coverage</b></td><td>${env.REPORT_COVERAGE}%</td></tr>
                      <tr><td><b>Reliability Rating</b></td><td>${env.REPORT_RELIABILITY}</td></tr>
                      <tr><td><b>Security Rating</b></td><td>${env.REPORT_SECURITY}</td></tr>
                      <tr><td><b>Maintainability Rating</b></td><td>${env.REPORT_MAINTAINABILITY}</td></tr>
                      <tr><td><b>Build</b></td><td><a href="${env.BUILD_URL}">${env.BUILD_URL}</a></td></tr>
                    </table>
                """
            )
        }

        failure {
            script {
                if (env.CHANGE_ID) {
                    githubNotify context: 'sonarqube/quality-gate',
                        description: 'Build or Quality Gate failed',
                        status: 'FAILURE',
                        targetUrl: env.BUILD_URL
                }
            }
            emailext(
                to: "${RECIPIENTS}",
                subject: "❌ SonarQube Scan Failed — ${env.SONAR_PROJECT}",
                mimeType: 'text/html',
                body: """
                    <h3 style="color:#cf222e;">Scan Failed</h3>
                    <p>Branch: ${env.BRANCH_NAME}</p>
                    <p>Check console log: <a href="${env.BUILD_URL}console">${env.BUILD_URL}console</a></p>
                """
            )
        }

        always {
            cleanWs()
        }
    }
}
