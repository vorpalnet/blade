# Installing OCCAS from scratch

Zero to a running OCCAS dynamic-cluster domain on a fresh Linux box (the OCI
`opc` user is the running example — any sudo-capable admin user works).
Everything is driven by `./install-occas.sh <env>` reading
`build-profiles/occas/<env>.conf`; with no arguments it works out the next
step itself and tells you what it's doing.

## 0. Get BLADE and an env conf

```
git clone https://github.com/vorpalnet/blade.git
cd blade
```

Use the committed `oci.conf` as-is, edit it, or build a new env interactively:

```
./install-occas.sh myenv init
```

## 1. Prep the box — once, as root

```
sudo ./install-occas.sh oci prep
```

This creates the Oracle-convention layout from the conf:

- the `oracle` user (`install.user`) and `oinstall` group (`inventory.group`)
- `oracle.home` (e.g. `/opt/oracle/occas/8.3`), the installer directory
  (dirname of `installer.jar`), `inventory.loc`, and `java.dir` — all owned
  `oracle:oinstall`, group-writable with setgid
- adds **you** to `oinstall`, so every later step runs as you without sudo

**Log out and back in** (or `newgrp oinstall`) so your shell picks up the
group, then continue.

## 2. Get the OCCAS media from eDelivery — once per release, in a browser

Oracle requires a human license click; everything after it is scripted.

1. Sign in at <https://edelivery.oracle.com> (any oracle.com account).
2. Search **Oracle Communications Converged Application Server**, pick the
   release (e.g. 8.3), **Add to Cart**, then **Checkout**.
3. Pick the platform (**Linux x86-64**) and **accept the license**.
4. Click **WGET Options** (bottom of the download page), then
   **Download wget.sh**. If you browsed on another machine, copy it over:
   `scp wget.sh opc@<box>:`
5. Same dialog: click **Generate Token** → **Copy**. You'll paste this token
   at the script's prompt. Tokens last **~1 hour**, the URLs inside wget.sh
   **~8 hours** — both are free to regenerate by repeating this step.

## 3. Run it

```
./install-occas.sh
```

With the box prepped and no OCCAS installed it runs `all`: it asks for the
wget.sh path (stashed as `build-profiles/occas/<env>.urls`, gitignored) and
the access token, then downloads the media, unzips the nested archives to the
installer jar, fetches the Oracle JDKs (`java.runtime` for OCCAS per the
certification matrix, `java.javadoc` for BLADE javadoc builds — straight from
download.oracle.com, no login), runs the silent product install on the
runtime JDK, and creates the dynamic-cluster domain (you'll be asked for the
new `weblogic` admin password unless it's in `<env>.secret`).

Every piece is idempotent: re-running skips whatever already succeeded, so if
a token expires mid-way, paste a fresh one and it resumes where it left off.

## 4. After the install

- Start the NodeManager on each machine, then the AdminServer
  (`misc/start-admin-nm.sh`).
- TLS: `./certs.sh <env> generate` (or `import`), then, with the domain
  stopped, `./install-occas.sh <env> secure`.
- Adding an engine node later: add a `machine.N` line to the conf, bump
  `dynamic.server.count`, re-run `./install-occas.sh <env> configure`
  (**overwrites** the domain — see the script header).

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `403 Forbidden` on download | Access token (~1 h) or wget.sh URLs (~8 h) expired — redo step 2.4/2.5. (The script already sends the wget-style User-Agent that Akamai requires.) |
| `Can't write to <dir>` warnings | Prep not run, or you haven't re-logged-in since it added you to `oinstall`. |
| `Invalid Central Inventory location` | Same — `inventory.loc` must exist and be writable; prep sets it up. |
| Downloads landed in `~/occas-media` | Fallback used before prep existed. Move them: `mv ~/occas-media/* <installer dir>/` and re-run. |
| Token prompt but nothing to download | Doesn't happen — the token is only requested when a fetch is actually needed. |
