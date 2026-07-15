# Installing OCCAS from scratch

Zero to a running OCCAS dynamic-cluster domain on a fresh Linux box (the OCI
`opc` user is the running example — any sudo-capable admin user works). One
driver script, one command:

```
git clone https://github.com/vorpalnet/blade.git
cd blade
./install-occas.sh
```

With no arguments it works out the next step itself: builds the env conf if
there is none (`init` interview), preps the box (via sudo, automatically),
downloads the OCCAS media and the Oracle JDKs, runs the silent product
install, creates the dynamic-cluster domain, secures it with a freshly
generated PKI — WebLogic's demo certificates are never used; NodeManager's
keystore config is re-pointed too, and the keystore password is auto-minted
into `<env>.secret` (replace the PKI later with customer-issued certs via
`./certs.sh <env> import` + re-run `secure`) — and boots NodeManager and the
AdminServer, printing the console URL. Re-running is always safe — every step
skips whatever already succeeded and resumes where it left off.

The only things it will ever ask you for:

| Prompt | Where it comes from |
|---|---|
| sudo password (maybe) | first run only — `prep` creates users/dirs; passwordless sudo (OCI `opc`) asks nothing |
| path to Oracle's `wget.sh` | the one-time eDelivery browser step below |
| eDelivery access token | same browser dialog, "Generate Token" |
| `weblogic` admin password | the new domain's admin account (or put `admin.password` in `<env>.secret`) |

## The eDelivery browser step (once per OCCAS release)

Oracle requires a human license click; everything after it is scripted.

1. Sign in at <https://edelivery.oracle.com> (any oracle.com account).
2. Search **Oracle Communications Converged Application Server**, pick the
   release (e.g. 8.3), **Add to Cart**, then **Checkout**.
3. Pick the platform (**Linux x86-64**) and **accept the license**.
4. Click **WGET Options** (bottom of the download page) →
   **Download wget.sh**. If you browsed on another machine, copy it over:
   `scp wget.sh opc@<box>:`
5. Same dialog: **Generate Token** → **Copy** — you'll paste it at the
   script's prompt.

Lifetimes (Oracle's): the token **~1 hour**, the URLs inside wget.sh
**~8 hours**. Both are free to regenerate by repeating this step; the script
tells you which one expired.

## What prep sets up (automatic, sudo, idempotent)

- the `oracle` runtime user (`install.user`) and `oinstall` group
  (`inventory.group`)
- `oracle.home` (e.g. `/opt/oracle/occas/8.3`), the installer directory,
  `inventory.loc`, and `java.dir` — owned by **you**, group-shared with
  `oracle` via setgid (mode 2775), so nothing needs a re-login and no later
  step needs sudo

Manual form, if you prefer to see it happen: `sudo ./install-occas.sh oci prep`

## After the install

- The `start` step (part of `all`) already booted the admin box: NodeManager,
  then the AdminServer through it, and printed the console URL. The `engines`
  step (also part of `all`) then provisions each engine box over key-based
  ssh — rsyncs the OCCAS home (domain included), the runtime JDK, and the env
  certs to the same paths, starts the box's NodeManager, and `nmStart`s its
  engine server. Boxes without working ssh are skipped with a warning;
  `./install-occas.sh <env> engines` re-runs resume where they left off.
- TLS is already wired: `all` generated a self-signed PKI and ran `secure`
  (SSL ports on AdminServer + engines, NodeManager on the env certificate).
  To switch to customer-issued certs: set the `cert.import.*` conf keys, run
  `./certs.sh <env> import`, then re-run `./install-occas.sh <env> secure`
  with the domain stopped.
- Adding an engine node later: add a `machine.N` line to the conf, bump
  `dynamic.server.count`, re-run `./install-occas.sh <env> configure`
  (**overwrites** the domain — see the script header).

## Environments

`./install-occas.sh <env> <step>` runs one explicit step
(`init | prep | download | install | configure | secure | all`) against
`build-profiles/occas/<env>.conf`.

**No env confs ship with the repo** — they carry your site's hostnames, IPs,
and topology, and are gitignored (like the `.secret` and `.urls` files). On a
fresh clone, plain `./install-occas.sh` starts the `init` interview and writes
your env's conf; or copy `example.conf.example` to `<env>.conf` and edit.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `403 Forbidden` on download | Access token (~1 h) or wget.sh URLs (~8 h) expired — redo the browser step. (The script already sends the wget-style User-Agent that Akamai requires.) |
| `Can't write to <dir>` / inventory errors | Prep hasn't run and passwordless sudo isn't available — run `sudo ./install-occas.sh <env> prep` once. |
| Media sitting in `~/occas-media` | The pre-prep fallback location — the next run reclaims it automatically. |
