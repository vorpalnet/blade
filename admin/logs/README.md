# Logs

Javadocs: `/blade/javadoc/logs/` on the Admin Portal

Read any log file on any node in the cluster from one page, at `/blade/logs`. Scroll a
multi-gigabyte file end to end, follow it live, search it, and download it — without
copying it anywhere first.

## How it works

Server discovery walks the WebLogic domain configuration over JMX. Log reads go through a
per-JVM `VorpalLogReader` MBean, reached over the federated DomainRuntime connection — the
same authenticated JMX path the rest of the admin tier uses, so no extra ports or agents
on the engines.

The viewer never holds a whole file. It keeps a sliding **window** of a few 64 KiB chunks;
scrolling near an edge fetches the neighbouring chunk and drops the far one. The slider
above the pane covers the whole file, so opening a 400 MB log costs the same as opening a
small one. A slice is fetched as raw bytes and trimmed to whole lines *before* it is
decoded, which is what keeps a window from ever splitting a UTF-8 character.

Reading is one thing; finding is another. Search runs on the node that holds the file and
returns byte offsets, so a result is somewhere the viewer can jump to rather than a second
copy of the log. Each pass is bounded — an engine node is carrying calls — and resumes
from where the last one stopped.

One packaging subtlety: the MBean's registration listener is a thin shim compiled into
this WAR's own classes (not the shared library), because WebLogic must resolve it during
deployment activation, before the shared-library classloader is merged. Registration is
idempotent per JVM.

That last point has a consequence worth knowing. The reader in a JVM is created by the
first BLADE application to start there, and it is deliberately left registered when an
application stops — so **redeploying does not replace it; only restarting the server
does.** A node that has not restarted since a framework change keeps the reader it booted
with. The viewer therefore asks each node what its reader can do (via
`MBeanServer.getMBeanInfo`) rather than assuming, and says so plainly when a node's reader
predates a capability.

## Testing

```bash
./mvnw -f libs/framework/pom.xml test -Dtest=VorpalLogReaderTest   # reader: slices, tail, search bounds, path safety
node admin/logs/src/test/js/run.js                                 # browser: window snapping, record parsing, ANSI
```

The browser tests are deliberately dependency-free and not wired into `./build.sh`, which
has no node toolchain. Same convention as [admin/flow](../flow/README.md).

## Configuration

`./config/custom/vorpal/logs.json` — metadata only today. The app appears on the
[Portal](../portal/README.md) deck like every other admin app.

## Related modules

- [Framework v2 logging](../../libs/framework/src/main/java/org/vorpal/blade/framework/v2/logging/README.md) — the per-app `vorpal/<app>.log` files this viewer tails
- [admin/callflow](../callflow/README.md) — when a log line isn't enough and you need the full SIP trace
- [BLADE](../../README.md) — project home

## Maven Coordinates

```xml
<groupId>org.vorpal.blade</groupId>
<artifactId>vorpal-blade-admin-logs</artifactId>
```
