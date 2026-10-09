# Jenkins controller + Apache JMeter (lab image)
# Jenkins LTS 2.580.1 on JDK 21 (multi-arch: works on Apple Silicon and Intel Macs).
FROM jenkins/jenkins:2.580.1-lts-jdk21

ARG JMETER_VERSION=5.6.3
ARG JMETER_SHA512=5978a1a35edb5a7d428e270564ff49d2b1b257a65e17a759d259a9283fc17093e522fe46f474a043864aea6910683486340706d745fcdf3db1505fd71e689083

USER root

# curl is used by the pipeline's target health check; git is already in the base image.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates \
 && rm -rf /var/lib/apt/lists/*

# Install JMeter from the permanent Apache archive and verify its checksum.
RUN curl -fsSL -o /tmp/jmeter.tgz \
      "https://archive.apache.org/dist/jmeter/binaries/apache-jmeter-${JMETER_VERSION}.tgz" \
 && echo "${JMETER_SHA512}  /tmp/jmeter.tgz" | sha512sum -c - \
 && tar -xzf /tmp/jmeter.tgz -C /opt \
 && rm /tmp/jmeter.tgz \
 && chown -R jenkins:jenkins /opt/apache-jmeter-${JMETER_VERSION}

# JMETER_HOME is what the Jenkinsfile reads; it is never hard-coded in the pipeline.
ENV JMETER_HOME=/opt/apache-jmeter-${JMETER_VERSION}
ENV PATH="${JMETER_HOME}/bin:${PATH}"
# JMeter heap inside the container (read by bin/jmeter). Keep below the Docker memory limit.
ENV HEAP="-Xms512m -Xmx1g -XX:MaxMetaspaceSize=256m"

USER jenkins

# Pre-install the plugins the lab needs (the setup wizard still runs on first start).
COPY --chown=jenkins:jenkins plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt
