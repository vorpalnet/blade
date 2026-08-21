# Installing BLADE

*For the sales engineer who has to stand it up, demo it, and defend it in the room.*

BLADE turns a base OCCAS install into a running, converged SIP cluster — one that
scales online, speaks TLS on every channel, patches atomically, and restarts
itself after a reboot — from a single re-runnable installer. The installer is
itself part of the pitch: `./install.sh myenv`, a handful of guided steps, and you
have a distributed engine tier whose **calls survive a node dropping**, because
BLADE serializes session state across the cluster instead of pinning it to one
JVM. This guide is how that works — close enough to the metal to answer the hard
question a customer's architect will ask.

BLADE ships three command-line tools, named for the three stages of getting it
running. This guide covers the first — standing up the server.

| Tool | Job |
|---|---|
| **`./install.sh`** | Stand up the **server**: install OCCAS, create the domain and cluster, Node Manager, TLS, and boot services. |
| **`./build.sh`** | Compile the **artifacts** into `dist/`. |
| **`./deploy.sh`** | Push the artifacts to a running server. |

`install.sh` is a re-runnable dashboard driven by a named **profile**. One profile
describes one deployment — where OCCAS lives, the domain name, the machines, the
certificate — and install.sh, build.sh and deploy.sh all read the same profile at
`~/.blade/<name>/profile.conf`, its keystores beside it in `certs/`. The dashboard's
PROFILE rows load, clone, rename or delete an environment — clone prod to stand up
staging from the same settings, then edit what differs (its secrets and certs are
not carried; the new environment sets its own).
Re-runnable matters: every step is idempotent, so the dashboard is equally the way
you build a cluster the first time and the way you repair or grow one later.

---

## 1. What you are standing up

Before the mechanics, the shape of the thing — this is the slide the architect
cares about.

- **A dynamic cluster, not a rack of hand-built servers.** The engine tier is a
  WebLogic *dynamic cluster*: every engine is materialized from one server
  template, so there is no per-engine configuration to drift. Adding the tenth
  engine is the same one action as adding the second.
- **Failover that is structural, not bolted on.** SIP session state serializes and
  replicates across the tier. When a node drops, an in-progress call is picked up
  elsewhere — the property a call center is actually buying.
- **Node-Manager-driven, systemd-anchored.** Every server starts through Node
  Manager over mutual TLS, and the boot services install that *exact* start path
  as systemd units. A reboot exercises the same path provisioning did — "it comes
  back up on its own" is a property you can demonstrate, not promise.
- **TLS end to end, never the demo certificate.** The admin channel, Node Manager,
  and SIP all run TLS from a certificate you control. BLADE refuses to leave
  WebLogic's publicly-known demo certificate on a live port.
- **Atomic, reversible patching.** A patch installs a new home beside the old one
  and flips a symlink; domains and keystores live *outside* that home, so a patch
  never silently reverts a config change or a cert rotation.

Everything below is how the installer delivers those five properties.

---

## 2. Prerequisites

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
- **Privileges:** key-based `ssh` and passwordless `sudo` to every target host —
  the installer uses them to create the install user, lay down the product, and
  register boot services. An engine host that lacks either is skipped with a clear
  warning and picked up on the next run.
- **Network:** the admin console (default `7001`, `7002` SSL), Node Manager
  (`5556`, SSL), and SIP (`5060`, `5061` TLS) reachable as configured. `install.sh`
  can open these in `firewalld`.
- **Edge proxy (optional):** if you front the portal with nginx on this box,
  `install.sh` renders and reloads its config (§6, STEP 5). Install `nginx`
  yourself first — with the naxsi module if you want the WAF — and have a TLS
  certificate on disk (e.g. Let's Encrypt); `install.sh` references the cert, it
  does not obtain or renew it.

---

## 3. Filesystem layout

OCCAS installs under a **versioned home** reached through a `current` symlink, and
**domains and keystores live outside that home**:

```
/opt/oracle/occas/8.3          the real product home
/opt/oracle/occas/current  ->  the active version (oracle.home points HERE)
/opt/oracle/domains/<name>     domains    — OUTSIDE the home
/opt/oracle/security/*.p12     keystores  — OUTSIDE the home   (or ~/.blade/<name>/certs/)
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

## 4. The install user

The product installs under a dedicated OS user (default `oracle:oinstall`). Two
things about it are worth knowing before a multi-host build:

- **The numeric ids default to Oracle's convention** — `oracle` = **54321**,
  `oinstall` = **54321**, the same ids Oracle's own preinstall RPMs and container
  images use. On a greenfield cluster every host agrees on the numbers; override
  with `install.uid` / `install.gid` if those are taken.
- **Ownership travels by name, not by number.** When the installer provisions an
  engine host it recreates the install user there and copies the trees with
  `--chown` by name, so a host whose `oracle` happens to be a different uid is
  cosmetic, not a failure — *unless* a home sits on shared NFS storage, where the
  kernel checks numbers. That is the one case to pin the ids.

`install.sh` creates the user and group for you (dashboard: "Create install user &
group"), locally and — during engine provisioning — on each remote host over ssh.
The boot services then run **as that install user**, which is what lets them read
the owner-only keystores the servers need; a boot unit left running as the login
user cannot open them.

---

## 5. Quick start

```bash
./install.sh myenv         # opens the dashboard for profile 'myenv'
                           # (creates it if new — you fill in the steps)
```

The dashboard is a checklist. Move with `↑/↓`, press `1`–`8` to jump to a step,
`space` to tick rows for a batch run, `enter` to run the highlighted row, `d` to
toggle dry-run, `Esc`/`q` to quit. A green ✓ means a step is already done; the
right-hand column shows live state (e.g. `nmdomain — running`,
`AdminServer — running`). Rows are chosen by moving to their label — there are no
per-letter shortcuts.

To run the whole install unattended once a profile exists:

```bash
./install.sh myenv install     # runs the install ladder headless
./install.sh myenv status      # report what's up
./install.sh myenv uninstall   # tear down (confirms each step)
```

Other subcommands: `wizard` (guided profile creation), `preflight` (host checks),
`backup`. Add `--dry-run` to any run to see what would happen without changing
anything — the demo-safe way to walk a customer through it.

---

## 6. What the steps do

The dashboard is grouped into steps. Run them **top to bottom** — the order is
load-bearing (certificates before the domain that references them; Node Manager
before configure, which enrolls the domain into it).

**STEP 1 — Point at OCCAS, then install it.** Choose the OCCAS home, version, and
JDKs; create the install user and directories; download the media (or reuse an
install); run preflight host checks; install the product. Patch here if you have
interim patches (see §10).

**STEP 2 — Name it & set the admin login.** The domain name and the admin
username/password. The domain name is a WebLogic administrative container, not a
DNS name; configure overwrites an existing domain directory, so do not point it at
one you want to keep.

**STEP 3 — Describe your machines.** The host list and the Node Manager settings
(its own domain name, bind address, port, and SSL). A fresh install is
**local-first**: it builds the AdminServer **and** `engine0` on the machine it runs
on, which is already a complete deployment. You add capacity later (§9).

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

   On the front-door box it also owns the **edge nginx reverse proxy**. The
   **nginx** row sets the vhosts (admin → AdminServer, apps → engine0), the
   backend address, cert paths, and whether naxsi is on; the **ngx** row renders
   `/etc/nginx/nginx.conf`, validates it off to the side, backs up the old one,
   and reloads. The rendered config terminates TLS and forwards both HTTP and
   WebSocket — the `Upgrade`/`Connection` headers are hop-by-hop, so a proxy that
   forgets to re-set them turns the Configurator/WebRTC handshake into a 302 to
   login. The backend defaults to this box's routable address, not `127.0.0.1`:
   the AdminServer SSL listener binds its ListenAddress, which localhost can't
   reach.

**STEP 6 — Deploy settings.** The build mode (dev or prod), SSH user, and the admin URL that
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

## 7. Engine hosts: how a node joins

Provisioning an engine host is deliberately not a second install. `install.sh`
**replicates** the admin box: it `rsync`s the real versioned OCCAS home, both
domains (cluster + `nmdomain`), the runtime JDK, and the TLS keystores to the same
absolute paths, creates the install user over ssh, then installs and starts the
boot services. Two consequences worth saying out loud:

- **An engine never runs `opatch`.** It receives a home that was patched and
  validated once, on the admin box. The patch story for the tier is "patch one
  host, ship it" — not "patch each host and hope they match."
- **A reboot is the boot path, tested.** The servers come up through systemd →
  Node Manager → `nmStart`, which is exactly what provisioning just ran. If
  provisioning worked, boot works.

An unreachable or mis-privileged host is skipped with a warning and retried on the
next run; nothing about the rest of the cluster depends on it. **Re-provision every
engine host** re-renders a host's boot services, scripts, env, and TLS
consistently — reach for it instead of hand-patching a unit.

---

## 8. Node Manager, boot services, and the trust chain

Node Manager runs in a **separate domain** (`nmdomain`) and serves the app/cluster
domains enrolled into it, MBean mode over **mutual TLS**. Keeping it in its own
domain lets it outlive any single server restart — including the AdminServer,
which is what makes the Files app's "restart to apply" button possible.

The pieces that make the boot path both self-contained and secure:

- **Units are generated from the live domain paths** at install time, and the
  helper scripts they call are staged into `$DOMAIN/bin/` so they travel with the
  domain. Engine hosts have no repo clone, so a unit must never point into one.
- **The unit runs as the install user**, and its `Group` must exist on every host —
  a missing group makes systemd refuse the unit with a `216/GROUP` error that names
  neither the group nor the host. The installer creates it everywhere for you.
- **A per-cluster Node Manager trust store** (`nm-trust.p12`, PKCS12) is what the
  boot-time WLST client uses to validate Node Manager's certificate. Its passphrase
  is carried in a `0600` boot env file (`.blade-nm.env`) next to the domain, read
  only by systemd at start. Rotate the certificate and the installer **re-pushes
  that passphrase to every host's boot env** on the next "Create & start Node
  Manager", so a rotation can never strand a node's next start on a stale
  passphrase. (Two failure modes that flow from this chain — an empty trust store
  and a stale passphrase — are in §12.)

---

## 9. TLS

BLADE runs TLS everywhere and never uses the WebLogic demo certificate on a live
port. At STEP 4 you either supply a certificate or generate a self-signed internal
CA; the identity and trust keystores are PKCS12, kept outside the versioned home
(default `~/.blade/<name>/certs/`). The identity SAN covers every host, FQDN, and IP in
the profile, so one certificate satisfies hostname verification across the tier.

Changing the certificate is a first-class action, not a reinstall: run "Supply your
own certificate" or "Generate a self-signed CA", then re-run "Create & start Node
Manager" to propagate the new material into Node Manager and every boot env (§8).

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

## 10. Growing and shrinking the cluster

The engine tier is a **dynamic cluster**: engines are materialized from a server
template, so there is no per-engine entry to edit. `engine0` runs on the install
host; more engines come from **Add a machine** (STEP 5), which grows the cluster
**online** — no restart of what is already running. **Remove the last machine**
shrinks it (highest-numbered only, because server index N maps to the Nth machine).

Two operational truths a live resize turns up:

- **The config change commits; the running members consume it on their next
  restart.** Growing the tier past a running member is a configuration edit that
  activates immediately, but a member already up materializes the new engine when
  it is next bounced — an online resize plus a rolling restart, not a single atomic
  event.
- **Only one editor at a time.** Online changes take the WebLogic edit lock. If a
  resize refuses to activate, an open **WebLogic Remote Console edit session** is
  usually holding it — discard changes and release the lock there, and the installer
  proceeds. The installer will not steal another admin's lock.

---

## 11. Patching

Patch **between install and configure**, or with servers stopped. Patching is
**in-place**: `opatch apply` runs on the live, inventory-registered home with the
servers down; `opatch rollback -id <id>` is the undo. Interim patches come from My
Oracle Support (the eDelivery media is the base product, not a patch).

Distribute a patched home to engine hosts with `./sync-occas.sh distribute` and
promote with `switch` (canary a subset with `--nodes`; rollback is a flip back).
Because an engine only ever *receives* a home (§7), the whole tier moves to a patch
level one host at a time, with a known-good version one symlink flip away.

---

## 12. Verifying

- The dashboard's **Verify the cluster** row health-checks every node (Node Manager
  active, JDK link resolves, log dir present, SELinux labels sane) and confirms the
  domain's TLS identity keystore actually opens with the passphrase in `config.xml`.
- `./install.sh <name> status` reports the running state.
- `./deploy.sh <name> --all status` lists what's deployed once the artifacts are up.

---

## 13. Troubleshooting

**Boot service dies with `status=203/EXEC` and no application log.** On a host with
SELinux **Enforcing**, files that come back **`unlabeled_t`** (e.g. after moving or
re-imaging `/opt/oracle`) cannot be executed by systemd, so the unit fails before it
runs anything. Confirm with `ls -Z <script>` (`…:unlabeled_t:s0`) and `getenforce`
(`Enforcing`), then relabel:

```bash
sudo restorecon -Rv /opt/oracle/domains/<domain>   # and the nmdomain
```

**A managed server boots into `ADMIN` mode instead of `RUNNING`.** WebLogic parks a
server in `ADMIN` when an application fails to deploy at startup (`BEA-149259`). The
usual cause is the same web app deployed **twice** to one target — for example a
whole-tier EAR *and* its constituent loose WARs both on the engine cluster, which
collides on every context root. Deploy either the tier EAR or the loose WARs to a
given target, never both.

**A boot service dies with `trustAnchors parameter must be non-empty`.** The
boot-time WLST client loaded an *empty* Node Manager trust store. Two causes: the
unit is running as a user that cannot read the owner-only `nm-trust.p12`, or the
passphrase in that host's `.blade-nm.env` no longer opens it after a cert rotation.
Run the boot service as the install user, and re-run "Create & start Node Manager"
to re-push the current passphrase to every host (§8).

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
in §9, or a **stale** Node Manager still running from an old domain directory. Check
`/proc/<nm-pid>/cmdline` for `-Dweblogic.RootDirectory=` to see which domain a
running NM is really serving — two domains' demo CAs share a subject DN, so
comparing certificate names proves nothing.

---

## See also

- [DEPLOYING.md](DEPLOYING.md) — pushing artifacts with `deploy.sh`.
- [DEVELOPING.md](DEVELOPING.md) — building and extending BLADE.
- [SECURITY.md](SECURITY.md) — the TLS and trust model in depth.
