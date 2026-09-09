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
> jar and is unit-tested. The identity provider integration in §2 ships in the
> framework jar too and was proven end to end against an OCI IAM identity domain
> on 2026-09-08: a reviewer with no account on the platform signed in through the
> domain and was shown every recording the reviewer rule grants. §6 is design,
> not code.

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

### Single sign-on is the framework's own OpenID Connect login

`OidcLoginFilter`, in the framework jar, signs a browser in with any OpenID
Connect provider and accepts bearer tokens from API clients. An application
names it in `web.xml`, mapped to everything it serves, and declares no
`auth-constraint` of its own: the container checks a constraint before any
filter runs, so a constraint would demand the container's own login of every
browser and the OpenID sign-in could never start. The recordings and audit
applications are wired this way.

Which door a request comes through, in order:

1. `Authorization: Bearer <token>`: verified against the provider's keys and
   the configured issuer, no session. The API client's path.
2. A request the container already authenticated, by form, certificate or
   basic login, passes untouched.
3. With no client settings in the WAR the filter asks the container to
   authenticate the request, which is the login the descriptor's
   `login-config` names. An unconfigured deployment keeps the login it had.
4. A session holding a signed-in identity proceeds as that identity.
5. Anything else starts a login: authorization code with PKCE, a state and a
   nonce in the session, the browser to the provider. A request that does not
   accept HTML gets `401` with a JSON body, since a script's request cannot
   usefully follow a redirect to a login page.

The callback checks the state, trades the code for the ID token with the
client secret and the PKCE verifier, verifies the token with `JwtValidator`
(signature against the provider's JWKS, issuer, this client as audience,
expiry), checks the nonce, and stores the identity in a fresh session.
`/oidc/logout` under the context root drops the session and visits the
provider's end-session endpoint when it publishes one. Every sign-in is one
INFO line in the server log, with the groups as the token carried them.

**What the application sees.** The request is wrapped: `getUserPrincipal()`
is the `JwtIdentity`, and `isUserInRole(r)` is true for any value the token's
group claim carried, verbatim or through a `role.<group>=<Role>` mapping in
the settings. A `web.xml` role named `Reviewer` is therefore held by anyone in
a provider group called `Reviewer`, the same reading of "externally defined"
the container gives a realm group. Resource code resolves the caller with
`SubjectAttributes.of(JwtIdentity)`, which carries the token's groups and
string claims, so an access rule may name the customer's own group names and
match on `${subject.<claim>}`.

**Where the client settings live.** `WEB-INF/blade-oidc.properties`: `issuer`,
`clientId`, `clientSecret`, `redirectUrl`, and optionally `scope` (default
`openid`), `discoveryUrl` (when the discovery document is not at
`<issuer>/.well-known/openid-configuration`), `usernameClaim` (default `sub`),
`groupsClaim` (default `groups`), `audience` (for bearer tokens issued to
another audience) and `role.<group>` mappings. A secret has no place in a WAR
in the repository, so `deploy.sh` adds the file from
`~/.blade/<env>/oidc/<deployment-name>.properties` at deploy time; a WAR with
no such file deploys as built. The redirect URL's path is what identifies the
callback, so the scheme, host and port a proxy presents to the browser do not
have to be what the server sees.

### The container's own provider, and why it is not the default

OCCAS 8.3 (WebLogic Server 14.1.2) ships an OpenID Connect identity assertion
provider, `oidc-identity-asserter.jar` under `mbeantypes`. It is a full relying
party: authorization code with PKCE, discovery from the issuer, cached keys,
a `groups` claim turned into realm principals, and a bearer token accepted on
the `Authorization` header. `misc/configure-oidc.py` adds it to the realm, and
a settings file that says `provider=container` lands in the WAR as its
`WEB-INF/oidcAuth.properties`. The vendor lists Keycloak and Azure as the
providers it tested against.

It was taken to the last step against an OCI identity domain on 2026-09-08,
and two things stopped it that have no setting on either side:

- Its key matcher accepts only a key marked `use: sig`. An identity domain
  publishes its signing key without a `use` field, so every ID token is
  rejected as unsigned ("no key found" in the debug log, "Invalid
  OpenIDConnect token signature" in the browser). The JWKS URL comes from
  discovery and cannot be overridden.
- It requests the `openid` scope only, and reads groups from a claim named
  `groups` only. An identity domain emits its `groups` claim for the `groups`
  scope and refuses a custom claim by that name, so even a verified token
  would carry no roles.

Four more things it needed were found on the way and are worth knowing for any
provider. Its redirect URL must carry the port, `https://host:443/...`,
because the container writes the port into every request URL it rebuilds and
the provider compares the callback character for character. The proxy in
front must speak TLS to the server's SSL listener, because the container takes
the scheme from its own connection, not from `X-Forwarded-Proto`; `install.sh`
renders nginx that way now. The domain trust store must hold the public roots
(below). And `misc/configure-oidc.py` takes a server name as its last argument
to turn on that server's authentication debug, the only place the provider
says what it did with a token.

### With an OCI IAM identity domain

An identity domain is an ordinary OpenID provider with four particulars, each
met by `misc/oci-identity-domain.sh`, which registers an application, writes the
settings file above, and creates the groups and a test user:

- Its tokens carry the login id in `sub` and its groups in a `groups` claim
  that is emitted only when the `groups` scope is requested. The script writes
  `scope=openid groups`.
- Its discovery document is served under the domain's own URL while naming the
  global `https://identity.oraclecloud.com/` as issuer. The framework's login
  takes the two apart (`discoveryUrl` beside `issuer`) and enforces the issuer
  on the token's `iss` claim, where it matters. The script also sets the
  domain's own issuer attribute to the domain URL, which the container's
  provider needs and which harms nothing else; the domain keeps the previous
  issuer beside it so tokens issued before the change still verify.
- Its signing keys are private by default: the discovery document's `jwks_uri`
  answers 401 to an anonymous fetch. The script turns on the domain's public
  access to its signing certificate. The keys are public material; the switch
  only says so.
- The servers reach it over public TLS. Every OCCAS server validates outbound
  connections against the domain trust store (`blade-trust.p12`), which held
  only BLADE's own CA, so the first fetch of the discovery document failed with
  "PKIX path building failed". `certs.sh`, `make-certs.sh` and `install.sh`
  now seed that store with the JDK's public roots; an existing environment gets
  them on its next `install.sh` pass, and the servers pick the store up on
  restart.

Single sign-on means exactly that. A browser that already holds a session in
the identity domain, such as an administrator signed in to the OCI console in
the same browser, is signed in to BLADE as that person without a prompt. To
try a test user, use a private window.

The applications declare a fifth role, `Reviewer`, beside the four platform roles,
externally defined like them, so a group called `Reviewer` in the identity domain
is the reviewer role with no mapping to maintain, and a policy rule naming
`Reviewer` grants the transcript to sixty thousand agents' supervisors without one
of them being given an account here. A directory that must keep its own group
names maps them with `role.<group>=Reviewer` in the settings file.

### The first-party token path is unchanged

`JwtAuthFilter` and `SECURITY.md` §2a's first-party tokens are unaffected and
still required: a browser cannot attach an `Authorization` header to a
WebSocket handshake, and no amount of OpenID Connect changes that.
`SECURITY.md` open item 3, distributing one JWT configuration to every admin
WAR, is answered differently now: an application that wants the corporate
identity provider names `OidcLoginFilter` and gets its settings at deploy time.

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

### How a recording comes to have a department

A rule matches on the record, so something has to have written the record's
attributes down. Four steps, three of which already existed:

1. A `Selector` derives the value from the signaling and writes it to session
   state. `TableSelector` is the one that maps a dialed number or a queue onto a
   department, as configuration rather than code; `AttributeSelector` covers a
   trunk that already sends the department in a header.
2. `MediaCallflow.recordingAttributes(app, names)` copies the named session
   attributes into a map. This step exists because session state dies with the
   call and the access decision happens days later.
3. `MediaCallflow.record(mediaGroup, uri, attributes, onComplete)` passes them to
   `RecordingDestinations.describe`, which writes them down before the first byte
   of audio.
4. `RecordingArchive.attributes` reads them back at review time, and the rule
   matches on what it returns.

Three properties of step 3 are load-bearing.

**Written at the start.** A recording classified when it finishes is
unclassified while it runs, and stays unclassified forever if the node dies
mid-call. That would leave content in the store that no rule can describe.

**Written once.** The OCI implementation writes to a bucket carrying a retention
rule, so a second write is refused. Verified against the live service: an attempt
to reclassify a recording from `cardiology` to `billing` returns
`403 RetentionRuleViolation` and the stored department does not change. Nobody
can relabel a recording to widen who may hear it.

**Not written by the media server.** The media server holds a capability scoped
to the recording's prefix, so it could write this object too. The classification
is what the policy matches on, so anything that can write it can grant itself
access. The application writes it with its own credential instead, and the media
plane never holds that power.

A recording whose classification failed to write matches no rule that names an
attribute. It fails closed, reachable only through a rule with an empty `match`,
which is the compliance path and is meant to be.

### One call, several conversations

A transfer replaces the party on the far side, and the answer to "who may hear
this" changes at that instant. So the recording changes with it: the application
stops the current recording, releases its destination, and starts a new one named
by `MediaCallflow.conversationUri`. Each conversation carries its own department.

A billing reviewer is then granted the billing conversation and refused the
support conversation that preceded it in the same call, with an audit record for
each. That is minimum-necessary applied to a call rather than asserted about one.

The conversations of a call are **not** related by their identifiers, which are
flat and unique. They share a `call` attribute, stamped on every recording by
`recordingAttributes`. Keeping the relationship in an attribute rather than in
the object key means a rule can match on the call exactly as it matches on the
department, the store needs no hierarchy, and nothing about the layout has to
change to support a call that turns out to have three conversations instead of
one.

Two consequences worth stating.

**Deciding where a conversation ends is the application's job.** The media server
is told to stop writing here and start writing there. It has no notion of a
conversation, a transfer, or a department, and needs none. The application is the
only party that knows what the call means.

**Hold is not a boundary.** A call on hold, and a PCI pause over a card number,
both pause the recorder and resume into the same recording. The muted span never
reaches the muxer, so it is not in the file to be found later. A boundary is a
change of party; a pause is an absence of content.

**How the recorder knows the party changed.** Two ways, for the two places a
recorder can sit. Downstream of the transferring application, the transfer's
INVITE to the target routes through the recorder as a new initial request, and
that is the boundary. Upstream, nearer the trunk, the transfer arrives as a
re-INVITE on the far leg carrying the target's SDP, and the recorder reads the
offer's origin line: a party moving its own media keeps its `o=` username and
session id and moves only the version, so a changed origin identity is a
changed party. Then the conversation closes, the party gets a fresh leg on the
media server, and the next conversation opens on it. A change of address with
the same origin is followed as a move inside one conversation, with a gap in
the manifest saying so. Proven on 2026-09-09 with a caller that re-INVITEd
under a new origin: one call, two complete conversations, the second starting
under a second later, both under the same `call` attribute.

### Matching a caller against a record

`${subject.<attribute>}` compares a record attribute against the *caller's* own,
so one rule can cover every department:

```yaml
- name:   "Staff hear their own department's calls"
  match:  { department: "${subject.department}" }
  permit: [ phi:list, phi:play ]
```

**This works on the bearer-token path and not on the browser path.** A validated
token carries claims, so `SubjectAttributes.of(JwtIdentity)` supplies them. A
container-authenticated caller arrives as realm principals, and
`RealmSubjectAttributes.attributes()` returns empty, because a realm subject
carries group membership and not arbitrary attributes. A rule referencing a
subject attribute the deployment does not supply matches nothing, so the rule
above silently grants nothing to a browser user.

For browser callers, key the rule on the group and name the department
explicitly. It is more verbose and it is honest about what the container
supplies:

```yaml
- name:   "Cardiology reviewers"
  groups: [ acme-cardiology ]
  match:  { department: cardiology }
  permit: [ phi:list, phi:play ]
```

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
3. **It is append-only, and not editable by its subjects.** Records are held in a
   bucket under a retention rule, so the property belongs to the store rather
   than to this code or to an administrator's restraint. Attempting to rewrite a
   stored record returns `403 RetentionRuleViolation`, and so does attempting to
   delete it. That is a bucket setting, not code, so it is cheap to implement and
   easy to show an auditor.
4. **It outlives what it describes.** Access records are kept longer than the
   recordings they refer to. Confirm the retention obligation with your own
   counsel; §164.316(b)(2)(i) sets six years for required documentation, and
   whether your audit records fall under it is a question for a lawyer, not for
   this document. *(Also the store's, and the retention rule is what sets it.)*

Access records are deliberately **not** analytics events and do not ride the
analytics subscription. Analytics records what a call did; this records what a
person did. They answer to different readers, under different retention, with
different integrity requirements.

> **Where the records land.** `AuditSink` is the interface, in the framework; the
> subscriber is `proto/audit`, and it writes through whatever `AuditSink` the
> deployment installs. Gryphon's `OciAuditSink` keeps them in OCI Object Storage,
> one object per record, named `audit/yyyy/MM/dd/<millis>-<eventId>.json` so a
> period reads as a prefix listing.
>
> Object storage rather than the analytics datasource, and the reason is property
> 3. In a bucket carrying a retention rule the append-only property belongs to the
> store; with a database grant of `INSERT` and `SELECT` it belongs to whoever
> administers the grant, and they can widen it from inside without leaving a trace
> in the thing being widened.
>
> Verified against the live service, on a stored access record:
>
> ```
> rewrite a denial as a permit -> 403 RetentionRuleViolation
> delete it                    -> 403 RetentionRuleViolation
> read it back                 -> still "org.vorpal.blade.access.denied"
> ```
>
> The subscription is durable, so records queue while the application is down
> rather than being dropped, and `AuditRecorder` rethrows anything the sink
> refuses so an unstored batch is redelivered rather than acknowledged. A record
> that arrives twice lands on the same object name, and the retention rule refuses
> the second write, which is the correct outcome and is treated as success.
>
> `proto/audit` refuses to start with no `AuditSink` on the classpath. Consuming
> access records and discarding them is worse than not running: it looks like
> compliance and produces nothing.
>
> **Reading it back** is `GET /blade/audit/api/v1/audit/yyyy/MM/dd`, behind
> `phi:audit`. Verified over HTTP: a caller holding all four platform roles and
> no `phi:audit` gets `403`; granted the permission, the same caller reads the
> day. Every read publishes its own access record, because a trail that logs
> every access except accesses to itself has a hole in exactly the shape of
> someone covering their tracks.

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
| `ContainerSubject` | Finds the authenticated subject for the current thread |
| `AccessEvent` | The audit record, and its CloudEvents envelope |

An application that serves content calls the evaluator once per request, acts on
the answer, and publishes the event either way. Nothing else decides: an
authorization rule enforced in four places is enforced in three, and the fourth
is the one an auditor finds.

`RealmSubjectAttributes.of(subject, username)` takes the subject as an argument,
so an application can build a caller from any source. To get the real one, ask
`ContainerSubject.current()`. The username is
`HttpServletRequest.getUserPrincipal().getName()`.

```java
SubjectAttributes caller = RealmSubjectAttributes.of(
        ContainerSubject.current(),
        request.getUserPrincipal().getName());
```

### Redaction: found at capture, decided at read

The recorder finds protected values in each utterance as it is transcribed and
stores a redacted rendition beside the verbatim text. Two sources. Shapes: card
numbers by their check digit, social security numbers, phone numbers, account
and member identifiers, numeric and spoken dates, street addresses, each a
named kind with a regular expression the deployment can extend or replace
(`redactPatterns` in the recorder's settings, `redact` to turn it off). And the
values the call is known to involve: whatever the session attributes named in
`transcribeHintAttributes` hold, the caller's name a Selector looked up, the
member identifier the routing carried, each redacted under its own attribute
name, `[callerName]`, `[memberId]`. A name has no shape a pattern finds, and it
needs none when the recorder knows it before the first word.

The verbatim text is stored too. Redacting in storage would leave the one
reader entitled to the value unable to get it, and the audio carries it anyway.

Which rendition a reader gets is decided when they read. A transcript the
recorder marked `REDACTED` is served with each protected span as its kind in
brackets, the timed words behind it masked the same way, and the recognizer's
uncorrected text withheld. The audio is served muted: each span carries the
moment it was spoken, from the recognizer's word timing, and the frames inside
it, padded by 200 ms each side, are replaced by silent frames as the file
streams, so `phi:play` and `phi:export` hand over the call without the
numbers, in the eyes and in the ears. Asking for the stored text or the stored
audio (`?verbatim=true` on the transcript, media or export resource) is a
second permission, `phi:unredact`, evaluated and audited as its own decision.
A span the recognizer could not time cannot be muted, and the audio is then
refused rather than played with the value audible.

Measured on the rig on 2026-09-08 against a call that read out a member id, a
card and a phone number: the three spans decode as digital silence and the
speech around them is unchanged to the decibel.

What is not there: a name the call did not already know. A caller who says a
third party's name, or their own when nothing looked it up, is redacted only if
the name happens to match a shape, and a name has none. That needs an entity
model on the transcript, the same class of work as the biasing. And a long
digit string is only as good as the recognizer's digit runs: a card read out
in four groups came back as thirteen digits, which failed the check digit and
was redacted as a number rather than a card. Redacted either way.

### Searching: the catalog is a cache of the archive

`blade-catalog` holds a durable subscription to the conversation-closed event
the framework publishes when a manifest is committed, by the recording node
or by the sweep that finalises what a dead node left. The event names the
conversation and nothing else; the catalog reads the manifest and the stored
utterances back from the archive and writes rows into the analytics
database: who called whom, when, how long, every attribute the recording
carried, the holds and the media moves, the redaction kinds found, and the
redacted rendition of every utterance under a full-text index. The verbatim
text is never in the database. The protected values found are stored as keyed
hashes of their normalised form under a key the deployment mints, so an exact
match on a member id can be asked for and a copy of the catalog gives up
nothing. Rows replace rows, so a redelivered event, a re-run and a rebuild
from the archive are one operation, and a schema change is a rebuild rather
than a migration.

The review API's search combines a day range, a calling or called number by
prefix, attributes by value, a call id, redaction kinds, and words, and
returns candidates in recency order with the first matching utterance as a
snippet. Every candidate is evaluated for `phi:list` against the attributes
the catalog holds for it, the same evaluation the day listing makes, and only
the permitted ones come back. The search is audited once, as a listing, with
the query passed through the redactor first: a search for a phone number is
content, and the audit log carries none. A search by protected value is a
`phi:unredact` question, evaluated and audited as its own decision. The page
at the application's root is the reviewer's front door: search, the transcript
with protected spans as tags, the recording muted or verbatim by the same
permission.

Index rows must expire with the recordings they describe; the reaper keyed on
the bucket's retention rule is not built yet. Neither is the label layer,
`BLADE_LABEL`, which the schema carries for the classifier that will fill it.

### The one trap worth knowing about

Do not reach for `Subject.getSubject(AccessController.getContext())`. It is the
call most examples show, and here it returns nothing.

It reads identity from the JAAS access control context, which only
`Subject.doAs` populates, and a servlet request is not dispatched inside one. On
current Java it does worse than return null: that mechanism belongs to the
Security Manager, the Security Manager is disallowed by default, and the call
throws.

The failure is silent and it looks like success. No subject means no groups, so
every rule naming a group stops matching and the policy grants nothing to
anybody. A policy that denies everyone looks like a strict policy, not a broken
one. The symptom is an empty listing and a `403` for a caller you are certain
should be allowed, including one holding every platform role.

`ContainerSubject` exists to make that unavailable. It asks the container first,
falls back to `Subject.current()`, and returns null rather than throwing, so a
thread with no identity produces a clean deny and an audit record instead of a
`500`. It reaches the container's security API reflectively, which keeps it off
the framework's compile path and leaves what a consuming repository must install
unchanged.

To confirm which lookup answered on a live node, set the application's
`loggingLevel` to `FINE` and look for the caller line:

```
blade-recordings recordings: caller RealmSubjectAttributes[weblogic groups=[Administrators]] via container
```

`via container` with the expected groups is the healthy state. `via none`, or a
caller whose group set is empty when the directory says otherwise, means the
identity is not reaching the application and no policy change will fix it.

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
