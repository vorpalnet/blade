# CRUD Editor

Javadocs: `/blade/javadoc/crud-editor/` on the Admin Portal

Author CRUD rule sets through a purpose-built editor at `/blade/crud-editor`, with a
live preview that replays every unsaved change against a sample SIP message — you see
what a rule set will do before you save, and Save is separate from Publish.

## The editor

The editor owns the CRUD-specific parts of `crud.json`: `variables`, the enrichment
`pipeline` (connectors, selectors, translation tables), `defaultRuleSet`, and `ruleSets`.
Logging and session settings stay in the Configurator (the page links there).

It edits all three of an application's files: the domain file and any cluster or server
file, listed beside the page in the order a server merges them. Variables, connectors and
rules are keyed by name, so a server file changes one value or one rule and inherits the
rest; *merged (read-only)* shows what a file runs and which file set each line, and the
toolbar says whether every running server holds what was saved. Each rule shows how many
messages it matched on the running servers. The
page walks the three-step model — pipeline selects, rule sets transform, preview
proves — with an empty-pipeline starter (sip + table connectors pre-shaped for
dialed-number selection) and a built-in help dialog explaining connectors, selectors,
match strategies, rule filters, the `when` grammar, and the operation families.
Connector, selector, authentication, rule and operation forms are all generated from
the service's JSON Schema (`_schemas/crud.jschema`), so new types and filters appear
without editor changes. Save re-parses the document as the CRUD service would, so a
config the service couldn't load is rejected. It also compiles every `when`, status
range, regex, XPath and JsonPath, which on a live call would only fail quietly, and
lists what doesn't compile; each preview refreshes that list. Save then encrypts `{CLEARTEXT}` credentials, and writes
through the framework's `VersionedFileStore` (previous versions kept and restorable from
the editor's history panel). Publish tells every node running the CRUD service to
reload, via the framework's `ConfigPublisher` MBean path.

## Rules inside an application

A v3 application runs the same rules from a `crud` section of its own configuration, on
every message it sends, with no CRUD service chained in front of it in FSMAR. The
editor's *Rules for* picker lists every application whose generated schema carries that
section, which today means any configuration on the v3 base that has started on a node.
Pick one and the editor works on that section alone: Save splices it into `<app>.json`
and writes the rest of the file back as it was, and Publish reloads that application.
In-application rules run on every message the application sends. A rule whose `event`
names `receive` runs instead on each message as it arrives, before the application sees
it. Narrow a rule with `method`, `statusRange`, `leg` (`caller` or `callee`), `sdp`
(`offer` or `answer`) and `when`. The full filter and operation reference is the
`v3.crud` package javadoc.

## The preview

Two dry-runs, both against the live config. **Selection** (`Auto-select from message`)
runs the enrichment pipeline against a pasted SIP request and reports which rule set it
picks, folding the extracted variables into the preview's *Inputs*. **Rule preview**
replays a sample SIP message through a chosen rule set and returns the transformed
message, the ids of the rules that fired, and the final session-variable snapshot. It loads the **live** CRUD configuration (`config/custom/vorpal/crud.json`) on
every request, so edits published elsewhere land without a redeploy; when no config exists
yet it falls back to the framework's built-in sample. The preview engine itself is the
framework's `v3.crud` machinery — the same code that runs in the
[CRUD service](../../services/crud/README.md), so what you preview is what production
does.

## Tests

*Save as test* keeps a preview's inputs and its result (refusal and output message) in
`config/custom/vorpal/_tests/<app>.json`. Saving the domain file runs the domain's tests
and refuses on a failure; a cluster or server file keeps its own. Back the `_tests`
directory up with the rest of `config/custom/vorpal`; engines never read it.

## Describe a rule

With the domain's AI assistant on, a rule set has a **Describe a rule** box: say what the rule
should do, in plain words, and Claude drafts it. It runs each candidate on the preview's sample
message and against the saved tests before proposing it. The proposal lands in the rule set as
an ordinary unsaved edit, marked as an AI draft; Save it, with all of Save's checks, or discard
it.

It is off until `blade-ai` is turned on and given an Anthropic API key, in the Configurator or by
`./install.sh <env> ai` (the same key serves the Configurator's own Use AI). A draft sends the rule set, the file's
variables, the sample message and your words to the model; secrets are never sent, and phone
numbers are masked unless `maskNumbers` is turned off.

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
