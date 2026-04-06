#!/bin/bash
#
# Install OCCAS JARs into the local Maven repository.
# Run this once before building with Maven.
#
# Usage: ./install-occas.sh /path/to/occas
#
# Supports OCCAS 8.0, 8.1, 8.2, and 8.3. Auto-detects the WebLogic
# version from the installed maven plugin directory.
#

OCCAS_HOME="${1:-$OCCAS}"

if [ -z "$OCCAS_HOME" ]; then
    echo "Usage: $0 /path/to/occas"
    echo "   or: export OCCAS=/path/to/occas && $0"
    exit 1
fi

if [ ! -d "$OCCAS_HOME/wlserver" ]; then
    echo "Error: $OCCAS_HOME/wlserver not found. Is this a valid OCCAS installation?"
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# --- Auto-detect WebLogic version from the maven plugin directory ---
PLUGIN_BASE="$OCCAS_HOME/wlserver/plugins/maven/com/oracle/weblogic/weblogic-maven-plugin"
if [ -d "$PLUGIN_BASE" ]; then
    WL_VERSION=$(ls -1 "$PLUGIN_BASE" | head -1)
else
    echo "Warning: weblogic-maven-plugin not found at $PLUGIN_BASE"
    echo "         Defaulting to 14.1.1 — set WL_VERSION manually if needed."
    WL_VERSION="14.1.1"
fi

# --- Auto-detect OCCAS SIP version from the sipservlet-api jar ---
if [ -f "$OCCAS_HOME/wlserver/sip/server/lib/sipservlet-api.jar" ]; then
    # Derive OCCAS version from the directory name (e.g. occas-8.1 -> 8.1)
    OCCAS_DIR=$(basename "$OCCAS_HOME")
    OCCAS_VERSION=$(echo "$OCCAS_DIR" | grep -oE '[0-9]+\.[0-9]+' | head -1)
    OCCAS_VERSION="${OCCAS_VERSION:-8.1}"
else
    OCCAS_VERSION="8.1"
fi

echo "Installing OCCAS JARs from: $OCCAS_HOME"
echo "  WebLogic version: $WL_VERSION"
echo "  OCCAS version:    $OCCAS_VERSION"
echo ""

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
    -Dversion="$WL_VERSION" \
    -Dpackaging=jar \
    -Dfile="$OCCAS_HOME/wlserver/server/lib/weblogic.jar"

mvn install:install-file \
    -DgroupId=com.oracle.weblogic \
    -DartifactId=weblogic-logging \
    -Dversion="$WL_VERSION" \
    -Dpackaging=jar \
    -Dfile="$OCCAS_HOME/wlserver/modules/com.oracle.weblogic.logging.jar"

mvn install:install-file \
    -DgroupId=com.oracle.occas \
    -DartifactId=sipservlet-api \
    -Dversion="$OCCAS_VERSION" \
    -Dpackaging=jar \
    -Dfile="$OCCAS_HOME/wlserver/sip/server/lib/sipservlet-api.jar"

mvn install:install-file \
    -DgroupId=com.oracle.occas \
    -DartifactId=wlss \
    -Dversion="$OCCAS_VERSION" \
    -Dpackaging=jar \
    -Dfile="$OCCAS_HOME/wlserver/sip/server/lib/wlss.jar"

mvn install:install-file \
    -DgroupId=com.oracle.occas \
    -DartifactId=wlssapi \
    -Dversion="$OCCAS_VERSION" \
    -Dpackaging=jar \
    -Dfile="$OCCAS_HOME/wlserver/sip/server/lib/wlssapi.jar"

mvn install:install-file \
    -DgroupId=com.oracle.weblogic \
    -DartifactId=weblogic-maven-plugin \
    -Dversion="$WL_VERSION" \
    -Dpackaging=maven-plugin \
    -Dfile="$PLUGIN_BASE/$WL_VERSION/weblogic-maven-plugin-$WL_VERSION.jar" \
    -DpomFile="$PLUGIN_BASE/$WL_VERSION/weblogic-maven-plugin-$WL_VERSION.pom"

echo ""
echo "Done. OCCAS JARs and plugins installed to local Maven repository."
echo "  WebLogic: com.oracle.weblogic:*:$WL_VERSION"
echo "  OCCAS:    com.oracle.occas:*:$OCCAS_VERSION"
