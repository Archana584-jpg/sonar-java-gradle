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
                    env.SAFE_BRANCH   = env.BRANCH_NAME.replaceAll('[^a-zA-Z0-9_-]', '-')
                    env.SONAR_PROJECT = "sonar-java-gradle-${env.SAFE_BRANCH}"
                    env.HOST_WORKSPACE = env.WORKSPACE.replace('/var/jenkins_home', env.JENKINS_HOME_HOST)

                    echo "Branch               : ${env.BRANCH_NAME}"
                    echo "Sonar Project        : ${env.SONAR_PROJECT}"
                    echo "Is Pull Request      : ${env.CHANGE_ID ? 'Yes (PR #' + env.CHANGE_ID + ' -> ' + env.CHANGE_TARGET + ')' : 'No'}"
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

                            # Define "New Code" as everything added since this
                            # project's first analysis (~30 day window covers
                            # a typical PR lifetime). This is what powers the
                            # New Code vs Overall Code split in reports below.
                            curl -s -u "${SONAR_ADMIN_TOKEN}:" -X POST \
                                "${SONAR_HOST_URL}/api/new_code_periods/set" \
                                -d "project=${SONAR_PROJECT}&type=NUMBER_OF_DAYS&value=30" \
                                || true
                        '''
                    }
                }
            }
        }

        stage('Test, Coverage & Sonar Scan') {
            steps {
                withSonarQubeEnv("${SONARQUBE_SERVER_NAME}") {
                    script {
                        env.SONAR_INCLUSIONS_ARG = ''
                        if (env.CHANGE_ID) {
                            sh "git fetch origin ${env.CHANGE_TARGET} || true"
                            def changedFiles = sh(
                                script: "git diff --name-only origin/${env.CHANGE_TARGET}...HEAD -- complete/ | sed 's|^complete/||' | tr '\\n' ',' | sed 's/,\$//'",
                                returnStdout: true
                            ).trim()
                            echo "Changed files (PR-scoped): ${changedFiles}"
                            if (changedFiles) {
                                env.SONAR_INCLUSIONS_ARG = "-Dsonar.inclusions=${changedFiles}"
                            } else {
                                echo "No changed files detected under complete/ — falling back to full scan."
                            }
                        } else {
                            echo "Not a PR build — running full scan (no inclusions filter)."
                        }
                    }
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
                                ./gradlew test jacocoTestReport sonar \
                                    -Dsonar.projectKey='"${SONAR_PROJECT}"' \
                                    -Dsonar.projectName='"${SONAR_PROJECT}"' \
                                    '"${SONAR_INCLUSIONS_ARG}"' \
                                    -Dsonar.host.url="$SONAR_HOST_URL" \
                                    -Dsonar.token="$SONAR_AUTH_TOKEN" \
                                    -Dsonar.coverage.jacoco.xmlReportPaths=build/reports/jacoco/test/jacocoTestReport.xml
                            '
                    '''
                }
            }
        }

        // NOTE: this stage only *records* the gate status — it deliberately
        // does not fail the build. The report must be fetched and the email
        // sent regardless of pass/fail, otherwise a failing PR would produce
        // no notification at all, which defeats the purpose.
        stage('Check Quality Gate Status') {
            steps {
                timeout(time: 10, unit: 'MINUTES') {
                    script {
                        env.QUALITY_GATE_STATUS = waitForQualityGate().status
                        echo "Quality Gate status: ${env.QUALITY_GATE_STATUS}"
                    }
                }
            }
        }

        stage('Fetch Report') {
            steps {
                withSonarQubeEnv("${SONARQUBE_SERVER_NAME}") {
                    sh '''
                        curl -s -u "${SONAR_AUTH_TOKEN}:" "${SONAR_HOST_URL}/api/measures/component?component=${SONAR_PROJECT}&metricKeys=bugs,vulnerabilities,code_smells,coverage,reliability_rating,security_rating,sqale_rating,new_bugs,new_vulnerabilities,new_code_smells,new_coverage,new_reliability_rating,new_security_rating,new_maintainability_rating" \
                            -o sonar-report.json
                        cat sonar-report.json
                        jq -r '.component.measures[] | "\\(.metric)=\\(.value // .period.value // "N/A")"' sonar-report.json > measures.properties
                        echo "----- measures.properties content -----"
                        cat measures.properties
                        echo "----------------------------------------"
                    '''
                    script {
                        def props = readFile('measures.properties').trim()
                        def measures = [:]
                        props.split('\n').each { line ->
                            if (line.contains('=')) {
                                def parts = line.split('=', 2)
                                measures[parts[0]] = parts[1]
                            }
                        }
                        env.REPORT_BUGS            = measures['bugs'] ?: 'N/A'
                        env.REPORT_VULNS           = measures['vulnerabilities'] ?: 'N/A'
                        env.REPORT_SMELLS          = measures['code_smells'] ?: 'N/A'
                        env.REPORT_COVERAGE        = measures['coverage'] ?: 'N/A'
                        env.REPORT_RELIABILITY     = measures['reliability_rating'] ?: 'N/A'
                        env.REPORT_SECURITY        = measures['security_rating'] ?: 'N/A'
                        env.REPORT_MAINTAINABILITY = measures['sqale_rating'] ?: 'N/A'

                        // New Code (this PR/branch only, since project creation)
                        env.NEW_BUGS            = measures['new_bugs'] ?: 'N/A'
                        env.NEW_VULNS           = measures['new_vulnerabilities'] ?: 'N/A'
                        env.NEW_SMELLS          = measures['new_code_smells'] ?: 'N/A'
                        env.NEW_COVERAGE        = measures['new_coverage'] ?: 'N/A'
                        env.NEW_RELIABILITY     = measures['new_reliability_rating'] ?: 'N/A'
                        env.NEW_SECURITY        = measures['new_security_rating'] ?: 'N/A'
                        env.NEW_MAINTAINABILITY = measures['new_maintainability_rating'] ?: 'N/A'

                        // Capture this now while still inside withSonarQubeEnv —
                        // SONAR_HOST_URL isn't accessible outside that block's scope.
                        env.SONAR_DASHBOARD_URL = "${SONAR_HOST_URL}/dashboard?id=${env.SONAR_PROJECT}"
                    }
                }
                archiveArtifacts artifacts: 'sonar-report.json', allowEmptyArchive: true
            }
        }

        stage('Notify') {
            steps {
                script {
                    def isPR         = env.CHANGE_ID != null
                    def gatePassed   = (env.QUALITY_GATE_STATUS == 'OK')
                    def verdictText  = gatePassed ? '✅ SAFE TO MERGE' : '❌ DO NOT MERGE'
                    def verdictColor = gatePassed ? '#1a7f37' : '#cf222e'

                    def prInfoRows = isPR ? """
                        <tr><td><b>Pull Request</b></td><td>#${env.CHANGE_ID}: ${env.CHANGE_TITLE ?: ''}</td></tr>
                        <tr><td><b>Source → Target</b></td><td>${env.CHANGE_BRANCH} → ${env.CHANGE_TARGET}</td></tr>
                        <tr><td><b>PR Link</b></td><td><a href="${env.CHANGE_URL}">${env.CHANGE_URL}</a></td></tr>
                    """ : """
                        <tr><td><b>Branch</b></td><td>${env.BRANCH_NAME}</td></tr>
                    """

                    def subjectPrefix = isPR ? "PR #${env.CHANGE_ID}" : env.BRANCH_NAME

                    emailext(
                        to: "${RECIPIENTS}",
                        subject: "${gatePassed ? '✅' : '❌'} ${subjectPrefix} — ${env.SONAR_PROJECT} — ${verdictText}",
                        mimeType: 'text/html',
                        body: """
                            <h2 style="color:${verdictColor};">${verdictText}</h2>
                            <h3>SonarQube Scan Report</h3>
                            <table cellpadding="6" style="border-collapse:collapse;">
                              ${prInfoRows}
                              <tr><td><b>Sonar Project</b></td><td>${env.SONAR_PROJECT}</td></tr>
                              <tr><td><b>Quality Gate</b></td><td>${env.QUALITY_GATE_STATUS}</td></tr>
                            </table>

                            <h3 style="margin-top:24px;">New Code (this PR)</h3>
                            <table cellpadding="6" style="border-collapse:collapse;">
                              <tr><td><b>Bugs</b></td><td>${env.NEW_BUGS}</td></tr>
                              <tr><td><b>Vulnerabilities</b></td><td>${env.NEW_VULNS}</td></tr>
                              <tr><td><b>Code Smells</b></td><td>${env.NEW_SMELLS}</td></tr>
                              <tr><td><b>Coverage</b></td><td>${env.NEW_COVERAGE}%</td></tr>
                              <tr><td><b>Reliability Rating</b></td><td>${env.NEW_RELIABILITY}</td></tr>
                              <tr><td><b>Security Rating</b></td><td>${env.NEW_SECURITY}</td></tr>
                              <tr><td><b>Maintainability Rating</b></td><td>${env.NEW_MAINTAINABILITY}</td></tr>
                            </table>

                            <h3 style="margin-top:24px;">Full Project (Old + New Code)</h3>
                            <table cellpadding="6" style="border-collapse:collapse;">
                              <tr><td><b>Bugs</b></td><td>${env.REPORT_BUGS}</td></tr>
                              <tr><td><b>Vulnerabilities</b></td><td>${env.REPORT_VULNS}</td></tr>
                              <tr><td><b>Code Smells</b></td><td>${env.REPORT_SMELLS}</td></tr>
                              <tr><td><b>Coverage</b></td><td>${env.REPORT_COVERAGE}%</td></tr>
                              <tr><td><b>Reliability Rating</b></td><td>${env.REPORT_RELIABILITY}</td></tr>
                              <tr><td><b>Security Rating</b></td><td>${env.REPORT_SECURITY}</td></tr>
                              <tr><td><b>Maintainability Rating</b></td><td>${env.REPORT_MAINTAINABILITY}</td></tr>
                              <tr><td><b>SonarQube Dashboard</b></td><td><a href="${env.SONAR_DASHBOARD_URL}">View full report</a></td></tr>
                              <tr><td><b>Jenkins Build</b></td><td><a href="${env.BUILD_URL}">${env.BUILD_URL}</a></td></tr>
                            </table>
                        """
                    )

                    if (isPR) {
                        githubNotify context: 'sonarqube/quality-gate',
                            description: "Quality Gate: ${env.QUALITY_GATE_STATUS} — ${verdictText}",
                            status: (gatePassed ? 'SUCCESS' : 'FAILURE'),
                            targetUrl: env.BUILD_URL
                    }
                }
            }
        }

        stage('Enforce Quality Gate') {
            steps {
                script {
                    if (env.QUALITY_GATE_STATUS != 'OK') {
                        error("Quality Gate failed with status: ${env.QUALITY_GATE_STATUS}. See emailed report for details.")
                    }
                }
            }
        }
    }

    post {
        always {
            cleanWs()
        }
    }
}
