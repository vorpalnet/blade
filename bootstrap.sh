#!/bin/bash
#
# Install OCCAS JARs into the local Maven repository.
# Run this once before building with Maven.
#
# Usage: ./bootstrap.sh /path/to/occas
#
# Supports OCCAS 8.0, 8.1, 8.2, and 8.3 (and forward-compatible with later
# versions). Auto-detects OCCAS and WebLogic versions from the install's
# inventory/registry.xml. Override by exporting OCCAS_VERSION or WL_VERSION.
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

PLUGIN_BASE="$OCCAS_HOME/wlserver/plugins/maven/com/oracle/weblogic/weblogic-maven-plugin"

# --- Auto-detect OCCAS and WebLogic versions from inventory/registry.xml ---
# Pre-set OCCAS_VERSION / WL_VERSION env vars are honored as overrides.
REGISTRY="$OCCAS_HOME/inventory/registry.xml"
if [ -f "$REGISTRY" ]; then
    if [ -z "$OCCAS_VERSION" ]; then
        OCCAS_VERSION=$(grep -oE 'name="Converged Application Server" version="[0-9]+\.[0-9]+' "$REGISTRY" \
                        | grep -oE '[0-9]+\.[0-9]+$' | head -1)
    fi
    if [ -z "$WL_VERSION" ]; then
        WL_VERSION=$(grep -oE 'name="cieCfg_wls_shared_external" version="[0-9]+\.[0-9]+\.[0-9]+' "$REGISTRY" \
                     | grep -oE '[0-9]+\.[0-9]+\.[0-9]+$' | head -1)
    fi
fi

# Fall back to heuristics if registry.xml is missing or did not yield a version.
if [ -z "$OCCAS_VERSION" ]; then
    echo "Warning: could not read OCCAS version from $REGISTRY; falling back to directory name."
    OCCAS_VERSION=$(basename "$OCCAS_HOME" | grep -oE '[0-9]+\.[0-9]+' | head -1)
    OCCAS_VERSION="${OCCAS_VERSION:-8.1}"
fi
if [ -z "$WL_VERSION" ]; then
    echo "Warning: could not read WebLogic version from $REGISTRY; falling back to plugin directory."
    if [ -d "$PLUGIN_BASE" ]; then
        WL_VERSION=$(ls -1 "$PLUGIN_BASE" | head -1)
    else
        WL_VERSION="14.1.1"
    fi
fi

echo "Installing OCCAS JARs from: $OCCAS_HOME"
echo "  WebLogic version: $WL_VERSION"
echo "  OCCAS version:    $OCCAS_VERSION"
echo ""

# javaee-api filename: javax.javaee-api.jar in 8.0/8.1; javaee-api-<ver>.jar in 8.2+
JAVAEE_JAR="$OCCAS_HOME/wlserver/server/lib/javax.javaee-api.jar"
if [ ! -f "$JAVAEE_JAR" ]; then
    JAVAEE_JAR=$(ls -1 "$OCCAS_HOME/wlserver/server/lib/"javaee-api-*.jar 2>/dev/null | head -1)
fi
if [ -z "$JAVAEE_JAR" ] || [ ! -f "$JAVAEE_JAR" ]; then
    echo "Error: Cannot find javaee-api JAR in $OCCAS_HOME/wlserver/server/lib/"
    exit 1
fi

# --- Pre-flight: verify all source JARs exist before any mvn invocations ---
declare -a SRC_JARS=(
    "$JAVAEE_JAR"
    "$OCCAS_HOME/wlserver/server/lib/weblogic.jar"
    "$OCCAS_HOME/wlserver/modules/com.oracle.weblogic.logging.jar"
    "$OCCAS_HOME/wlserver/modules/com.oracle.weblogic.security.encryption.jar"
    "$OCCAS_HOME/wlserver/sip/server/lib/sipservlet-api.jar"
    "$OCCAS_HOME/wlserver/sip/server/lib/wlss.jar"
    "$OCCAS_HOME/wlserver/sip/server/lib/wlssapi.jar"
    "$PLUGIN_BASE/$WL_VERSION/weblogic-maven-plugin-$WL_VERSION.jar"
)
missing=()
for f in "${SRC_JARS[@]}"; do [ -f "$f" ] || missing+=("$f"); done
if [ ${#missing[@]} -gt 0 ]; then
    echo "Error: required JARs not found in $OCCAS_HOME:"
    printf '  %s\n' "${missing[@]}"
    exit 1
fi

mvn install:install-file \
    -DgroupId=javax \
    -DartifactId=javaee-api \
    -Dversion=8.0-occas \
    -Dpackaging=jar \
    -Dfile="$JAVAEE_JAR" \
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
    -DartifactId=weblogic-security-encryption \
    -Dversion="$WL_VERSION" \
    -Dpackaging=jar \
    -Dfile="$OCCAS_HOME/wlserver/modules/com.oracle.weblogic.security.encryption.jar"

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
