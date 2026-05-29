# BLADE STIR/SHAKEN Spike

Project Bond Week-1 Task 1: prove the PASSporT verify path end-to-end with
Nimbus JOSE+JWT. **This is not a deployable library** — it is a pure JAR
used to validate the verifier shape before promoting it to `libs/stir/` and
wiring it into a service WAR (`services/stir-vs/`).

See `bond/05-prototype-roadmap.md` and `bond/03-blade-architecture-fit.md`
in the repo root for context.

## What's in the box

| Type | Purpose |
|---|---|
| `Verifier` | takes a SIP `Identity` header value + the From-header TN, returns a `VerifyResult` |
| `VerifyResult` | OK + parsed `PassPort`, or one of nine specific failure reasons |
| `PassPort` / `PassPortClaims` | immutable parsed PASSporT (header + claims) |
| `CertResolver` | abstraction over the `x5u` fetch (real HTTP is out of scope; tests inject in-memory) |
| `IdentityHeader` | parses the SIP `Identity` header syntax (`<jws>;info=<x5u>;alg=ES256;ppt=shaken`) |

Test support mints a self-signed STI-CA-shaped chain (BouncyCastle) and
signs PASSporTs with Nimbus, so the verifier round-trips on real material
without any network access or carrier paperwork.

## What's deliberately not in the box (yet)

- No SIP servlet / OCCAS deployment — that's `services/stir-vs/` in Week 2.
- No real `x5u` HTTP fetch — `CertResolver` is just an interface.
- No CRL / OCSP checking.
- No jCard parsing (RCD claim stored as a raw map for now).
- No Coherence cert cache.
- No multi-version PASSporT support — ATIS-1000074 v001 profile only.

## Running the tests

```bash
# From the spike directory:
cd blade/libs/stir-spike
../../mvnw test

# Or from the BLADE repo root, building deps as needed:
cd blade
./mvnw -pl libs/stir-spike -am test
```

No network access required. Build completes in well under a minute.

**Prerequisite:** the OCCAS/WebLogic provided-scope dependencies that the
parent POM inherits must already be installed in your local Maven repo via
`./install-occas.sh`. The spike does not touch them at runtime, but they
must resolve for the parent POM to load.

## Failure modes covered

| Case | Setup | Expected |
|---|---|---|
| Happy path | freshly signed ES256, A-attest, trusted chain | `OK` |
| Tampered sig | flip a byte in the signature after signing | `BAD_SIGNATURE` |
| Wrong orig.tn | `orig.tn` ≠ From TN passed in | `ORIG_TN_MISMATCH` |
| Stale `iat` | `iat` 120s in the past (window = 60s) | `IAT_EXPIRED` |
| Future `iat` | `iat` 120s in the future | `IAT_IN_FUTURE` |
| Missing `ppt` | strip `;ppt=shaken` | `MISSING_PPT` |
| Wrong alg | sign with RS256 instead of ES256 | `BAD_ALG` |
| Untrusted chain | leaf rooted at a CA not in the trust set | `UNTRUSTED_CHAIN` |
| Missing `x5u` | strip `;info=…` | `MISSING_X5U` |

## Promotion path

Once the verify path lands cleanly and the harvested-real corpus from
TransNexus/ATIS verifies against it (Week-1 Task 3), this module becomes
`libs/stir/` — repackaged as a shared library, added to the BLADE shared-lib
`prefer-application-packages` list, and consumed by `services/stir-vs/`.
The spike module then gets deleted.
