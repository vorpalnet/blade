#!/usr/bin/env bash
# Collects javadocs from all sibling modules and generates an index.html
# Usage: collect-javadocs.sh <project-root> <output-dir> <version>

set -euo pipefail

PARENT="$1"
DOCROOT="$2"
VERSION="$3"

mkdir -p "$DOCROOT"

# Collect javadocs from all sibling modules
# Check both possible output locations (reports/apidocs and site/apidocs)
for moddir in "$PARENT"/*/*/; do
    [ -d "$moddir" ] || continue
    modname=$(basename "$moddir")
    [ ! -f "$moddir/pom.xml" ] && continue

    apidocs=""
    if [ -d "$moddir/target/reports/apidocs" ]; then
        apidocs="$moddir/target/reports/apidocs"
    elif [ -d "$moddir/target/site/apidocs" ]; then
        apidocs="$moddir/target/site/apidocs"
    fi

    if [ -n "$apidocs" ]; then
        echo "  Collecting javadoc: $modname"
        mkdir -p "$DOCROOT/$modname"
        cp -r "$apidocs/"* "$DOCROOT/$modname/"
    fi
done

# Generate index.html
cat > "$DOCROOT/index.html" <<HEADER
<!DOCTYPE html>
<html>
<head>
    <title>BLADE Javadoc</title>
    <style>
        body { font-family: 'Segoe UI', Arial, sans-serif; margin: 0; background: #f5f5f5; }
        h1 { color: #333; border-bottom: 2px solid #4a90d9; padding-bottom: 10px; }
        p.version { color: #888; font-size: 14px; }
        h2 { color: #555; margin-top: 30px; }
        ul { list-style: none; padding: 0; }
        li { margin: 8px 0; }
        a { color: #4a90d9; text-decoration: none; font-size: 16px; }
        a:hover { text-decoration: underline; }
    </style>
</head>
<body>
    <div style="background:#602671; padding:10px 16px; margin-bottom:16px;">
        <img src="images/vorpal_logo_white.svg" height="50" alt="Vorpal">
    </div>
    <div style="padding: 0 40px;">
    <h1>BLADE Javadoc</h1>
    <p class="version">Version: ${VERSION}</p>
    <h2>Modules</h2>
    <ul>
HEADER

# Add a link for each module that has javadocs
for dir in "$DOCROOT"/*/; do
    [ -d "$dir" ] || continue
    modname=$(basename "$dir")
    # Create display name: replace hyphens with spaces, title case
    display=$(echo "$modname" | sed 's/-/ /g' | awk '{for(i=1;i<=NF;i++) $i=toupper(substr($i,1,1)) substr($i,2)}1')
    echo "        <li><a href=\"$modname/index.html\">$display</a></li>" >> "$DOCROOT/index.html"
done

cat >> "$DOCROOT/index.html" <<'FOOTER'
    </ul>
    </div>
</body>
</html>
FOOTER

COUNT=$(find "$DOCROOT" -maxdepth 1 -type d ! -name "$(basename "$DOCROOT")" | wc -l | tr -d ' ')
echo "  Generated index.html with $COUNT modules"
