# Building BLADE

BLADE builds with Maven, wrapped by `./build.sh`. This is the second of the three
stages — see [INSTALLING.md](INSTALLING.md) for standing up the server and
[DEPLOYING.md](DEPLOYING.md) for pushing what you build.

| Tool | Job |
|---|---|
| `./install.sh` | Stand up the server. |
| **`./build.sh`** | Compile the **artifacts** into `dist/`. |
| `./deploy.sh` | Push the artifacts to a running server. |

One command builds everything shippable — the framework, the shared library, every
admin/service/test/proto WAR, and the three whole-tier EARs — in one Maven reactor.
There is no module to pick: to iterate on a single module, run Maven directly
(`./mvnw -pl services/hold package`). What you *do* choose is the **mode**: a fast
`dev` loop or a traceable `prod` release.

---

## 1. Prerequisites

- **Build JDK 23 or newer.** BLADE's Javadoc uses Java 23+ `///` Markdown doc
  comments (JEP 467), so the javadoc tool needs 23+ (25 works). Bytecode targets
  Java 11 — a build JDK newer than the runtime target is expected.
- **The OCCAS JARs in your local Maven repo.** `./bootstrap.sh <occas-home>`
  installs them into `~/.m2`; `build.sh` runs it for you from `$MW_HOME` when they
  are missing.
- **Maven** comes from the bundled `./mvnw` wrapper — nothing to install. (The
  ANT-to-Maven migration is complete; Maven is the only build path.)

---

## 2. Quick start

```bash
./build.sh                # dev build: the full set → flat dist/
./build.sh --prod         # release build: the full set → dist/<rev>-<build>/
./build.sh clean          # clean only; purges org.vorpal.blade from ~/.m2
./build.sh cleanAll       # clean, and delete the whole dist/ tree
```

No arguments needed — a build is always the whole shippable set. Add a platform to
target a specific OCCAS version (§4), or plain Maven goals (`clean package`).

---

## 3. Dev and prod

The one dial that matters is the mode, because WebLogic's side-by-side versioning
keys off the application version:

- **`dev` (default)** keeps the app version stable (e.g. `3.0.6`), so OCCAS replaces
  the app in place on redeploy — the fast edit/build/redeploy loop. Output lands
  **flat in `dist/`** (cleaned first each build), and the slow Javadoc pass is
  skipped.
- **`--prod`** appends the build number (`3.0.6-<build>`), minting a new, traceable
  version each build — the previous one stays registered until undeployed by name.
  Output lands in its own **`dist/<rev>-<build>/`** release directory, and the
  Javadoc is built.

`BLADE_MODE=prod` in the environment sets the default; an explicit `--dev`/`--prod`
always wins.

---

## 4. Building for a named environment

A deployment's profile carries its mode. `install.sh` and `deploy.sh` share one
profile per environment, `~/.blade/<env>/profile.conf`; naming it on the build reads
that environment's `build.mode`:

```bash
./build.sh ashburn        # builds in the mode ashburn's profile records
./build.sh ashburn --prod # …unless you override it on the command line
```

So `build.sh`, `install.sh` and `deploy.sh` all speak of the same environment by
name. (`build.sh` reads only the mode; it needs nothing else from the profile.)

---

## 5. Platforms

The platform names the OCCAS/WebLogic target the run compiles for
(`build-profiles/platforms/occas-*.conf`, e.g. `occas-8.1`, `occas-8.2`,
`occas-8.3`). When you don't name one, `build.sh` resolves it in order:

1. `$MW_HOME` → the active install's `registry.xml`
2. exactly one OCCAS version bootstrapped in `~/.m2`
3. the fallback `occas-8.1`

The chosen source is shown in the build header — `Platform: occas-8.3 ($MW_HOME)`.
Name it explicitly to be sure the run targets what you'll deploy to:

```bash
./build.sh occas-8.1        # platform is a positional arg; header shows (cli)
```

---

## 6. What a build produces

Both EARs **and** loose WARs ship, so an operator deploys whichever suits — a
whole-tier EAR in one step, or a loose WAR for per-service start/stop/target. `dev`
writes flat into `dist/`; `prod` nests each release in `dist/<rev>-<build>/`. Either
way the layout under `<dist>` is:

```
<dist>/            blade-admin.ear, blade-services.ear, blade-test.ear  + build.log
      /lib/        blade-framework.jar, blade-shared.war, blade-fsmar.jar
      /admin/      loose admin WARs (same apps as blade-admin.ear)
      /services/   loose service WARs
      /test/       loose test WARs
      /proto/      incubator WARs (no EAR — proto/ is a grab-bag, deployed ad-hoc)
```

`build.log` (the full build console) travels with the dist so a build can be
reviewed after the fact. A failed **prod** build removes its release directory; a
failed **dev** build leaves the prior flat output in place — `dist/` is never
deleted wholesale. Skip the copy in tight dev loops with `--no-dist` (one-off) or
`export BLADE_SKIP_DIST=1` (sticky).

---

## 7. Javadoc

The javadoc app aggregates every module's apidocs into `blade-javadoc.war`, bundled
in the admin EAR. It is the slow part, so it is **built for a `--prod` release** and
**skipped in `dev`** for a fast loop. Because it must run after every module has
generated its apidocs, a prod build does it in a final pass, so the docs are
complete regardless of reactor order. On a build JDK older than 23 the docs are
dropped with a warning (bytecode still targets Java 11).

---

## 8. Switching platforms requires `clean`

`build.sh` does not `clean` between runs, and Maven's up-to-date check compares
source and class **timestamps** — it cannot see the bytecode target. After a
platform switch, a plain rebuild recompiles only the edited sources and repackages
every untouched class at the **old** target. Deploy that to the other WebLogic and
it fails with `UnsupportedClassVersionError`.

So **any platform switch requires `clean`**:

```bash
./build.sh occas-8.1 clean package
```

The header's `Target: Java 11 bytecode` describes what the run *compiles*, not
necessarily every class the WARs *ship*. And `./mvnw -pl <module>` bypasses
`build.sh` entirely — it reads no platform conf (so it uses the parent POM's
default target), updates no `dist/`, and never cleans. Build through `build.sh`.

---

## See also

- [INSTALLING.md](INSTALLING.md) — standing up the server.
- [DEPLOYING.md](DEPLOYING.md) — pushing the artifacts to it.
