# Installing BLADE

BLADE ships three command-line tools, named for the three stages of getting it
running. This guide covers the first — standing up the server.

| Tool | Job |
|---|---|
| **`./install.sh`** | Stand up the **server**: install OCCAS, create the domain and cluster, Node Manager, TLS, and boot services. |
| **`./build.sh`** | Compile the **artifacts** into `dist/`. |
| **`./deploy.sh`** | Push the artifacts to a running server. |

`install.sh` is a re-runnable dashboard driven by a named **profile**. One profile
describes one deployment — where OCCAS lives, the domain name, the machines, the
certificate — and every tool reads the same profile from `~/.blade/<name>.conf`.

---

## 1. Prerequisites

- **OS:** Linux (the server tier). `install.sh` itself also runs on macOS for
  authoring a profile and dry-runs, but it installs onto Linux hosts.
- **OCCAS 8.3** (Oracle Communications Converged Application Server — WebLogic
  14.1.2 with SIP). `install.sh` can download the eDelivery media for you, or you
  point it at an existing install.
- **Two JDKs**, because the certified runtime and the build toolchain differ:
  - **Runtime JDK** — the Oracle-certified major for your OCCAS version (**JDK 21**
    for 8.3). The server runs on it, and so must `opatch`: run under the newer build
    JDK (25), `opatch` fails to parse a patch's XML (`Unable to parse the xml file`)
    and the apply aborts. **Patching requires JDK 21** — `install.sh` runs `opatch`
    under the runtime JDK for exactly this reason.
  - **Build JDK** — **23 or newer** (25 is fine). BLADE source uses Java 23+
    Markdown Javadoc, so `./build.sh` needs it. Bytecode still targets Java 11.

  `install.sh` fetches and links both (`<java.dir>/current` = runtime,
  `<java.dir>/build` = build), so you rarely set `JAVA_HOME` by hand.
- **Privileges:** `sudo` on the target hosts (to create the install user, install
  the product, and register systemd boot services). The product installs under a
  dedicated **install user** (default `oracle:oinstall`), which `install.sh` can
  create.
- **Network:** the admin console (default `7001`, `7002` SSL), Node Manager
  (`5556`, SSL), and SIP (`5060`, `5061` TLS) reachable as configured. `install.sh`
  can open these in `firewalld`.

---

## 2. Filesystem layout

OCCAS installs under a **versioned home** reached through a `current` symlink, and
**domains and keystores live outside that home**:

```
/opt/oracle/occas/8.3          the real product home
/opt/oracle/occas/current  ->  the active version (oracle.home points HERE)
/opt/oracle/domains/<name>     domains    — OUTSIDE the home
/opt/oracle/security/*.p12     keystores  — OUTSIDE the home   (or ~/.blade/<name>/)
/opt/oracle/java/current       runtime JDK link
/opt/oracle/java/build         build JDK link
```

**Why domains and keystores stay outside the home.** Patching installs a new home
beside the old one and flips `current`. A domain or keystore *inside* the home
would still resolve after the flip — but to the copy taken at patch time, silently
reverting every config change and cert rotation since. Keeping them out makes a
patch atomic and reversible. After a domain is configured, no path in its
`config.xml` should contain a version number.

The install user must own the **base directory** (`/opt/oracle/occas`), not just
the home inside it — `install.sh`'s "Create install dirs & chown" step handles
this. Without it, patching (which writes a sibling home + the `current` link) fails.

---

## 3. Quick start

```bash
./install.sh myenv         # opens the dashboard for profile 'myenv'
                           # (creates it if new — you fill in the steps)
```

The dashboard is a checklist. Move with `↑/↓`, press `1`–`8` to jump to a step,
`space` to tick rows for a batch run, `enter` to run the highlighted row, `d` to
toggle dry-run, `Esc`/`q` to quit. Green ✓ means a step is already done; the
right-hand column shows live state (e.g. `nmdomain — running`,
`AdminServer — running`).

To run the whole install unattended once a profile exists:

```bash
./install.sh myenv install     # runs the install ladder headless
./install.sh myenv status      # report what's up
./install.sh myenv uninstall   # tear down (confirms each step)
```

Other subcommands: `wizard` (guided profile creation), `preflight` (host checks),
`backup`. Add `--dry-run` to any run to see what would happen without changing
anything.

---

## 4. What the steps do

The dashboard is grouped into steps. Run them **top to bottom** — the order is
load-bearing (certificates before the domain that references them; Node Manager
before configure, which enrolls the domain into it).

**STEP 1 — Point at OCCAS, then install it.** Choose the OCCAS home, version, and
JDKs; create the install user and directories; download the media (or reuse an
install); run preflight host checks; install the product. Patch here if you have
interim patches (see §8).

**STEP 2 — Name it & set the admin login.** The domain name and the admin
username/password. The domain name is a WebLogic administrative container, not a
DNS name; configure overwrites an existing domain directory, so do not point it at
one you want to keep.

**STEP 3 — Describe your machines.** The host list and the Node Manager settings
(its own domain name, bind address, port, and SSL). A fresh install is
**local-first**: it builds the AdminServer **and** `engine0` on the machine it runs
on, which is already a complete deployment. You add capacity later (§7).

**STEP 4 — TLS certificate.** Either **generate** a self-signed internal CA or
**supply** your own PKCS12/PEM (e.g. Let's Encrypt). The certificate is stamped
onto the cluster's server template at configure time, so every engine — including
ones added years later — gets it automatically. BLADE never leaves the WebLogic
demo certificate on a live TLS port.

**STEP 5 — Start it up (in order).**
1. **Create & start Node Manager** — in its own domain (`nmdomain`), MBean mode,
   SSL on `5556`.
2. **Create the cluster domain** — configure enrolls it into `nmdomain`.
3. **Start the AdminServer** — through Node Manager.
4. **Install the boot services** — a systemd unit for Node Manager and one for the
   AdminServer (via NM). **Do this** — boot services are part of the install, not
   an afterthought; without them nothing restarts after a reboot.

   This step also grows/shrinks the cluster, re-provisions engine hosts, verifies
   the cluster, deploys the WebLogic Remote Console, and opens firewall ports.

**STEP 6 — Deploy settings.** The build profile, SSH user, and the admin URL that
`deploy.sh` will use. `install.sh` computes the admin URL from the live domain
(preferring `t3s` when SSL is on) and writes it, plus the WebLogic target names,
into the profile.

**STEP 7 — Deploy to WebLogic.** After `./build.sh`, deploy everything. This step
delegates to `deploy.sh` — the single deploy authority — which pushes the shared
library first, then the EARs and service WARs in dependency order. See
[DEPLOYING.md](DEPLOYING.md).

**UNINSTALL.** A reverse-order teardown: remove the domain + profile, the Node
Manager domain + unit, deinstall the product, remove directories, the install
user, and finally the local repo clone. Tick any subset; each row confirms before
deleting, and removing the local clone never touches your GitHub remote.

---

## 5. Node Manager and boot services

Node Manager runs in a **separate domain** (`nmdomain`) and serves the app/cluster
domains enrolled into it, MBean mode over SSL. Keeping it in its own domain lets it
outlive any single server restart.

The systemd units are **generated from the live domain paths** at install time, and
the helper scripts they call are staged into `$DOMAIN/bin/` so they travel with the
domain — engine hosts have no repo clone, so a unit must never point into one. The
unit's `User`/`Group` come from the domain directory's real owner; that group must
exist on every engine host or the unit fails to start.

---

## 6. TLS

BLADE runs TLS everywhere and never uses the WebLogic demo certificate on a live
port. At STEP 4 you either supply a certificate or generate a self-signed internal
CA; the identity and trust keystores are PKCS12, kept outside the versioned home
(default `~/.blade/<name>/`).

If you ever fall back to WebLogic's own demo certificates for a quick test, note
that WebLogic 14.1.2 generates a **per-domain** demo CA — the shipped
`DemoTrust.jks` does **not** contain it. `-Dweblogic.security.TrustKeyStore=DemoTrust`
therefore fails every SSL handshake with `PKIX path building failed`. Point at the
domain's own store instead:

```
-Dweblogic.security.TrustKeyStore=CustomTrust
-Dweblogic.security.CustomTrustKeyStoreFileName=$DOMAIN/security/DemoTrust.p12
-Dweblogic.security.CustomTrustKeyStoreType=PKCS12
-Dweblogic.security.CustomTrustKeyStorePassPhrase=DemoTrustKeyStorePassPhrase
```

BLADE's generated CA avoids all of this.

---

## 7. Growing and shrinking the cluster

The engine tier is a **dynamic cluster**: engines are materialized from a server
template, so there is no per-engine entry to edit. `engine0` runs on the install
host; more engines come from **Add a machine** (STEP 5), which grows the cluster
**online** — no restart of what's already running. **Remove the last machine**
shrinks it (highest-numbered only, because server index N maps to the Nth machine).
**Re-provision every engine host** re-renders a host's boot services, scripts, env,
and TLS consistently — reach for it instead of hand-patching a unit.

---

## 8. Patching

Patch **between install and configure**, or with servers stopped. Patching is
**in-place**: `opatch apply` runs on the live, inventory-registered home with the
servers down; `opatch rollback -id <id>` is the undo. Interim patches come from My
Oracle Support (the eDelivery media is the base product, not a patch).

Distribute a patched home to engine hosts with `./sync-occas.sh distribute` and
promote with `switch` (canary a subset with `--nodes`; rollback is a flip back).

---

## 9. Verifying

- The dashboard's **Verify the cluster** row health-checks every node (Node Manager
  active, JDK link resolves, log dir present, SELinux labels sane) and confirms the
  domain's TLS identity keystore actually opens with the passphrase in `config.xml`.
- `./install.sh <name> status` reports the running state.
- `./deploy.sh <name> --all status` lists what's deployed once the artifacts are up.

---

## 10. Troubleshooting

**Boot service dies with `status=203/EXEC` and no application log.** On a host with
SELinux **Enforcing**, files that come back **`unlabeled_t`** (e.g. after moving or
re-imaging `/opt/oracle`) cannot be executed by systemd, so the unit fails before it
runs anything. Confirm with `ls -Z <script>` (`…:unlabeled_t:s0`) and `getenforce`
(`Enforcing`), then relabel:

```bash
sudo restorecon -Rv /opt/oracle/domains/<domain>   # and the nmdomain
```

**A full deploy fails partway with `OutOfMemoryError: Metaspace`.** The OCCAS dev
default `MaxMetaspaceSize` is too small for the admin EAR. BLADE sets it to `2g`;
if you overrode the memory args, keep Metaspace at `2g`.

**The App Router (FSMAR) fails to load on an engine**, or the admin log shows
`MaxMessageSizeExceededException` on its fetch. Managed servers pull the App Router
jar from the AdminServer over the management channel, and the default T3 message cap
(10 MB) is smaller than the jar. BLADE raises `max-message-size` to 100 MB on the
server template and AdminServer at configure; if you built a domain without that,
raise it (the Tuning admin app exposes it).

**`nmConnect` fails with `PKIX path building failed`.** Usually the demo-CA trap
above, or a **stale** Node Manager still running from an old domain directory. Check
`/proc/<nm-pid>/cmdline` for `-Dweblogic.RootDirectory=` to see which domain a
running NM is really serving — two domains' demo CAs share a subject DN, so
comparing certificate names proves nothing.

---

## See also

- [DEPLOYING.md](DEPLOYING.md) — pushing artifacts with `deploy.sh`.
- [DEVELOPING.md](DEVELOPING.md) — building and extending BLADE.
- [SECURITY.md](SECURITY.md) — the TLS and trust model in depth.
