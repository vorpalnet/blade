# BLADE Identity and Access Management

How BLADE fits into a company's identity system, and how it decides who may hear
a call.

`SECURITY.md` is the map of the mechanisms: which descriptor carries which
constraint, how the realm is wired, where credentials are encrypted. This
document answers a different question, for a different reader: the security
architect who has to approve BLADE for a regulated workload, and the sales
engineer who has to explain it to them. Read `SECURITY.md` for how a caller
authenticates. Read this for what they are allowed to do afterwards.

> Status: the access-control layer described in §3 and §4 ships in the framework
> jar and is unit-tested. The identity provider integration in §2 is deployment
> configuration with one item still to confirm against Oracle's documentation,
> marked below. §6 is design, not code.

---

## 1. Two questions, not one

Every access-control conversation about a call centre confuses two questions
that have different answers:

**Who may administer the platform?** Deploy an application, edit a routing
configuration, restart a node, read a server log. BLADE has answered this since
3.0 with four roles, `Admin`, `Operator`, `Deployer` and `Monitor`, mapped onto
groups in the customer's directory.

**Who may hear a patient's call?** Play a recording, read a transcript, export a
file. This is a question about *content*, it is asked of individual records, and
the answer depends on the job the person does and their relationship to that
particular call.

The two are orthogonal, and BLADE keeps them orthogonal. **A platform role
grants no access to content.** A read-only `Monitor` watching a cluster has no
job-function reason to hear a patient, and a supervisor who may review their own
team's calls has no reason to redeploy an application. Systems that collapse
these two vocabularies end up handing call audio to whoever can already read a
dashboard, and cannot explain to an auditor why.

That separation is enforced in code, not by convention:
`org.vorpal.blade.framework.v3.security.AccessEvaluator` never consults
`AdminRole`, and the two name sets are disjoint. `DataPermission.fromName("Admin")`
is null, and `AdminRole.fromName("phi:play")` is null.

---

## 2. Authentication: the customer's identity provider, not BLADE's

BLADE stores no users and no passwords. It never has. Identity lives in the
corporate directory or identity provider, and BLADE reads what that system
asserts.

### On OCCAS 8.3, single sign-on is domain configuration

OCCAS 8.3 (WebLogic Server 14.1.2) ships an OpenID Connect identity assertion
provider, `oidc-identity-asserter.jar` in the WebLogic `mbeantypes` directory.
It is a full relying party, not just a token validator: it runs the
authorization-code flow with PKCE, discovers the provider's endpoints from the
issuer URL, caches the signing keys, and handles sign-out. It also accepts a
bearer token presented directly on the `Authorization` header, so the same
provider serves browsers and API clients.

Three of its behaviors decide how a deployment is configured:

| Behavior | What it means for you |
|---|---|
| It reads a `groups` claim from the ID token and turns each value into a WebLogic principal | Your identity provider must emit group membership in a claim named `groups`. The name is a constant in the provider, not a setting. |
| `VirtualUserAllowed` defaults to true | Federated users need no account in WebLogic's embedded directory. Nobody provisions users twice. |
| `UserNameTokenClaim` defaults to `upn`, `UserIDTokenClaim` to `sub` | Both are settable on the provider if your tokens name the user elsewhere. `ClockSkew` and the key-cache lifetime are settable too. |

Once groups arrive as principals, the mapping onto BLADE's four roles is the
`weblogic.xml` machinery that is already in every admin WAR. One detail is easy
to get wrong: `<externally-defined/>` means "match a realm group with the same
name," so it works only if the group in your identity provider is literally
called `Admin`. For a real corporate group name, name it instead:

```xml
<wls:security-role-assignment>
    <wls:role-name>Admin</wls:role-name>
    <wls:principal-name>ACME-BLADE-Administrators</wls:principal-name>
</wls:security-role-assignment>
```

> **To confirm before you build the recipe.** Where the per-application OpenID
> Connect client configuration is authored, meaning issuer, client id, client
> secret and redirect URL, is not documented here. The values are held per web application
> at runtime, but they appear in no descriptor schema, no domain configuration
> schema, and no management bean in the installed product. Check Oracle's
> *Administering Security for Oracle WebLogic Server* 14.1.2 before relying on
> this section. This document would rather say it does not know than guess.

### The bearer-token path, and where it is still the answer

`JwtAuthFilter` in the framework validates an `Authorization: Bearer` token
against a configured issuer and JWKS, independently of the container. It remains
the door for API clients on a deployment that has not enabled the container
provider, and `SECURITY.md` §2a's first-party token path is unaffected and still
required: a browser cannot attach an `Authorization` header to a WebSocket
handshake, and no amount of OpenID Connect changes that.

**One planned piece of work is no longer needed.** `SECURITY.md` open item 3 was
to distribute one JWT configuration to every admin WAR, because `JwtAuthFilter`
only activates where a configuration supplier is published and so guarded only
the `security` app. On 8.3 the whole admin tier is guarded by one realm-level
provider, and there is nothing to distribute.

---

## 3. Authorization: permissions, and the scope they apply to

### The permissions

Seven names, fixed in code, deliberately a ladder rather than a switch. HIPAA's
minimum-necessary standard asks for the *least* access that does the job, and a
single "may access recordings" flag cannot express that.

| Permission | Grants |
|---|---|
| `phi:list` | That a call exists: metadata, timestamps, partial identifiers |
| `phi:transcript` | Read what was said |
| `phi:play` | Hear the call, streamed |
| `phi:export` | Take a copy: download, or bulk extract |
| `phi:unredact` | See the fields a classification marks sensitive |
| `phi:audit` | Read the access log |
| `phi:breakglass` | Emergency access, always recorded as such |

Knowing a call exists, reading what was said, hearing a voice, and walking out of
the building with a file are four disclosures with four different consequences,
so they are four permissions. `phi:play` does not imply `phi:export`: playing
leaves the content inside the application, where the next access is audited too.

`phi:audit` is held by the people who audit and deliberately not by the people
being audited. An access log its subjects can read is a map of what they got away
with.

### Scope: what a role alone cannot say

"A supervisor may hear their own team's calls" is a relationship, not a role. So
a rule matches on two things at once, the caller and the record:

```yaml
rules:
  - name:   "QA reviewers hear their own queue"
    groups: [ acme-qa-reviewers ]
    match:  { queue: cardiology }
    permit: [ phi:list, phi:transcript, phi:play ]

  - name:   "Agents hear their own calls"
    match:  { agent: "${subject.name}" }
    permit: [ phi:list, phi:transcript ]

  - name:   "Compliance sees everything, and may take it away"
    groups: [ acme-compliance ]
    permit: [ phi:list, phi:transcript, phi:play, phi:export, phi:unredact ]

  - name:   "On-call may break glass"
    groups: [ acme-oncall ]
    permit: [ phi:breakglass ]
```

`${subject.name}` is the caller's own name, so the second rule gives every agent
exactly their own calls with one line and no per-user configuration.
`${subject.<attribute>}` matches one of the caller's attributes where the
deployment can supply them; on the container path it cannot, so such a rule
matches nothing rather than matching everything.

### Reading the rules

- **Deny by default, with no way to say otherwise.** An empty policy grants
  nothing. Unlike the `acl` service's IP filter, which this borrows its shape
  from, there is no `defaultPermission` that an operator can set to `allow`. The
  failure mode of a configuration mistake should be a support call, not a
  disclosure.
- **First match wins**, so order is meaning. Write rules most specific first.
- **Both halves must match.** No `groups` means every caller; no `match` means
  every record; neither means everything, which is a rule worth noticing in a
  review.
- **A missing fact never grants.** A record without the attribute a rule names
  does not match. Neither does a rule referring to a caller attribute nobody
  supplies.
- **A misspelled permission grants nothing.** It is dropped rather than rejected,
  because refusing to load a whole policy over one bad word would take a
  deployment's access down for a typo. `AccessPolicy.unknownPermissions()`
  reports what was dropped so an application can log it once at load.

### Where it is configured

`AccessPolicy` is a section of the `security` admin app's settings, edited in the
Configurator like every other BLADE configuration, versioned, and pushed from the
AdminServer to the engines by the machinery that already distributes
configuration. There is no new editor, no new distribution path, and no policy
language to learn.

### Why not WebLogic's own authorization provider

WebLogic ships an XACML authorizer and role mapper, and they were the first thing
considered. They map policy onto *resources*: URLs, bean methods, directory
names. They cannot express "recording 12345 belongs to the cardiology queue,"
because the identity of the record is not in the URL space when the policy is
written. Per-record authorization has to happen where the record is loaded.

---

## 4. The audit trail

HIPAA §164.312(b) requires recording and examining activity in systems that hold
electronic protected health information. BLADE publishes an access record onto
the event bus for every decision, as `org.vorpal.blade.access.permitted` or
`org.vorpal.blade.access.denied`.

Each record carries the actor, the permission attempted, what was reached for,
the decision, and the rule that granted it or the reason it did not.

Four properties make it an audit trail rather than a log:

1. **Refusals are published as loudly as grants.** A log of successes cannot show
   attempted overreach, which is most of what an access review is looking for. A
   run of denials against one record is precisely the signal.
2. **It never contains the content.** The record names the recording; it does not
   quote it. An audit record that carried the transcript would disclose it to
   every reader of the audit log, including the people who were refused it. The
   event has nowhere to put content, and a test enforces that.
3. **It is append-only, and not editable by its subjects.** The sink's database
   user gets `INSERT` and `SELECT` and nothing else. That is a grant, not code,
   so it is cheap to implement and easy to show an auditor.
4. **It outlives what it describes.** Access records are kept longer than the
   recordings they refer to. Confirm the retention obligation with your own
   counsel; §164.316(b)(2)(i) sets six years for required documentation, and
   whether your audit records fall under it is a question for a lawyer, not for
   this document.

Access records are deliberately **not** analytics events and do not ride the
analytics subscription. Analytics records what a call did; this records what a
person did. They answer to different readers, under different retention, with
different integrity requirements.

> **Deployment dependency.** The durable sink wants a datasource. The analytics
> service uses `jdbc/BladeAnalytics`, which is not provisioned on every
> environment yet. Until it is, access records are published to the bus and a
> subscriber has to store them.

---

## 5. What is enforced today, and what an application must still do

The framework supplies the decision, the vocabulary, and the record:

| Class | Does |
|---|---|
| `DataPermission` | The seven permission names |
| `AccessPolicy` / `AccessRule` | The operator's rules, as configuration |
| `AccessEvaluator` | The single decision point |
| `AccessDecision` | The answer, with the rule or the reason |
| `SubjectAttributes` | Who the caller is. `RealmSubjectAttributes` adapts a container subject; a validated bearer token adapts through `SubjectAttributes.of(JwtIdentity)` |
| `AccessEvent` | The audit record, and its CloudEvents envelope |

An application that serves content calls the evaluator once per request, acts on
the answer, and publishes the event either way. Nothing else decides: an
authorization rule enforced in four places is enforced in three, and the fourth
is the one an auditor finds.

One integration point is left to the deployment on purpose. Obtaining the
authenticated subject for the current thread means the container's own security
API, which is not on the framework's compile path, and putting it there would
change what every consuming repository must install before it can build. So
`RealmSubjectAttributes.of(subject, username)` takes the subject as an argument.
The username is `HttpServletRequest.getUserPrincipal().getName()`.

---

## 6. Design, not yet code

### The recording vault

There is no recording store today. The `player` service hands the media server a
recording URI the application chose and forgets it: no index, no metadata, no
retention, no mediated read path. Nothing exists to migrate, which is the
opportunity to build it correctly once.

Two rules shape it. **Media is never served from a filesystem path.** A read
goes through the application, after a decision, and emits a record either way.
**The recording URI is never chosen by the application.** An application-supplied
path is a write primitive, so the service mints it.

The index carries the record attributes §3's rules match on: identifier, call
correlator, times, participants, tenant, queue, team, agent, and a classification
label. The index schema and the policy vocabulary have to be designed together,
because one is what the other matches on.

It also owns retention. The analytics service is candid that "retention is yours,
and the default is unbounded growth. Nothing in BLADE deletes a row." For call
content that is a defect rather than a default: retention per classification,
with disposal recorded in the audit trail.

### Machine identity

§164.312(d) says "person **or entity** authentication," and about half of a media
deployment's surface is machine-to-machine. Where network reachability is the
only control today, the remedy is configuration rather than new machinery:
`certs.sh` already issues a server identity whose certificate carries both
`serverAuth` and `clientAuth` extended key usage, precisely so the same keystore
can be a client identity, and `RestConnector`'s `TlsClientConfig` already accepts
a client keystore for mutual TLS.

### Hardened deployment profile

A checklist rather than a design, for a deployment handling regulated content:

- `AddressPolicy.allowChosenAddress` defaults to true in the WebRTC phone, a
  deliberate trade for demonstrability, documented in `SECURITY.md` §2a. Set it
  false.
- The admin session timeout is 3600 seconds. Automatic logoff is addressable
  under §164.312(a)(2)(iii); an hour is long for a shared workstation.
- `<cookie-secure>` and `CONFIDENTIAL` transport guarantees are `SECURITY.md`'s
  acknowledged intent rather than the state of the tree. TLS-only is already
  reachable with `tls.only=true`.
- Three applications still serve a JAX-RS API outside their security
  constraints. See the check in `SECURITY.md`.

---

## 7. Mapping to the HIPAA Security Rule

Offered as a starting point for a conversation with the customer's compliance
office, not as a compliance opinion. The citations are the obligations this
design was built against; whether a given deployment meets them is a question for
their counsel and their risk analysis.

| Obligation | Where it lands |
|---|---|
| §164.308(a)(4) Information access management | §3. The policy is the access-authorization record, versioned and reviewable |
| §164.312(a)(1) Access control, unique user identification | §2. One federated identity, no shared accounts, no local user store |
| §164.312(a)(2)(ii) Emergency access procedure | `phi:breakglass`, which requires a stated justification and records itself distinctly |
| §164.312(a)(2)(iii) Automatic logoff | §6, session timeout. Configuration |
| §164.312(a)(2)(iv) Encryption at rest | §6, the vault. Not built |
| §164.312(b) Audit controls | §4 |
| §164.312(d) Person or entity authentication | §2 for people, §6 for machines |
| §164.312(e) Transmission security | `SECURITY.md` §6, TLS/SIPS/t3s |
| §164.502(b) Minimum necessary | §3. The permission ladder exists for this obligation |

## See also

- **[SECURITY.md](SECURITY.md)**: the authentication surfaces, the realm wiring,
  credential storage, and TLS
- **[DEPLOYING.md](DEPLOYING.md)**: deploying the admin tier and the services
