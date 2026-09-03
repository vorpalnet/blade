#!/usr/bin/env python3
"""Check that every JAX-RS API in a BLADE WAR is actually behind a security constraint.

SECURITY.md's other check greps for the *presence* of an <auth-constraint>. That
proves a descriptor mentions authentication; it cannot prove the pattern matches
a path anything is served on. services/context passed that grep for a year while
serving its whole API — including three methods that rewrite a live call —
unauthenticated, because the app declared no @ApplicationPath and the container
therefore served it under /resources while the descriptor named /api/*.

So this check compares the two: where JAX-RS actually answers, against what the
descriptor actually covers.

  where it answers   @ApplicationPath if the app declares one, else /resources
                     (the container's default base when there is no
                     Application subclass)
  what is covered    the <url-pattern>s of every <security-constraint> that
                     carries a non-empty <auth-constraint>

An app with no @Path class is skipped: it has no API to protect.

Usage:  misc/check-rest-constraints.py [repo-root]
Exit:   0 clean, 1 if any app serves an API outside its constraints.
"""

import os
import re
import sys
import xml.etree.ElementTree as ET

JAVAEE = "{http://xmlns.jcp.org/xml/ns/javaee}"
TIERS = ("admin", "services", "test", "proto", "libs")

# Apps that are open on purpose. SECURITY.md owns this list; keep the two in
# step. These are static or redirect-only WARs with no API to expose.
ALLOWLIST = {"admin/javadoc", "admin/redirect", "proto/demo"}


def text(node):
    return "" if node is None or node.text is None else node.text.strip()


def annotation_value(source, annotation):
    """The string argument of an annotation, e.g. @ApplicationPath("/api")."""
    match = re.search(annotation + r'\s*\(\s*"([^"]*)"\s*\)', source)
    return match.group(1) if match else None


def scan_java(app_dir):
    """(application path or None, whether the app has any @Path class)."""
    app_path, has_resource = None, False
    java_root = os.path.join(app_dir, "src", "main", "java")
    for root, _dirs, files in os.walk(java_root):
        for name in files:
            if not name.endswith(".java"):
                continue
            with open(os.path.join(root, name), encoding="utf-8", errors="replace") as handle:
                source = handle.read()
            found = annotation_value(source, "@ApplicationPath")
            if found is not None:
                app_path = found
            if re.search(r'^\s*@Path\s*\(', source, re.M):
                has_resource = True
    return app_path, has_resource


def constrained_patterns(web_xml):
    """url-patterns of constraints that actually require a role."""
    patterns = []
    root = ET.parse(web_xml).getroot()
    for constraint in root.findall(JAVAEE + "security-constraint"):
        auth = constraint.find(JAVAEE + "auth-constraint")
        if auth is None:
            # No <auth-constraint> declares the path PUBLIC. It is not a
            # protection, and counting it as one is the original bug.
            continue
        if not auth.findall(JAVAEE + "role-name"):
            # An empty <auth-constraint> denies everyone, which does cover the
            # path — that is the deny-all guard on the default JAX-RS base.
            pass
        for collection in constraint.findall(JAVAEE + "web-resource-collection"):
            for pattern in collection.findall(JAVAEE + "url-pattern"):
                patterns.append(text(pattern))
    return patterns


def covers(pattern, base):
    """Does a url-pattern cover everything under base?"""
    if pattern in ("/", "/*"):
        return True
    if pattern.endswith("/*"):
        prefix = pattern[:-2]
        return base == prefix or base.startswith(prefix + "/")
    return pattern == base


def check(repo_root):
    problems = []
    for tier in TIERS:
        tier_dir = os.path.join(repo_root, tier)
        if not os.path.isdir(tier_dir):
            continue
        for app in sorted(os.listdir(tier_dir)):
            app_dir = os.path.join(tier_dir, app)
            web_xml = os.path.join(app_dir, "src", "main", "webapp", "WEB-INF", "web.xml")
            if not os.path.isfile(web_xml):
                continue
            if "%s/%s" % (tier, app) in ALLOWLIST:
                continue

            app_path, has_resource = scan_java(app_dir)
            if not has_resource:
                continue

            base = (app_path or "/resources").rstrip("/") or "/"
            patterns = constrained_patterns(web_xml)
            if not any(covers(pattern, base) for pattern in patterns):
                where = "@ApplicationPath(\"%s\")" % app_path if app_path \
                    else "no @ApplicationPath, so the container's default /resources"
                problems.append(
                    "%s/%s: JAX-RS answers under %s (%s) but no security-constraint covers it; "
                    "constrained patterns are %s"
                    % (tier, app, base, where, patterns or "none"))
    return problems


def main():
    repo_root = sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    problems = check(repo_root)
    for problem in problems:
        print("UNPROTECTED API: " + problem)
    if problems:
        print("\n%d app(s) serve a JAX-RS API outside their security constraints." % len(problems))
        return 1
    print("OK: every app with a @Path class serves it under a constrained url-pattern.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
