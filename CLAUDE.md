# CLAUDE.md

## Build Commands

```bash
./build.sh                              # full build, OCCAS 8.1 (defaults)
./build.sh production                   # production services, OCCAS 8.1
./build.sh production occas-8.2         # production services, OCCAS 8.2
./build.sh minimal occas-8.3            # core routing, OCCAS 8.3
./build.sh production clean package     # with explicit Maven goals
./build.sh -- -Pjavadocs               # full build with javadoc WAR
```

Do NOT use `mvn` or `./mvnw` directly — always use `./build.sh`.

## Version Management

The project version is defined in ONE place only: `<revision>` property in the root `pom.xml`.
All child POMs inherit it via `${revision}`. The `flatten-maven-plugin` resolves it at build time.
Never hardcode version numbers in child POMs.

## Project Structure

- 20 Maven modules: 2 JARs (framework, fsmar) + 17 WARs + 1 EAR
- Modules are organized into subdirectories by role:
  - `libs/` — framework (vorpal-blade-framework JAR), fsmar (fat JAR)
  - `admin/` — blade (console WAR), configurator (WAR) — deployed to AdminServer
  - `apps/` — service WARs + applications (EAR) — deployed to cluster
  - `test/` — test-b2bua, test-uac, test-uas
- Module directory names match their weblogic.xml context roots
- Always-built: libs/framework, libs/fsmar, admin/blade, admin/configurator, apps/applications
- Services (profile-controlled): acl, analytics, hold, options, presence, proxy-balancer, proxy-block, proxy-registrar, proxy-router, queue, tpcc, transfer
- Test apps (profile-controlled): test-b2bua, test-uac, test-uas

## Build Profiles

Module profiles live in `build-profiles/*.conf` (module lists). Platform profiles live in `build-profiles/platforms/*.conf` (Java version). Default platform is `occas-8.1` (JDK 11).

## Key Rules

- Always update README.md when making build process changes
- EAR uses skinny WARs — all JARs go in EAR `lib/`
- FSMAR is a fat JAR (shade plugin), manually installed in OCCAS approuter directory
- Admin WARs (blade, configurator) are standalone fat WARs deployed separately to AdminServer
- OCCAS JARs are `provided` scope — run `./install-occas.sh` once to install them locally
