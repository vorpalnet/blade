#!/usr/bin/env bash
#
# Populate the local Maven repo with the WebLogic/OCCAS artifact set the
# weblogic-maven-plugin needs to run (its whole dependency closure), so
# `deploy.sh` can deploy remotely via the Maven engine from a dev box.
#
# This reproduces Oracle's `oracle-maven-sync:push`, which is not shipped with
# every OCCAS install. Oracle lays out a Maven-style repository under each
# */plugins/maven tree, but the artifact jars aren't in it: a sibling
# `.location` file names the real jar's path (relative to the install). We walk
# every pom, install it plus its real jar (via .location, or an in-tree jar like
# the plugin's own), and write a maven-metadata-local.xml so the version RANGES
# in Oracle's poms resolve. Jars are symlinked, not copied, so ~/.m2 stays lean.
#
# Idempotent. Usage: ./oracle-maven-provision.sh /path/to/occas   (or $MW_HOME)
set -u
OCCAS_HOME="${1:-${MW_HOME:-}}"
[ -n "$OCCAS_HOME" ] && [ -d "$OCCAS_HOME/wlserver" ] || { echo "Usage: $0 /path/to/occas" >&2; exit 1; }
DEST="${HOME}/.m2/repository"
ts=$(date +%Y%m%d%H%M%S)
poms=0 jars=0 pomonly=0 miss=0

# First <tag> value on the line it appears (Oracle's poms are flat, one per line).
tagval(){ awk -v t="$1" 'BEGIN{o="<"t">";c="</"t">"}{if(i=index($0,o)){s=substr($0,i+length(o));j=index(s,c);if(j)s=substr(s,1,j-1);gsub(/^[ \t]+|[ \t]+$/,"",s);print s;exit}}'; }

while IFS= read -r base; do
  while IFS= read -r -d '' pom; do
    head=$(awk '/<dependencies>|<build>/{exit}{print}' "$pom")
    g=$(printf '%s\n' "$head" | tagval groupId)
    a=$(printf '%s\n' "$head" | tagval artifactId)
    v=$(printf '%s\n' "$head" | tagval version)
    pk=$(printf '%s\n' "$head" | tagval packaging)
    [ -n "$g" ] && [ -n "$a" ] && [ -n "$v" ] || continue
    gpath="${g//.//}"
    tdir="$DEST/$gpath/$a/$v"
    mkdir -p "$tdir"
    cp -f "$pom" "$tdir/$a-$v.pom"; poms=$((poms+1))
    if [ "$pk" = "pom" ]; then
      pomonly=$((pomonly+1))
    else
      case "$pk" in ""|jar|maven-plugin|bundle|ejb|ejb-client) ext=jar;; *) ext="$pk";; esac
      dir=$(dirname "$pom"); dirver=$(basename "$dir")
      loc=$(ls "$dir"/*.location 2>/dev/null | head -1)
      src=""
      if [ -n "$loc" ] && [ -f "$loc" ]; then
        rel=$(tr -d '\r\n' < "$loc"); [ -f "$OCCAS_HOME/$rel" ] && src="$OCCAS_HOME/$rel"
      fi
      if [ -z "$src" ]; then                     # jar shipped in-tree (e.g. the plugin)
        for cand in "$dir/$a-$dirver.$ext" "$dir/$a.$dirver.$ext"; do
          [ -f "$cand" ] && { src="$cand"; break; }
        done
      fi
      if [ -n "$src" ]; then ln -sf "$src" "$tdir/$a-$v.$ext"; jars=$((jars+1)); else miss=$((miss+1)); fi
    fi
    # metadata: list every installed version so ranged deps resolve locally.
    adest="$DEST/$gpath/$a"
    vers=$(find "$adest" -maxdepth 1 -mindepth 1 -type d -exec basename {} \; | sort)
    last=$(printf '%s\n' "$vers" | tail -1)
    {
      echo '<?xml version="1.0" encoding="UTF-8"?>'
      echo '<metadata>'; echo "  <groupId>$g</groupId>"; echo "  <artifactId>$a</artifactId>"
      echo '  <versioning>'; echo "    <latest>$last</latest>"; echo "    <release>$last</release>"
      echo '    <versions>'
      printf '%s\n' "$vers" | while read -r vv; do [ -n "$vv" ] && echo "      <version>$vv</version>"; done
      echo '    </versions>'; echo "    <lastUpdated>$ts</lastUpdated>"; echo '  </versioning>'
      echo '</metadata>'
    } > "$adest/maven-metadata-local.xml"
  done < <(find "$base" -name '*.pom' -print0)
done < <(find "$OCCAS_HOME" -maxdepth 3 -type d -path '*/plugins/maven')

echo "  provisioned: $poms poms, $jars jars, $pomonly pom-only, $miss missing (skipped)"
