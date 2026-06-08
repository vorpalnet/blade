# Test User Agent Server (UAS)

[Javadocs](https://vorpal.net/javadocs/blade/test-uas)

A scriptable SIP test server built on the BLADE framework. It sits at the end
of a call path and plays whatever part the test needs — a ringing endpoint, a
busy endpoint, a transfer, a transforming B2BUA — letting developers and
administrators mock up SIP scenarios without standing up SIPp. It is the
counterpart to the [Test UAC](../test-uac).

## Scenario selection

Every initial INVITE resolves a **scenario**, in priority order:

| Selector | Example | Behavior |
|---|---|---|
| `scenario=` Request-URI parameter | `sip:bob@uas.test;scenario=answer-486` | Runs the named scenario from `test-uas.json` |
| Translation plan match | dialed number `8002` → `{"scenario": "answer-486"}` | Standard BLADE selectors/maps/plan (same machinery as the CRUD service; a bare `ruleSet` attribute works too) |
| Classic URI shorthands | `;status=486`, `;delay=5s`, `;refer=sip:…` | Synthesizes an ephemeral answer scenario — **fully backward compatible** with existing test scripts |
| `defaultScenario` (config) | — | Configured fallback |
| Built-in default | — | **Strip-and-forward**: B2BUA passthrough with multipart (SIPREC) bodies stripped to bare `application/sdp` |

The resolved scenario is stamped on the application session, so in-dialog
requests (re-INVITE → hold, BYE/CANCEL/INFO → 200, other → 501) follow the
same path as the initial INVITE.

## Scenario roles

### `answer` — scripted endpoint

A `responseScript` plays an ordered status sequence with per-step delays,
custom reason phrases, and SDP control; 2xx answers carry a blackhole/mute
SDP derived from the offer. After answer: optional REFER transfer and/or
auto-BYE.

```jsonc
"answer-486": {
  "role": "answer",
  "responseScript": {
    "send": [ { "status": 180, "delay": "200ms" },
              { "status": 486, "delay": "1s", "reasonPhrase": "Busy Here" } ]
  }
},
"answer-transfer": {
  "role": "answer",
  "responseScript": {
    "send": [ { "status": 200 } ],
    "refer": "sip:transfer-target@uas.test",
    "referStatus": "200"
  }
}
```

### `b2bua` — transform and forward

The call passes through; a CRUD rule set (and/or inline `rules`) transforms
requests and responses across the lifecycle — insert/delete headers, attach
or strip MIME parts, rewrite SDP/XML/JSON. The classic SIPREC strip is one
`keepPart` operation:

```jsonc
"strip-siprec-b2bua": {
  "role": "b2bua",
  "rules": [ { "id": "strip", "method": "INVITE", "messageType": "request",
               "event": "callStarted",
               "operations": [ { "type": "keepPart", "contentType": "application/sdp" } ] } ]
}
```

## URI shorthands (no configuration needed)

| Parameter | Meaning |
|---|---|
| `status` | SIP response code to send (default `200`). A 2xx answer carries a blackhole/mute SDP. |
| `delay` | For an answered call, teardown delay before `BYE`. Bare integer = ms; `ms`/`s`/`m`/`h` suffixes honored. |
| `refer` | Answer 200, then REFER the caller to this address (transfer test). |

```
sip:bob@uas.test;status=200;delay=5s   ; answer 200, BYE after 5 seconds
sip:bob@uas.test;status=404            ; reject immediately with 404
sip:bob@uas.test;refer=sip:carol@…     ; answer, then transfer the caller to Carol
sip:bob@uas.test;scenario=answer-486   ; run a configured scenario
```

## Configuration

Managed by [SettingsManager](https://vorpal.net/javadocs/blade/framework) at
`config/custom/vorpal/test-uas.json` — scenarios, rule sets, and scenario
selection are all edited schema-validated in the **Configurator**. The
generated `test-uas.json.SAMPLE` contains worked examples (scripted
rejection, ring-and-answer with auto-teardown, transfer, SIPREC strip). An
empty configuration is fine: the URI shorthands and the built-in
strip-and-forward default need nothing.

Every node also registers a `TesterControl` MBean
(`vorpal.blade:Name=test-uas,Type=TesterControl,Cluster=…`) so the **BLADE
Test Console** can read its per-scenario metrics (answered/forwarded counts,
status distributions) over federated JMX.

## Admin tooling (portal & Configurator)

test-uas registers a `SettingsManager` MBean
(`vorpal.blade:Name=test-uas,Type=Configuration`) like every BLADE app:

- **Configurator** — the dynamic form edits scenarios, rule sets, logging,
  and session parameters from the generated `_schemas/test-uas.jschema`.
- **Portal launcher deck** — test-uas is **not** a deck card, by design: the
  deck lists admin GUI apps under `blade/*` context-roots. test-uas is a SIP
  service with a flat context-root and no browser UI. Cluster test activity
  shows up in the [Test Console](../../admin/test-console) instead.

## Dependencies

- **`org.vorpal.blade:vorpal-blade-library-framework`** — BLADE framework
  providing `TesterServlet`, `ScriptedAnswer`, the CRUD rule engine,
  `SettingsManager`, and SIP container integration

## Related Modules

- [**test/test-uac**](../test-uac) — Test User Agent Client (load generator)
- [**libs/framework**](../../libs/framework) — Base framework
- [**admin/test-console**](../../admin/test-console) — cluster-wide test dashboards

## Deployment

Deploys as a WAR to Oracle OCCAS with the `vorpal-blade` shared library:

- **Context root**: `test-uas`
- **WebLogic shared library**: `vorpal-blade` (specification version 2.0)

SIPp scenarios for exercising both modes live in [`testing/`](testing) —
they keep working unchanged against the URI shorthands.

## License

Part of the Vorpal Blade SIP Servlet Container project.
