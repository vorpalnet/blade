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

# Redirect each module's index.html to its root package-summary.html
# Javadoc already does this for single-package modules; do the same for multi-package ones.
for dir in "$DOCROOT"/*/; do
    [ -d "$dir" ] || continue
    idx="$dir/index.html"
    [ -f "$idx" ] || continue
    modname=$(basename "$dir")
    # Skip if already a redirect
    grep -q 'window.location.replace' "$idx" && continue
    # Find the root package-summary.html.
    # Priority: paths with services/test/applications > other vorpal paths > non-vorpal.
    # Within each tier, pick the shallowest path.
    all_pkgs=$(find "$dir" -name "package-summary.html")
    own_pkgs=$(echo "$all_pkgs" | grep -E 'services/|test/|applications/' || true)
    vorpal_pkgs=$(echo "$all_pkgs" | grep 'vorpal' || true)
    if [ -n "$own_pkgs" ]; then
        candidates="$own_pkgs"
    elif [ -n "$vorpal_pkgs" ]; then
        candidates="$vorpal_pkgs"
    else
        candidates="$all_pkgs"
    fi
    # Among candidates at the same depth, prefer the one whose path contains
    # the module name (e.g. "test-uac" module prefers path with "/uac/")
    modkey=$(echo "$modname" | sed 's/.*-//')
    match=$(echo "$candidates" | grep "/$modkey/" || true)
    if [ -n "$match" ]; then
        root=$(echo "$match" | awk -F/ '{print NF, $0}' | sort -n | head -1 | cut -d' ' -f2-)
    else
        root=$(echo "$candidates" | awk -F/ '{print NF, $0}' | sort -n | head -1 | cut -d' ' -f2-)
    fi
    [ -z "$root" ] && continue
    # Make path relative to the module directory
    relpath="${root#$dir}"
    echo "  Redirecting $modname/index.html -> $relpath"
    cat > "$idx" <<REDIRECT
<!DOCTYPE HTML>
<html lang>
<head>
<meta http-equiv="Content-Type" content="text/html; charset=UTF-8">
<meta name="dc.created" content="$(date +%Y-%m-%d)">
<meta http-equiv="Refresh" content="0;$relpath">
<script type="text/javascript">window.location.replace('$relpath')</script>
</head>
<body>
<p><a href="$relpath">$relpath</a></p>
</body>
</html>
REDIRECT
done

# --- Extract first sentence of a module's root package description ---
get_module_description() {
    local dir="$1"
    # Find the root package-summary.html (same logic as redirect step)
    local all_pkgs vorpal_pkgs own_pkgs candidates root
    all_pkgs=$(find "$dir" -name "package-summary.html" 2>/dev/null)
    own_pkgs=$(echo "$all_pkgs" | grep -E 'services/|test/|applications/' || true)
    vorpal_pkgs=$(echo "$all_pkgs" | grep 'vorpal' || true)
    if [ -n "$own_pkgs" ]; then candidates="$own_pkgs"
    elif [ -n "$vorpal_pkgs" ]; then candidates="$vorpal_pkgs"
    else candidates="$all_pkgs"; fi
    local modname modkey match
    modname=$(basename "$dir")
    modkey=$(echo "$modname" | sed 's/.*-//')
    match=$(echo "$candidates" | grep "/$modkey/" || true)
    if [ -n "$match" ]; then
        root=$(echo "$match" | awk -F/ '{print NF, $0}' | sort -n | head -1 | cut -d' ' -f2-)
    else
        root=$(echo "$candidates" | awk -F/ '{print NF, $0}' | sort -n | head -1 | cut -d' ' -f2-)
    fi
    [ -z "$root" ] && return
    # Extract first sentence from package-description block (strip HTML tags)
    sed -n '/<section class="package-description"/,/<\/section>/p' "$root" 2>/dev/null |
        sed -n '/<div class="block">/,/<\/div>/p' |
        sed 's/<[^>]*>//g' | tr '\n' ' ' | sed 's/  */ /g' |
        sed 's/^ *//' |
        # Take first sentence (up to first period followed by space or end)
        sed 's/\. .*/\./' |
        head -c 200
}

# --- Emit a module card ---
emit_module() {
    local modname="$1"
    local dir="$DOCROOT/$modname"
    local display desc
    display=$(echo "$modname" | sed 's/-/ /g' | awk '{for(i=1;i<=NF;i++) $i=toupper(substr($i,1,1)) substr($i,2)}1')
    desc=$(get_module_description "$dir")
    if [ -n "$desc" ]; then
        echo "        <li><a href=\"$modname/index.html\"><strong>$display</strong><span class=\"desc\">$desc</span></a></li>"
    else
        echo "        <li><a href=\"$modname/index.html\"><strong>$display</strong></a></li>"
    fi
}

# Generate index.html
cat > "$DOCROOT/index.html" <<HEADER
<!DOCTYPE html>
<html>
<head>
    <title>BLADE Javadoc - v${VERSION}</title>
    <meta name="theme-color" content="#602671">
    <meta name="color-scheme" content="light">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <style>
        * { box-sizing: border-box; }
        body { font-family: 'DejaVu Sans', 'Segoe UI', Arial, sans-serif; margin: 0; background: #f8f9fa; color: #333; }
        .header-bar { background: #602671; padding: 8px 16px; display: flex; align-items: center; gap: 16px; }
        .header-bar a.logo { display: flex; align-items: center; text-decoration: none; }
        .header-bar .version { color: rgba(255,255,255,0.7); font-size: 13px; }
        .header-bar nav { margin-left: auto; display: flex; gap: 20px; font-size: 14px; }
        .header-bar nav a { color: #fff; text-decoration: none; }
        .header-bar nav a:hover { text-decoration: underline; }
        .content { max-width: 960px; margin: 0 auto; padding: 32px 40px; }
        h1 { color: #333; font-size: 28px; margin-bottom: 4px; }
        .subtitle { color: #666; font-size: 15px; margin-bottom: 36px; }
        h2 { color: #602671; font-size: 16px; text-transform: uppercase; letter-spacing: 1px; margin: 32px 0 14px 0; padding-bottom: 6px; border-bottom: 2px solid #e0d4e6; }
        h2:first-of-type { margin-top: 0; }
        .module-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 12px; list-style: none; padding: 0; margin: 0 0 8px 0; }
        .module-grid li a { display: block; padding: 16px 20px; background: #fff; border: 1px solid #e0e0e0; border-radius: 8px; color: #602671; text-decoration: none; font-size: 15px; transition: box-shadow 0.15s, border-color 0.15s, transform 0.15s; }
        .module-grid li a:hover { border-color: #602671; box-shadow: 0 4px 12px rgba(96,38,113,0.15); transform: translateY(-1px); }
        .module-grid li a strong { display: block; font-size: 16px; margin-bottom: 4px; }
        .module-grid li a .desc { display: block; font-size: 12.5px; color: #666; line-height: 1.4; font-weight: normal; }
        footer { text-align: center; color: #999; font-size: 12px; margin-top: 48px; padding-bottom: 24px; }
        footer a { color: #666; }
    </style>
</head>
<body>
    <div class="header-bar">
        <a class="logo" href="https://vorpal.net">
            <img src="images/vorpal_logo_white.svg" height="50" alt="Vorpal">
        </a>
        <span class="version">v${VERSION}</span>
        <nav>
            <a href="https://vorpal.net/javadocs/">Documentation</a>
            <a href="https://github.com/vorpalnet/blade">GitHub</a>
        </nav>
    </div>
    <div class="content">
    <h1>BLADE API Reference</h1>
    <p class="subtitle">Version ${VERSION} &mdash; Blended Layer Application Development Environment</p>
HEADER

# --- Libraries ---
echo '    <h2>Libraries</h2>' >> "$DOCROOT/index.html"
echo '    <ul class="module-grid">' >> "$DOCROOT/index.html"
for mod in framework fsmar; do
    [ -d "$DOCROOT/$mod" ] && emit_module "$mod" >> "$DOCROOT/index.html"
done
echo '    </ul>' >> "$DOCROOT/index.html"

# --- Admin ---
echo '    <h2>Administration</h2>' >> "$DOCROOT/index.html"
echo '    <ul class="module-grid">' >> "$DOCROOT/index.html"
for mod in console configurator; do
    [ -d "$DOCROOT/$mod" ] && emit_module "$mod" >> "$DOCROOT/index.html"
done
echo '    </ul>' >> "$DOCROOT/index.html"

# --- Services ---
echo '    <h2>Services</h2>' >> "$DOCROOT/index.html"
echo '    <ul class="module-grid">' >> "$DOCROOT/index.html"
for dir in "$DOCROOT"/*/; do
    mod=$(basename "$dir")
    case "$mod" in
        framework|fsmar|console|configurator|test-*|images) continue ;;
    esac
    emit_module "$mod" >> "$DOCROOT/index.html"
done
echo '    </ul>' >> "$DOCROOT/index.html"

# --- Test ---
echo '    <h2>Test Applications</h2>' >> "$DOCROOT/index.html"
echo '    <ul class="module-grid">' >> "$DOCROOT/index.html"
for dir in "$DOCROOT"/test-*/; do
    [ -d "$dir" ] || continue
    mod=$(basename "$dir")
    emit_module "$mod" >> "$DOCROOT/index.html"
done
echo '    </ul>' >> "$DOCROOT/index.html"

cat >> "$DOCROOT/index.html" <<'FOOTER'
    <footer>
        Copyright 2025 <a href="https://vorpal.net">Vorpal Networks, LLC</a>. All Rights Reserved.<br>
        MIT License; Without warranty or liability of any kind.
    </footer>
    </div>
</body>
</html>
FOOTER

COUNT=$(find "$DOCROOT" -maxdepth 1 -type d ! -name "$(basename "$DOCROOT")" | wc -l | tr -d ' ')
echo "  Generated index.html with $COUNT modules"
