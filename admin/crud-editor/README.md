# CRUD Editor

Javadocs: `/blade/javadoc/crud-editor/` on the Admin Portal

Author CRUD rule sets through a purpose-built editor at `/blade/crud-editor`, with a
live preview that replays every unsaved change against a sample SIP message — you see
what a rule set will do before you save, and Save is separate from Publish.

## The editor

The editor owns the CRUD-specific parts of `crud.json` — the enrichment `pipeline`
(connectors, selectors, translation tables), `defaultRuleSet`, and `ruleSets`; logging,
session settings, and cluster/server overlays stay in the Configurator (the page links
there), following the Flow-vs-Configurator division of labour for `fsmar.json`. The
page walks the three-step model — pipeline selects, rule sets transform, preview
proves — with an empty-pipeline starter (sip + table connectors pre-shaped for
dialed-number selection) and a built-in help dialog explaining connectors, selectors,
match strategies, rule filters, the `when` grammar, and the operation families.
Connector, selector, authentication, and operation forms are all generated from the
service's JSON Schema (`_schemas/crud.jschema`), so new types appear without editor
changes. Save re-parses the document as the CRUD service would — a config
the service couldn't load is rejected — encrypts `{CLEARTEXT}` credentials, and writes
through the framework's `VersionedFileStore` (previous versions kept and restorable from
the editor's history panel). Publish tells every node running the CRUD service to
reload, via the framework's `ConfigPublisher` MBean path.

## The preview

Two dry-runs, both against the live config. **Selection** (`Auto-select from message`)
runs the enrichment pipeline against a pasted SIP request and reports which rule set it
picks, folding the extracted variables into the editor's variables grid. **Rule preview**
replays a sample SIP message through a chosen rule set and returns the transformed
message, the ids of the rules that fired, and the final session-variable snapshot. It loads the **live** CRUD configuration (`config/custom/vorpal/crud.json`) on
every request, so edits published elsewhere land without a redeploy; when no config exists
yet it falls back to the framework's built-in sample. The preview engine itself is the
framework's `v3.crud` machinery — the same code that runs in the
[CRUD service](../../services/crud/README.md), so what you preview is what production
does.

## Roles

Unlike most admin apps (four roles), the CRUD Editor admits only **Admin** and
**Operator** — it exists to change routing data, so the read-mostly roles stay out.

## Related modules

- [services/crud](../../services/crud/README.md) — the engine-tier service these rules drive
- [admin/configurator](../configurator/README.md) — publishes the config the preview reads
- [Framework v3 API](../../libs/framework/src/main/java/org/vorpal/blade/framework/v3/README.md) — home of the rule engine (`v3.crud`)
- [BLADE](../../README.md) — project home

## Maven Coordinates

```xml
<groupId>org.vorpal.blade</groupId>
<artifactId>vorpal-blade-admin-crud-editor</artifactId>
```
