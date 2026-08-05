# Javadoc Site

Packages the generated API documentation for every BLADE module — framework, libraries,
admin tools, and services — into one browsable WAR, served publicly at `/blade/javadoc`.
It is the only admin app with no login: the Javadoc site is deliberately public.

## How it's built

Javadoc generation runs under the `javadocs` Maven profile (`./build.sh -- -Pjavadocs`,
automatic on JDK 23+, skippable with `--no-javadoc`). Each module's javadoc is generated
with the UML Doclet — SVG class diagrams alongside the HTML — plus BLADE's own stylesheet
and a topbar linking back to the [Portal](../portal/README.md).

At packaging time, `collect-javadocs.sh` walks the sibling modules, copies each generated
`apidocs` tree into `target/javadoc-content/<module>/`, and generates the index page. When
`build.sh` passes the active profile's module list, only those modules are collected, so
the WAR matches what the build actually shipped. New modules appear on the index
automatically — no build changes needed.

This is the canonical home of the API reference — module READMEs across the repo cite
their javadocs as `/blade/javadoc/<module>/`, meaning this site on the deployment's
Admin Portal.

## Configuration

None. A minimal settings class exists only to carry the app's portal-card identity
(`Javadoc`) over JMX; the context-root is `blade/javadoc` because the portal lists
only `blade/*` apps.

## Related modules

- [admin/portal](../portal/README.md) — links here from the card deck
- [BLADE](../../README.md) — project home; see "Javadocs" in the build guide

## Maven Coordinates

```xml
<groupId>org.vorpal.blade</groupId>
<artifactId>javadoc</artifactId>
```
