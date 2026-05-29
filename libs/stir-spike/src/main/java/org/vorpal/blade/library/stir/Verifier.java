package org.vorpal.blade.library.stir;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import org.vorpal.blade.library.stir.internal.IdentityHeader;

import java.security.GeneralSecurityException;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.vorpal.blade.library.stir.VerifyResult.Reason.*;

/// Verifies a SIP `Identity` header carrying a SHAKEN PASSporT.
///
/// Pure library — no SIP API, no WebLogic. `services/stir-vs/` will wrap
/// this with a SipServlet and stamp the result onto session attributes.
public final class Verifier {

	private static final String PPT_SHAKEN = "shaken";
	private static final String ALG_ES256 = "ES256";
	private static final Duration DEFAULT_IAT_WINDOW = Duration.ofSeconds(60);

	private final CertResolver certResolver;
	private final Set<TrustAnchor> trustAnchors;
	private final Duration iatWindow;
	private final Clock clock;

	public Verifier(CertResolver certResolver, Set<X509Certificate> trustedRoots) {
		this(certResolver, trustedRoots, DEFAULT_IAT_WINDOW, Clock.systemUTC());
	}

	public Verifier(CertResolver certResolver, Set<X509Certificate> trustedRoots,
			Duration iatWindow, Clock clock) {
		this.certResolver = Objects.requireNonNull(certResolver);
		this.trustAnchors = new HashSet<>();
		for (X509Certificate root : Objects.requireNonNull(trustedRoots)) {
			this.trustAnchors.add(new TrustAnchor(root, null));
		}
		this.iatWindow = Objects.requireNonNull(iatWindow);
		this.clock = Objects.requireNonNull(clock);
	}

	public VerifyResult verify(String identityHeaderValue, String fromTn) {
		IdentityHeader ih;
		try {
			ih = IdentityHeader.parse(identityHeaderValue);
		} catch (RuntimeException e) {
			return VerifyResult.failure(MALFORMED_HEADER, e.getMessage());
		}

		if (ih.getX5u() == null) {
			return VerifyResult.failure(MISSING_X5U, "info= parameter missing");
		}
		if (!PPT_SHAKEN.equals(ih.getPpt())) {
			return VerifyResult.failure(MISSING_PPT, "ppt parameter is not 'shaken' (got '" + ih.getPpt() + "')");
		}
		if (!ALG_ES256.equals(ih.getAlg())) {
			return VerifyResult.failure(BAD_ALG, "alg parameter is not ES256 (got '" + ih.getAlg() + "')");
		}

		JWSObject jws;
		try {
			jws = JWSObject.parse(ih.getJws());
		} catch (Exception e) {
			return VerifyResult.failure(MALFORMED_PASSPORT, "JWS parse: " + e.getMessage());
		}

		if (!JWSAlgorithm.ES256.equals(jws.getHeader().getAlgorithm())) {
			return VerifyResult.failure(BAD_ALG, "JWS header alg is not ES256 (got '"
					+ jws.getHeader().getAlgorithm() + "')");
		}

		List<X509Certificate> chain;
		try {
			chain = certResolver.resolve(ih.getX5u());
		} catch (Exception e) {
			return VerifyResult.failure(UNTRUSTED_CHAIN, "x5u resolution failed: " + e.getMessage());
		}
		if (chain == null || chain.isEmpty()) {
			return VerifyResult.failure(UNTRUSTED_CHAIN, "empty cert chain from x5u");
		}

		try {
			validateChain(chain);
		} catch (GeneralSecurityException e) {
			return VerifyResult.failure(UNTRUSTED_CHAIN, e.getMessage());
		}

		X509Certificate leaf = chain.get(0);
		if (!(leaf.getPublicKey() instanceof ECPublicKey)) {
			return VerifyResult.failure(BAD_ALG, "leaf cert public key is not EC");
		}

		boolean sigOk;
		try {
			sigOk = jws.verify(new ECDSAVerifier((ECPublicKey) leaf.getPublicKey()));
		} catch (Exception e) {
			return VerifyResult.failure(BAD_SIGNATURE, "verify threw: " + e.getMessage());
		}
		if (!sigOk) {
			return VerifyResult.failure(BAD_SIGNATURE, "signature did not verify against leaf cert");
		}

		PassPortClaims claims;
		try {
			claims = parseClaims(jws.getPayload().toJSONObject());
		} catch (Exception e) {
			return VerifyResult.failure(MALFORMED_PASSPORT, "claim parse: " + e.getMessage());
		}

		if (!claims.getOrigTn().equals(fromTn)) {
			return VerifyResult.failure(ORIG_TN_MISMATCH,
					"orig.tn '" + claims.getOrigTn() + "' does not match From TN '" + fromTn + "'");
		}

		long nowSec = clock.instant().getEpochSecond();
		long iat = claims.getIat();
		long window = iatWindow.getSeconds();
		if (iat < nowSec - window) {
			return VerifyResult.failure(IAT_EXPIRED,
					"iat=" + iat + " more than " + window + "s before now=" + nowSec);
		}
		if (iat > nowSec + window) {
			return VerifyResult.failure(IAT_IN_FUTURE,
					"iat=" + iat + " more than " + window + "s after now=" + nowSec);
		}

		PassPort pp = new PassPort(ih.getAlg(),
				jws.getHeader().getType() == null ? null : jws.getHeader().getType().getType(),
				ih.getPpt(), ih.getX5u(), claims);
		return VerifyResult.ok(pp);
	}

	private void validateChain(List<X509Certificate> chain) throws GeneralSecurityException {
		CertificateFactory cf = CertificateFactory.getInstance("X.509");
		CertPath path = cf.generateCertPath(new ArrayList<>(chain));
		PKIXParameters params = new PKIXParameters(trustAnchors);
		params.setRevocationEnabled(false);
		params.setDate(new Date(clock.millis()));
		CertPathValidator.getInstance("PKIX").validate(path, params);
	}

	@SuppressWarnings("unchecked")
	private static PassPortClaims parseClaims(Map<String, Object> json) {
		String attest = (String) json.get("attest");
		Object destObj = json.get("dest");
		List<String> destTns = new ArrayList<>();
		if (destObj instanceof Map) {
			Object tn = ((Map<String, Object>) destObj).get("tn");
			if (tn instanceof List) {
				for (Object o : (List<Object>) tn) destTns.add(String.valueOf(o));
			} else if (tn != null) {
				destTns.add(String.valueOf(tn));
			}
		}
		Object iatObj = json.get("iat");
		long iat = iatObj instanceof Number ? ((Number) iatObj).longValue() : 0L;

		String origTn = null;
		Object origObj = json.get("orig");
		if (origObj instanceof Map) {
			Object tn = ((Map<String, Object>) origObj).get("tn");
			if (tn != null) origTn = String.valueOf(tn);
		}
		if (origTn == null) {
			throw new IllegalArgumentException("orig.tn missing");
		}

		String origid = (String) json.get("origid");
		Map<String, Object> rcd = json.get("rcd") instanceof Map
				? (Map<String, Object>) json.get("rcd") : null;
		return new PassPortClaims(attest, destTns, iat, origTn, origid, rcd);
	}
}
