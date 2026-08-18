# Building BLADE

BLADE builds with Maven, wrapped by `./build.sh`. This is the second of the three
stages — see [INSTALLING.md](INSTALLING.md) for standing up the server and
[DEPLOYING.md](DEPLOYING.md) for pushing what you build.

| Tool | Job |
|---|---|
| `./install.sh` | Stand up the server. |
| **`./build.sh`** | Compile the **artifacts** into `dist/`. |
| `./deploy.sh` | Push the artifacts to a running server. |

A build always names a **profile** — there is no build-everything default. BLADE is
a development framework; not every module needs building, so naming the set is a
deliberate choice.

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
./build.sh default        # the base set — everything but proto/
./build.sh --list         # the profiles available
./build.sh --init         # create a profile interactively, then build it
./build.sh clean          # clean only (no profile); purges org.vorpal.blade from ~/.m2
./build.sh cleanAll       # clean, and delete the whole dist/ tree
```

With no profile on a terminal you get a picker (existing profiles, or create one);
without a terminal you get a non-zero error naming the profiles. Clean-only runs
need no profile.

---

## 3. Profiles

A profile names the module set. Three are committed in `build-profiles/*.conf`:

| Profile | Builds |
|---|---|
| `default` | everything but `proto/` — the base set, and it includes `javadoc` |
| `full` | `default` plus the `proto/` incubator apps |
| `minimal` | core routing only |

`--init` writes a **local** profile into `.conf/` (gitignored), so an experiment
never has to touch the committed set.

---

## 4. Platforms

The platform names the OCCAS/WebLogic target the run compiles for
(`build-profiles/platforms/occas-*.conf`, e.g. `occas-8.1`, `occas-8.2`,
`occas-8.3`). When you don't name one, `build.sh` resolves it in order:

1. `$MW_HOME` → the active install's `registry.xml`
2. exactly one OCCAS version bootstrapped in `~/.m2`
3. the fallback `occas-8.1`

The chosen source is shown in the build header — `Platform: occas-8.3 ($MW_HOME)`.
Name it explicitly to be sure the run targets what you'll deploy to:

```bash
./build.sh default occas-8.1        # platform is a positional arg; header shows (cli)
```

---

## 5. What a build produces

Everything built during the run is copied to `dist/<ver>-<build>/`:

```
dist/<ver>-<build>/            blade-admin.ear, blade-services.ear, blade-test.ear
                               + the active conf files + build.log
             /lib/             blade-framework.jar, blade-shared.war, blade-fsmar.jar
             /admin/           loose admin WARs (same apps as blade-admin.ear)
             /services/        loose service WARs
             /test/            loose test WARs
             /proto/           incubator WARs (full profile; no EAR)
```

Each shippable tier builds a **whole-tier EAR and** its loose WARs — deploy
whichever suits. The confs and `build.log` travel with the dist for traceability.
On a failed build, that build's `dist/` directory is deleted (the terminal still
shows the output). Skip the copy in tight dev loops with `--no-dist` (one-off) or
`export BLADE_SKIP_DIST=1` (sticky).

---

## 6. Dev vs prod versioning

WebLogic's side-by-side versioning keys off the application version, so the build
number matters:

- **Default (dev)** keeps the version stable (e.g. `3.0.4`), so OCCAS replaces the
  app in place on redeploy.
- **`--prod`** appends the build number (`3.0.4-<build>`), minting a new, traceable
  version each build — the previous one stays registered until undeployed by name.

---

## 7. Javadoc

`javadoc` is a normal profile module: it builds when the active profile lists it
(`default` and `full` do) and not otherwise — there is no separate flag, and no
`--no-javadoc`. It aggregates every module's apidocs into `blade-javadoc.war`,
bundled in the admin EAR, and `build.sh` builds it in a final pass so the docs are
complete regardless of reactor order. On a build JDK older than 23 a
javadoc-listing profile still builds, but the docs are dropped with a warning.

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
