# Installing OCCAS

**Use `./blade.sh`.** This directory no longer drives the install — it survives only for
legacy env conf files. Profiles live in `.conf/<name>/` (gitignored) and are created and
edited by the installer itself.

```
./blade.sh                    pick or create a profile, then the dashboard
./blade.sh <name> install     unattended, end to end
./blade.sh <name> status      health snapshot, including patch level per host
```

`install-occas.sh` was deleted once `blade.sh` had been exercised end-to-end on a real
cluster. It is in git history if you need it.

## What the installer does

One machine at a time. `./blade.sh <name> install` builds **AdminServer + engine0 on the
machine it runs on**, and that is a complete, working deployment — not a stepping stone to a
cluster. Growth is a separate, online step.

| step | what it does |
|---|---|
| user / dirs | creates the install user, group and directories |
| download | fetches the OCCAS media from Oracle eDelivery (one browser step for the licence click) |
| install | silent product install into `<base>/<version>` |
| **patch** | builds a **patched copy** of the home, out-of-place — see below |
| certificate | your own certificate, or a generated self-signed one. The WebLogic demo certificate is never used |
| domain | dynamic cluster; the certificate and both SIP channels go onto the server template |
| Node Manager / AdminServer | started, plus systemd units so a reboot recovers unattended |
| **Add a machine** | grows the cluster online: machine1 runs engine1, machine2 runs engine2, … |

## Layout

The Oracle home is reached through a **symlink**, so patching is atomic and reversible:

```
/opt/oracle/occas/8.3.0        real GA home
/opt/oracle/occas/8.3.0_p1     patched copy
/opt/oracle/occas/current ->   the link; oracle.home points here
/opt/oracle/domains/<name>     domains live OUTSIDE the Oracle home
```

Domains are outside deliberately. Inside, flipping the symlink would swing the domain path
onto the patched copy's stale snapshot and lose every config change since it was taken.

## Patching

Oracle's eDelivery media ships buggy; the fixes come from My Oracle Support. Download the
zips in a browser into `patch.dir` (default `~/occas-patches`) and list the patch IDs, in
the order they must be applied, in `.conf/<name>/patches.list`.

The patch step copies the home that `current` resolves to, patches the **copy**, and stops.
Nothing is switched, so a failed patch costs nothing and the running install is untouched.
Promote it deliberately:

```
./sync-occas.sh <name> distribute 8.3.0_p1     # ship it to the other machines
./sync-occas.sh <name> switch     8.3.0_p1     # repoint 'current' (--nodes for a canary)
```

Rollback is switching back — the previous home is still on disk.

**Engines are never patched.** They receive a home that was patched and validated once, on
machine0. `./blade.sh <name> status` reports the patch level of every host and warns if they
disagree.
