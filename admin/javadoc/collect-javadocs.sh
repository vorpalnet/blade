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

# --- Emit a module card (shared brand layer's vorpal-card) ---
emit_module() {
    local modname="$1"
    local dir="$DOCROOT/$modname"
    local display desc
    display=$(echo "$modname" | sed 's/-/ /g' | awk '{for(i=1;i<=NF;i++) $i=toupper(substr($i,1,1)) substr($i,2)}1')
    desc=$(get_module_description "$dir")
    if [ -n "$desc" ]; then
        echo "                <a class=\"vorpal-card\" href=\"$modname/index.html\"><h3>$display</h3><p>$desc</p><span class=\"vorpal-card-link\">Open</span></a>"
    else
        echo "                <a class=\"vorpal-card\" href=\"$modname/index.html\"><h3>$display</h3><span class=\"vorpal-card-link\">Open</span></a>"
    fi
}

# Generate index.html — same chrome as the Admin Portal (shared brand.css,
# served public from /blade/portal/brand/). White topbar with the Vorpal mark,
# light hero, vorpal-card deck.
cat > "$DOCROOT/index.html" <<HEADER
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <meta name="theme-color" content="#602671">
    <title>BLADE Javadoc &middot; v${VERSION}</title>
    <link rel="stylesheet" href="/blade/portal/brand/brand.css">
    <link rel="icon" type="image/svg+xml" href="/blade/javadoc/favicon.svg">
    <style>
        /* Section labels — brand.css has no section-header primitive. */
        .jd-section { margin: 40px 0 16px; padding-bottom: 8px;
            border-bottom: 1px solid var(--vorpal-divider);
            color: var(--vorpal-slate-600); font-size: 13px; font-weight: 600;
            text-transform: uppercase; letter-spacing: 0.1em; }
        .jd-section:first-of-type { margin-top: 8px; }
    </style>
</head>
<body>
<div class="vorpal-app">

    <header class="vorpal-topbar">
        <a class="vorpal-brand" href="/blade/portal/" title="Return to portal.">
            <img class="vorpal-brand-mark" src="/blade/portal/brand/vorpal_splotch.svg" alt="Vorpal">
            <span class="vorpal-brand-text">
                <span class="vorpal-brand-product">BLADE Javadoc</span>
                <span class="vorpal-brand-tagline">API Reference</span>
            </span>
        </a>
        <div class="vorpal-nav" aria-hidden="true"></div>
        <div class="vorpal-topbar-right">
            <a class="vorpal-topbar-link" href="/blade/portal/">Portal</a>
            <a class="vorpal-topbar-link" href="https://github.com/vorpalnet/blade" target="_blank" rel="noopener">GitHub</a>
        </div>
    </header>

    <main class="vorpal-main">
        <div class="vorpal-page">

            <section class="vorpal-hero">
                <div>
                    <h1>BLADE API Reference</h1>
                    <p>Generated API documentation for every BLADE module &mdash; framework, libraries, admin tools, and services &mdash; with UML class diagrams. The Blended Layer Application Development Environment.</p>
                </div>
                <div class="vorpal-hero-meta">
                    <span>API Reference</span>
                    <span>Version ${VERSION}</span>
                </div>
            </section>
HEADER

# --- Libraries ---
echo '            <h2 class="jd-section">Libraries</h2>' >> "$DOCROOT/index.html"
echo '            <section class="vorpal-card-grid">' >> "$DOCROOT/index.html"
for mod in framework fsmar fsmar3; do
    [ -d "$DOCROOT/$mod" ] && emit_module "$mod" >> "$DOCROOT/index.html"
done
echo '            </section>' >> "$DOCROOT/index.html"

# --- Admin ---
echo '            <h2 class="jd-section">Administration</h2>' >> "$DOCROOT/index.html"
echo '            <section class="vorpal-card-grid">' >> "$DOCROOT/index.html"
for mod in configurator flow tuning logs crud-editor portal; do
    [ -d "$DOCROOT/$mod" ] && emit_module "$mod" >> "$DOCROOT/index.html"
done
echo '            </section>' >> "$DOCROOT/index.html"

# --- Services ---
echo '            <h2 class="jd-section">Services</h2>' >> "$DOCROOT/index.html"
echo '            <section class="vorpal-card-grid">' >> "$DOCROOT/index.html"
for dir in "$DOCROOT"/*/; do
    mod=$(basename "$dir")
    case "$mod" in
        framework|fsmar|fsmar3|configurator|flow|tuning|logs|crud-editor|portal|javadoc|test-*|images) continue ;;
    esac
    emit_module "$mod" >> "$DOCROOT/index.html"
done
echo '            </section>' >> "$DOCROOT/index.html"

# --- Test ---
echo '            <h2 class="jd-section">Test Applications</h2>' >> "$DOCROOT/index.html"
echo '            <section class="vorpal-card-grid">' >> "$DOCROOT/index.html"
for dir in "$DOCROOT"/test-*/; do
    [ -d "$dir" ] || continue
    mod=$(basename "$dir")
    emit_module "$mod" >> "$DOCROOT/index.html"
done
echo '            </section>' >> "$DOCROOT/index.html"

cat >> "$DOCROOT/index.html" <<'FOOTER'

        </div>

        <footer class="vorpal-footer">
            <a href="https://github.com/vorpalnet/blade" target="_blank" rel="noopener" title="Source on GitHub">
                <img src="/blade/portal/brand/vorpal_logo.svg" alt="Vorpal Networks">
            </a>
            <span>&copy; Vorpal Networks 2013&ndash;2026 &middot; MIT License</span>
            <a href="https://github.com/vorpalnet/blade" target="_blank" rel="noopener">View on GitHub</a>
        </footer>
    </main>

</div>
</body>
</html>
FOOTER

COUNT=$(find "$DOCROOT" -maxdepth 1 -type d ! -name "$(basename "$DOCROOT")" | wc -l | tr -d ' ')
echo "  Generated index.html with $COUNT modules"
