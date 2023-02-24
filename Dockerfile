FROM openjdk:11-jre-slim

RUN apt-get update && apt-get install -y curl
COPY target/uberjar/onkalo.jar /srv/onkalo.jar
ADD config.edn /srv/config.edn
ADD ./entrypoint.sh /srv/entrypoint.sh

EXPOSE 8012
WORKDIR /srv
ENTRYPOINT ["/srv/entrypoint.sh"]
