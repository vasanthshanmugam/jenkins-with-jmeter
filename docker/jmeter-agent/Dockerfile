# Dedicated Linux JMeter agent (inbound/WebSocket agent) - used in the "move load off the
# controller" step of the lab (docs/11). Same JMeter version and JMETER_HOME as the controller image.
FROM jenkins/inbound-agent:3391.va_37fa_a_305d6d-5-jdk21

ARG JMETER_VERSION=5.6.3
ARG JMETER_SHA512=5978a1a35edb5a7d428e270564ff49d2b1b257a65e17a759d259a9283fc17093e522fe46f474a043864aea6910683486340706d745fcdf3db1505fd71e689083

USER root
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates \
 && rm -rf /var/lib/apt/lists/* \
 && curl -fsSL -o /tmp/jmeter.tgz \
      "https://archive.apache.org/dist/jmeter/binaries/apache-jmeter-${JMETER_VERSION}.tgz" \
 && echo "${JMETER_SHA512}  /tmp/jmeter.tgz" | sha512sum -c - \
 && tar -xzf /tmp/jmeter.tgz -C /opt \
 && rm /tmp/jmeter.tgz \
 && chown -R jenkins:jenkins /opt/apache-jmeter-${JMETER_VERSION}

ENV JMETER_HOME=/opt/apache-jmeter-${JMETER_VERSION}
ENV PATH="${JMETER_HOME}/bin:${PATH}"
ENV HEAP="-Xms1g -Xmx2g -XX:MaxMetaspaceSize=256m"

USER jenkins
