#!/bin/bash
#
# Install OCCAS JARs into the local Maven repository.
# Run this once before building with Maven.
#
# Usage: ./install-occas.sh /path/to/occas-8.1
#

OCCAS_HOME="${1:-$OCCAS}"

if [ -z "$OCCAS_HOME" ]; then
    echo "Usage: $0 /path/to/occas-8.1"
    echo "   or: export OCCAS=/path/to/occas-8.1 && $0"
    exit 1
fi

if [ ! -d "$OCCAS_HOME/wlserver" ]; then
    echo "Error: $OCCAS_HOME/wlserver not found. Is this a valid OCCAS installation?"
    exit 1
fi

echo "Installing OCCAS JARs from: $OCCAS_HOME"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

mvn install:install-file \
    -DgroupId=javax \
    -DartifactId=javaee-api \
    -Dversion=8.0-occas \
    -Dpackaging=jar \
    -Dfile="$OCCAS_HOME/wlserver/server/lib/javax.javaee-api.jar" \
    -DpomFile="$SCRIPT_DIR/javaee-api.pom"

mvn install:install-file \
    -DgroupId=com.oracle.weblogic \
    -DartifactId=weblogic-server \
    -Dversion=14.1.1 \
    -Dpackaging=jar \
    -Dfile="$OCCAS_HOME/wlserver/server/lib/weblogic.jar"

mvn install:install-file \
    -DgroupId=com.oracle.weblogic \
    -DartifactId=weblogic-logging \
    -Dversion=14.1.1 \
    -Dpackaging=jar \
    -Dfile="$OCCAS_HOME/wlserver/modules/com.oracle.weblogic.logging.jar"

mvn install:install-file \
    -DgroupId=com.oracle.occas \
    -DartifactId=sipservlet-api \
    -Dversion=8.1 \
    -Dpackaging=jar \
    -Dfile="$OCCAS_HOME/wlserver/sip/server/lib/sipservlet-api.jar"

mvn install:install-file \
    -DgroupId=com.oracle.occas \
    -DartifactId=wlss \
    -Dversion=8.1 \
    -Dpackaging=jar \
    -Dfile="$OCCAS_HOME/wlserver/sip/server/lib/wlss.jar"

mvn install:install-file \
    -DgroupId=com.oracle.occas \
    -DartifactId=wlssapi \
    -Dversion=8.1 \
    -Dpackaging=jar \
    -Dfile="$OCCAS_HOME/wlserver/sip/server/lib/wlssapi.jar"

mvn install:install-file \
    -DgroupId=com.oracle.weblogic \
    -DartifactId=weblogic-maven-plugin \
    -Dversion=14.1.1 \
    -Dpackaging=maven-plugin \
    -Dfile="$OCCAS_HOME/wlserver/plugins/maven/com/oracle/weblogic/weblogic-maven-plugin/14.1.1/weblogic-maven-plugin-14.1.1.jar" \
    -DpomFile="$OCCAS_HOME/wlserver/plugins/maven/com/oracle/weblogic/weblogic-maven-plugin/14.1.1/weblogic-maven-plugin-14.1.1.pom"

echo "Done. OCCAS JARs and plugins installed to local Maven repository."
