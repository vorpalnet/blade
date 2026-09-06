# Updating OCCAS under a running cluster

`update.sh` changes the binaries a live cluster runs on: build a new home, prove
it on one engine, flip, roll. It is the fourth verb beside `install.sh` (the
server), `build.sh` (the artifacts) and `deploy.sh` (the push).

## The model

Binaries live in **append-only versioned homes** behind one symlink:

```
/opt/oracle/occas/8.3        blessed home — immutable once it has run traffic
/opt/oracle/occas/8.3_p1     the next home: a clone, patched, manifested
/opt/oracle/occas/current -> 8.3
```

Two rules carry the whole safety story:

1. **A server is pinned at launch.** The start path resolves `current` once and
   substitutes the concrete home into the JVM's ClassPath and Arguments. A flip
   never touches a running server; it takes effect per server, at that server's
   next start. `BLADE_OCCAS_HOME` overrides the pin, which is how the canary
   runs one engine on a candidate home while `current` still points at the
   blessed one.
2. **A blessed home is never modified.** Patching only creates directories. A
   finished build is made read-only and gets a sha256 manifest; `preflight`
   re-verifies every manifest, so drift in a supposedly-immutable home is
   caught before patch day, not during an outage. Rollback is a symlink move
   to a home that provably has not changed.

Domains, keystores, logs and stores live outside the homes (the install layout
already guarantees this), so no flip can touch them.

## Patch day

```bash
./update.sh <env> preflight            # manifests, disk, opatch JDK, patch dir
./update.sh <env> build                # clone current -> <cur>_pN, patch, manifest
./update.sh <env> distribute 8.3_p1    # ship to engine hosts (skip when shared)
./update.sh <env> canary 8.3_p1        # one drained engine onto the new home
./update.sh <env> flip 8.3_p1          # repoint current (refused if not canaried)
./update.sh <env> roll                 # AdminServer, then drain-aware engines
```

Every verb takes `-n` for a dry run. `status` at any time shows where every
`current` points and, from each JVM's own command line, which concrete home
every running server is actually on — "converged" is a command, not a belief.

`rollback` flips back to the recorded previous home and rolls again. The old
home is still there, byte-identical; nothing here ever deletes a home.

## How build patches a copy

opatch refuses an unregistered copied home, and this OCCAS build ships no tool
to register one. `build` therefore bind-mounts the clone **at the registered
path inside a private mount namespace** (`unshare -m`): opatch sees a
registered home, nothing outside the namespace sees the mount, and the live
tree is never touched — the servers keep running through the whole build. The
opatch mechanics themselves (certified-JDK selection, OPatch tool updates,
conflict pre-checks, ordered apply) are `install.sh`'s existing patch code,
pointed at the clone.

The central oraInventory is a registry for installer tooling only — no product
reads it at runtime. `preflight` verifies it exists and otherwise ignores it;
the patch state that matters travels inside each home.

## Shared-binaries deployments

When the versioned homes sit on a shared read-only mount, set
`occas.homes.shared=true` in the profile: `distribute` becomes a no-op and a
flip is cluster-wide by nature. Everything else — pinning, canary, roll,
rollback — is identical. Engine hosts should mount the share read-only at the
export level; only the box that runs `build` needs the read-write export.

## During the roll

The cluster briefly runs mixed patch levels — the same window any rolling
patch has, entered only after the canary has already run real traffic on the
new home. The roll drains each engine first (OPTIONS answers `503 Draining`,
the balancer stops offering new calls, `PeriodCountSipThroughput` reaches zero
twice), then bounces it; replicated dialogs fail over. Session counts are not
the gate; throughput is.
