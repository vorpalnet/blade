# BLADE TLS — certificates, HTTPS, t3s, and SIP TLS for OCCAS

Two scripts take an OCCAS environment from plaintext to TLS on every surface:
**admin HTTPS**, **t3s** (secured admin/cluster protocol), and **SIP TLS** (the
`sips` network channel). One identity cert with a SAN list covering every node
serves all three.

| Script | Where you run it | What it does |
|---|---|---|
| `make-certs.sh <env>` | anywhere with a JDK | Stand up a private CA, issue the identity cert, build the identity + trust PKCS12 keystores |
| `install-ssl.sh <env>` | **on the AdminServer** | Push the keystores to every node, then WLST-configure server SSL + the SIPS channel |

Both read the **same env conf as `deploy.sh`** — `build-profiles/deploy/<env>.conf`
plus the gitignored `<env>.secret`. Host facts (engine nodes, ssh user, admin
URL) and the SAN/keystore settings live in exactly one place.

## Why a private CA (not bare self-signed)

You import **one** CA cert (`blade-ca.pem`) into browsers / clients / the SBC's
trust store once. After that you can reissue server certs freely — re-run
`make-certs.sh`, redeploy, restart — and nothing has to re-trust anything. When
you're ready for a real CA, the same identity CSR goes to it instead (see
*Real-CA path* below); nothing else changes.

## Conf keys

These are added to the env conf alongside the deploy keys. See the committed
`oci.conf` for a filled-in example, and `production.secret.example` for the
passphrase keys.

```
tls.san=dns:host,ip:1.2.3.4,...   # SAN list (keytool form); the one place it lives
tls.identity.cn=...               # cert CN (default: first dns: in tls.san)
tls.identity.alias=blade-identity
tls.validity.days=825
tls.key.size=2048
tls.keystore.dir=/abs/path        # identical dir on EVERY node for the p12s
tls.ssl.port=7002                 # HTTPS + t3s (same port)
# tls.ssl.servers=...             # default: ALL servers
sip.tls.enabled=true
sip.tls.port=5061
sip.tls.versions=TLSv1.2          # OCCAS NAP default is TLSv1 — we override
sip.tls.twoway=false              # true = mTLS to the SBC
# sbc.ca.cert=/path/to/sbc-ca.pem # required when sip.tls.twoway=true
# sip.tls.servers=...             # default: members of wls.targets.cluster
```

Secrets (`<env>.secret`, or env vars): `tls.ca.passphrase` /
`BLADE_TLS_CA_PASS`, `tls.keystore.passphrase` / `BLADE_TLS_KEYSTORE_PASS`,
`tls.trust.passphrase` / `BLADE_TLS_TRUST_PASS`, plus the existing
`wls.password` / `BLADE_WLS_PASSWORD`.

## Procedure (OCI example, run on the AdminServer)

```bash
# 1. Generate the keystores (writes to tls/out/<env>/, gitignored).
./tls/make-certs.sh oci

# 2. Look before you leap.
./tls/install-ssl.sh oci --dry-run

# 3. Push keystores + configure SSL + SIP TLS.
./tls/install-ssl.sh oci

# 4. Restart the affected servers — config is read at boot:
#    - AdminServer  → HTTPS console + t3s
#    - engine tier  → SIP TLS (the sips channel)

# 5. Verify.
./tls/install-ssl.sh oci status
```

You can run a single tier: `./tls/install-ssl.sh oci keystores`, `... ssl`,
`... sip`.

## SBC trust: one-way vs mTLS

- **One-way** (`sip.tls.twoway=false`, default): the engine presents its cert;
  the SBC must trust our CA (`blade-ca.pem`). The engine does not check the SBC's
  cert.
- **mTLS** (`sip.tls.twoway=true`): also enforces the SBC's client cert. Set
  `sbc.ca.cert` to the SBC's signing CA — `make-certs.sh` imports it into the
  trust store, and `install-ssl.sh` sets `ClientCertificateEnforced` on the
  `sips` channel.

## Real-CA path (instead of the private CA)

```bash
./tls/make-certs.sh oci --csr-only          # emits tls/out/oci/blade-identity.csr
# → send the CSR to your CA; save the signed chain as
#   tls/out/oci/blade-identity-signed.pem
./tls/make-certs.sh oci                      # picks up the signed chain, assembles the keystores
```

Put the real CA's root/intermediates where the trust store needs them (drop the
root in place of, or alongside, `blade-ca.pem`).

## Cutover note: plaintext stays up

`install-ssl.sh` **enables** the SSL/SIP-TLS listeners but does **not** disable
the plaintext HTTP/t3/SIP ports — so you can verify TLS before cutting over. Once
verified:

- Point `deploy.sh` at the secure admin port — set `wls.adminurl=t3s://localhost:7002`
  in the env conf.
- Disable the plaintext listen ports (admin console, or a follow-up WLST step).

## What's verified vs not

- **Verified locally**: `make-certs.sh` end-to-end — CA creation, SAN embedding,
  CA-signed chain, trust store, and `openssl verify` of the chain.
- **Not verifiable off-box**: the WLST in `install-ssl.sh` needs a running OCCAS
  domain. The WebLogic SSL MBean attributes are standard; the `sips` protocol and
  the `EnabledProtocolVersions`-defaults-to-TLSv1 behavior were confirmed against
  the decompiled OCCAS 8.1 source (`SipConstants`, `NetworkAccessPointMBeanUtil`).
  Run `--dry-run` first, and `status` after, to confirm against your 8.3 domain.
```
