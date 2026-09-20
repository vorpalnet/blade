# Call Blocking

Javadocs: `/blade/javadoc/proxy-block/` on the Admin Portal

Call Blocking screens an inbound call on its INVITE, before the call reaches an IVR or an agent. It passes the call, tags it with a header, sends it to a challenge IVR, or blocks it without telling the caller. It has no code of its own. It is the [iRouter](../irouter/README.md) shipped with a call-screening configuration, so every rule below is a row or a clause an operator edits in the [Configurator](../../admin/configurator/README.md), and a policy change needs no rebuild.

## What it checks

A number blocklist alone loses to spoofing: a robocaller shows a different real person's number on every call. The sample policy leans on signals a spoofer cannot rotate away.

| Rule | Signal | Outcome |
|---|---|---|
| Allow list | Known customers' numbers | Pass. This rule runs first, so a spoofed or mistyped block entry never stops a customer. |
| Block list | Numbers and prefixes, each with a treatment | Review voicemail, tarpit, or `603 Decline` |
| Own number | Caller ID is one of your own numbers, which never call in | Tarpit |
| Invalid number | Caller ID is not an assignable North American number | `603 Decline` |
| Carrier verification failed | `verstat=TN-Validation-Failed` from a STIR/SHAKEN verifying carrier (off by default) | Challenge IVR |
| Call rate | More than 10 calls a minute from one number, counted on each engine | Challenge IVR |
| Caller is callee | Caller ID equals the dialed number | Challenge IVR |
| Watch | Anonymous caller; with STIR/SHAKEN on, also attestation C or a PASSporT older than 60 seconds | Pass with `X-Call-Screen: watch` |

Every outcome carries `X-Call-Screen: <verdict>;reason=<rule>`, so the IVR, the agent desktop, and call records all see why. The caller number comes from P-Asserted-Identity when the carrier sends one, and from From otherwise. Numbers outside the North American Numbering Plan pass untouched.

## Block-list treatments

A blocked caller is not always a machine. Some are people harassing someone at the company, and a rejection tells them they have been found out, which can push them to new numbers or other channels. Each block entry names what happens to its caller:

| Treatment | For | What happens |
|---|---|---|
| `review` | A person who must not reach anyone, such as a harasser | Forwarded to a voicemail box a review team listens to. The caller hears an ordinary greeting and the message is kept as evidence. |
| `tarpit` | Robocall numbers and prefixes | Forwarded to an endpoint that rings and never answers. The dialer learns nothing and holds a port until it gives up. |
| `decline` | Anything to end at once | `603 Decline`: final, no reason given, and not retried by a dialer. |

An entry with no treatment is declined. The review forward carries `Diversion` and `X-Original-Request-URI` with the number that was dialed, so the voicemail system can file the message against the person targeted; check which of the two your voicemail platform reads. The tarpit endpoint must never answer: an answered robocall bills a toll-free minute, and a tarpit that answers pays for every one. Set both URIs, `sip:review@voicemail.example.com` and `sip:tarpit@media.example.com` in the sample, to real endpoints before going live.

The challenge route forwards to an IVR that asks the caller to press a key, with the original request URI in `X-Original-Request-URI`. Autodialers fail it; a real person whose number was borrowed passes.

## STIR/SHAKEN

The STIR/SHAKEN rules ship turned off. The `sip` connector's first selector, `stirShaken`, sets a switch whose expression is `false`, and every rule that reads carrier verification requires `${stirShaken} == true`. Change that expression to `true` in the Configurator to turn them on. None of it needs a signing certificate or service-provider approval: it reads what the customer's carrier already sends.

Two levels of support are in the configuration:

- **The carrier's verdict.** A verifying carrier marks the caller's URI with `verstat`. The pipeline reads it from From and P-Asserted-Identity and routes on it.
- **The PASSporT itself.** The `identity` selector decodes the `Identity` header into `${stir}` (attestation `A`, `B` or `C`), `${stir.origTn}`, `${stir.origid}` and `${stir.age}`. It does not check the signature, so trust those claims when `verstat` says `TN-Validation-Passed`.

Carriers that verify in their own network often strip `Identity` and send only `verstat`. Check a trace before writing rules on `${stir}`.

## Agent-marked spam

An agent who takes a spam call can mark the number, and the mark blocks it for a set time. Spammers rotate numbers, so the mark expires rather than blocking whoever is given the number next. The pieces ship with the app:

- `sql/spam-numbers.sql` creates the `spam_numbers` table. The agent desktop inserts a row with an `expires_at` and a `treatment`, `tarpit` by default, or `review` for a caller someone should listen to.
- `_templates/spam-numbers.sql` is the lookup. It is bundled in the WAR and copied to the domain's `_templates/` on first use.

Add a `jdbc` connector to the pipeline **before** `lists`, so the allow list still overrides a mark:

```json
{
  "type": "jdbc",
  "id": "spam",
  "dataSource": "jdbc/CallBlocking",
  "queryTemplate": "spam-numbers.sql",
  "circuitBreakerCooldownSeconds": 30,
  "selectors": [
    { "type": "attribute", "id": "listed", "attribute": "LISTED" },
    { "type": "attribute", "id": "treatment", "attribute": "TREATMENT" }
  ]
}
```

Oracle reports the columns as `LISTED` and `TREATMENT`; on MySQL or PostgreSQL use lower case. The circuit breaker skips the lookup for 30 seconds after a database failure, so an outage passes calls instead of holding each one for a connection timeout. The caller's number is bound as a query parameter, never pasted into the SQL.

## Reporting

Every decision is an analytics event: `callRouted` when the call passes or is forwarded to the challenge, the review mailbox or the tarpit, `callDeclined` when it is declined. The sample defines both with the caller, the dialed number and the `X-Call-Screen` verdict. Set `analytics.enabled` to `true` and each call puts one event on the BLADE event bus, where the analytics service stores it and any other subscriber can count blocks by reason.

## Feeding the call risk score

`X-Call-Screen` is not only for people. On a call this app passes downstream, the header is the
SIP-layer read a fraud risk score wants: a screening app has already checked the caller against the
same signals (spoofed own number, invalid number, carrier verification, anonymity, call rate) that
a downstream signaling probe would otherwise re-derive. A media-tier consumer that fuses signals
(acoustic, provenance, signaling, behaviour) reads this verdict as its signaling input instead of
re-deriving it, so the two are not double-counted and the earlier, richer read wins:

- `clear` lowers signaling concern (a trusted edge looked and found nothing);
- `watch` raises it a little;
- a blocking verdict that still arrived reads as strong.

The consumer honours the header only from a trusted BLADE hop (see `blade.trustedPeers`), never
from a caller, so the verdict cannot be forged from outside. Nothing extra to configure here: the
header this app already stamps is the contract.

A passed call also carries `X-Call-Rate`, this number's per-node call rate, so the same score can
weigh call velocity as behaviour even for a call that stayed under the challenge threshold. It is
honoured under the same trust rule.

## Deploying

Build and deploy it like any other SIP service. The WAR is `proxy-block.war`, its context root is `proxy-block`, and its SIP application name is `block`, which is the name an FSMAR `next` must use. On first deploy the sample lands in `_samples/proxy-block.json.SAMPLE`. Copy it to a live config in the Configurator, then replace the sample numbers, your own numbers, and the challenge IVR's URI.

For lists too long to edit by hand, such as a CRM's customer numbers, replace the `lists` table connector with a `jdbc` or `rest` connector that sets `${listed}`. The routing does not change.

## Upgrading from the original proxy-block

The original module was a translate-and-forward proxy configured with `callingNumbers`, `fromSelector` and `defaultRoute`. That configuration does not load in this version, and a node with no loadable configuration answers `503` to every call. Rewrite it before deploying:

- A calling-number rule becomes a `table` connector keyed on `${ani}` that sets a destination variable, plus a routing clause that forwards to it.
- A dialed-number override becomes a table keyed on `${ani}:${dnis}`, placed ahead of the calling-number table.
- A rule with several `forwardTo` targets, picked at random, has no direct equivalent. Point it at a load balancer instead.

## Related modules

- [services/irouter](../irouter/README.md): the engine this app configures
- [proto/acl](../../proto/acl/README.md): IP-level allow and deny, the network-edge complement
- [BLADE](../../README.md): project home

## Maven Coordinates

```xml
<groupId>org.vorpal.blade</groupId>
<artifactId>vorpal-blade-services-proxy-block</artifactId>
```
