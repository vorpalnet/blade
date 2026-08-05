# Shared Library (`blade-shared`)

The WebLogic shared library that carries every third-party JAR BLADE needs — about 65 of
them. It contains no Java source and no BLADE code. Every admin and service WAR references
it from its own `weblogic.xml`:

```xml
<wls:library-ref>
    <wls:library-name>blade-shared</wls:library-name>
    <wls:specification-version>3.0</wls:specification-version>
    <wls:exact-match>false</wls:exact-match>
</wls:library-ref>
```

This is the other half of BLADE's **skinny WAR** rule: application WARs bundle only
`vorpal-blade-library-framework.jar` (the parent POM's war-plugin `packagingExcludes`
enforces it), and everything else resolves from here. One library update patches the
entire suite; a security scan of the dependency tree has one place to look.

## What's inside

Jackson (core, XML/YAML formats, JAX-RS providers) · Swagger/OpenAPI (core, parser,
JAX-RS integration) · JSON Schema (victools generator, networknt validator) · JSONPath ·
SLF4J (api + simple) · Nimbus OAuth 2.0 / JWT · OkHttp · Apache Commons (email,
collections4, lang3, io) · Jakarta EE APIs (mail, activation, validation, JAXB) ·
Woodstox/StAX · JSP standard taglibs · assorted small libraries (gson, ipaddress,
classgraph, snakeyaml, and friends).

One deliberate exclusion: **xalan is `provided`, never bundled** — the JDK and WebLogic
supply it, and bundling it breaks the WebLogic Admin Console with
`NoClassDefFoundError: OutputPropertiesFactory`.

## How the build works

- The `<prefer-application-packages>` block in `weblogic.xml` is **auto-generated at build
  time** by scanning the dependency JARs — never edit it by hand.
- A `<prefer-application-resources>` entry pins the SLF4J provider registration, because
  OCCAS 8.2/8.3 ship an incompatible `slf4j-nop` in `wlserver/modules/` that would
  otherwise pre-empt the bundled provider.
- Multi-release class files above the target Java version are stripped from the bundled
  JARs, preventing `UnsupportedClassVersionError` on older JDKs.
- The manifest's `Extension-Name` is `blade-shared`; `Implementation-Version` is bumped by
  hand only when the bundled JARs change, so a framework-only build doesn't masquerade as
  a shared-library change requiring redeploy.

## Deployment

Deployed to **both** the AdminServer and the engine cluster — WebLogic shared libraries
are scoped to deployment targets, and both tiers host referencing WARs. Whole-environment
order is shared → fsmar → admin → services (see [DEPLOYMENT.md](../../DEPLOYMENT.md));
undeploy is the reverse, so the library goes last.

Two failure signatures worth knowing:

- `NoClassDefFoundError` in an application — the library isn't deployed to that app's target.
- `ClassCastException: class X cannot be cast to class X` — a WAR is bundling a JAR that
  also lives in the library; restore the skinny-WAR packaging.

## Related modules

- [Framework library](../framework/README.md) — the one JAR that *does* ship inside each WAR
- [BLADE](../../README.md) — project home

## Maven Coordinates

```xml
<groupId>org.vorpal.blade</groupId>
<artifactId>vorpal-blade-library-shared</artifactId>
```
