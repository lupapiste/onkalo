#!/bin/bash

# Exit when any command fails
set -eu

# Copy env specific config.edn in use, mounted from kubernetes secret to pod
yes | cp -f /etc/config/config.edn /srv
 
java -XX:MaxRAMPercentage=80.0 -server -XX:+UseG1GC -XX:MaxMetaspaceSize=256m -jar /srv/onkalo.jar
