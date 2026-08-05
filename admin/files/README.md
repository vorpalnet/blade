# Files

Javadocs: `/blade/javadoc/files/` on the Admin Portal

Edit schema-less domain files — XML, JSON, properties, plain text — from the browser at
`/blade/files` instead of over SSH. This is the tool for the files the
[Configurator](../configurator/README.md) can't touch because they have no JSON Schema:
`sipserver.xml`, datasource descriptors, logging configs.

## Security model

- **Deny-by-default whitelist.** Only files an admin has registered in the config are
  editable; every path parameter must match a registered entry exactly, with a
  path-traversal guard confining everything to the domain directory. There is deliberately
  no "browse" endpoint.
- **Every save is checked and backed up.** Content is well-formedness-validated for its
  type, and written through the framework's `VersionedFileStore` — prior versions land in
  `.versions/` siblings, so a bad edit rolls back. Same discipline as the Configurator.

## Server control

Some file edits (like `config.xml` splices) only take effect on restart, so the app can
optionally reach Node Manager to restart the AdminServer. The restart is deliberately
*forced* — a graceful shutdown would flush in-memory config back over the very hand-edit
being applied — and runs detached, since an in-process restart call would kill the JVM
answering the request. The button stays disabled until `serverControl.scriptPath` is
configured.

## Configuration

`./config/custom/vorpal/files.json`:

| Setting | Description |
| --- | --- |
| `files` | The whitelist: each entry a path relative to the domain root, plus its type |
| `serverControl` | Node Manager reach for the restart button; leave `scriptPath` empty to keep it off |

## Related modules

- [admin/configurator](../configurator/README.md) — for config that *does* have a schema
- [SECURITY.md](../../SECURITY.md) — the admin-tier security model
- [BLADE](../../README.md) — project home

## Maven Coordinates

```xml
<groupId>org.vorpal.blade</groupId>
<artifactId>vorpal-blade-admin-files</artifactId>
```
