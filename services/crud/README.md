# CRUD Service

Javadocs: `/blade/javadoc/crud/` on the Admin Portal

A configurable engine that creates, reads, updates, and deletes SIP headers and body parts
(SIP, XML, JSON, SDP) by rule — data-driven message manipulation without custom code.
Rules are JSON; no Java required.

## How it works

`CrudServlet` is a B2BUA built on the framework's `v3.B2buaServlet`. The WAR is
deliberately thin: the rule engine, config model, and preview machinery all live in the
framework's [v3.crud](../../libs/framework/src/main/java/org/vorpal/blade/framework/v3/README.md)
package. On an initial request the v3 enrichment pipeline runs — the same
connector/selector machinery as the iRouter — and selects a rule set by writing the
`ruleSet` context variable, falling back to `defaultRuleSet` when it writes none; the
servlet stashes that rule set on the application session. Every value the pipeline
extracts is promoted to the application session, so rule templates and `when` clauses
can reference it — `X-Trace-Id: trace-${dialedNumber}` in the sample. The rule set
then fires at every point in the call lifecycle:

`callStarted`, `callAnswered`, `callConnected`, `callCompleted`, `callDeclined`,
`callAbandoned`, `requestEvent`, `responseEvent`

Each rule names the lifecycle events it applies to, so one rule set can rewrite the
outbound INVITE one way and the final response another. A rule may also carry a `when`
expression over session variables — `${customerTier} == premium` — using the same
grammar as iRouter's conditional routing; a malformed expression never fires the rule.

## Configuration

The sample config (`config/crud.SAMPLE`) shows the full shape: a `sip` connector whose
regex selector extracts the dialed number from the To header, a `table` connector mapping
dialed numbers to the `ruleSet` variable, and example rule sets for each operation
family — plain header/body `create`/`read`/`update`/`delete`, plus `xml*` (XPath),
`json*` (JSONPath), and `sdp*` operations. A call the pipeline maps to no rule set
falls back to `defaultRuleSet`; with no default it passes through untransformed.

Author and preview rules interactively in the
[CRUD Editor](../../admin/crud-editor/README.md), which replays a sample message through a
rule set and shows the transformed result before you commit. Publish through the
[Configurator](../../admin/configurator/README.md).

## Related modules

- [admin/crud-editor](../../admin/crud-editor/README.md) — spreadsheet-style rule authoring with live preview
- [Framework v3 API](../../libs/framework/src/main/java/org/vorpal/blade/framework/v3/README.md) — home of the rule engine
- [BLADE](../../README.md) — project home

## Maven Coordinates

```xml
<groupId>org.vorpal.blade</groupId>
<artifactId>vorpal-blade-services-crud</artifactId>
```
