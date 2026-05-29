package org.vorpal.blade.library.stir;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.vorpal.blade.library.stir.support.TestCertAuthority;
import org.vorpal.blade.library.stir.support.TestCertAuthority.Leaf;
import org.vorpal.blade.library.stir.support.TestSigner;

import java.net.URI;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifierTest {

	private static final String ORIG_TN = "+15551234567";
	private static final String DEST_TN = "+15557654321";
	private static final URI X5U = URI.create("https://test.example.org/cert/leaf.pem");

	private TestCertAuthority ca;
	private Leaf ecLeaf;
	private CertResolver resolver;
	private Verifier verifier;

	@BeforeEach
	void setUp() throws Exception {
		ca = new TestCertAuthority("Test SHAKEN CA");
		ecLeaf = ca.issueEcLeaf("Test Signer");
		resolver = uri -> Collections.singletonList(ecLeaf.certificate);
		verifier = new Verifier(resolver, Collections.singleton(ca.caCert()));
	}

	@Test
	void happyPath_validShakenPassport_verifies() throws Exception {
		String header = new TestSigner(ecLeaf.keyPair.getPrivate(), X5U)
				.signNow(ORIG_TN, DEST_TN);

		VerifyResult r = verifier.verify(header, ORIG_TN);

		assertTrue(r.isOk(), () -> "expected OK but got " + r);
		PassPort pp = r.getPassPort();
		assertNotNull(pp);
		assertEquals("ES256", pp.getAlg());
		assertEquals("shaken", pp.getPpt());
		assertEquals(X5U, pp.getX5u());
		assertEquals("A", pp.getClaims().getAttest());
		assertEquals(ORIG_TN, pp.getClaims().getOrigTn());
	}

	@Test
	void tamperedSignature_reportsBadSignature() throws Exception {
		String header = new TestSigner(ecLeaf.keyPair.getPrivate(), X5U)
				.signNow(ORIG_TN, DEST_TN);
		String tampered = TestSigner.corruptSignature(header);

		VerifyResult r = verifier.verify(tampered, ORIG_TN);

		assertFalse(r.isOk());
		assertEquals(VerifyResult.Reason.BAD_SIGNATURE, r.getReason());
	}

	@Test
	void wrongOrigTn_reportsMismatch() throws Exception {
		String header = new TestSigner(ecLeaf.keyPair.getPrivate(), X5U)
				.signNow(ORIG_TN, DEST_TN);

		VerifyResult r = verifier.verify(header, "+15559999999");

		assertEquals(VerifyResult.Reason.ORIG_TN_MISMATCH, r.getReason());
	}

	@Test
	void staleIat_reportsExpired() throws Exception {
		long iat = (System.currentTimeMillis() / 1000L) - 120;
		String header = new TestSigner(ecLeaf.keyPair.getPrivate(), X5U)
				.sign("A", ORIG_TN, DEST_TN, iat, "test-stale");

		VerifyResult r = verifier.verify(header, ORIG_TN);

		assertEquals(VerifyResult.Reason.IAT_EXPIRED, r.getReason());
	}

	@Test
	void futureIat_reportsFuture() throws Exception {
		long iat = (System.currentTimeMillis() / 1000L) + 120;
		String header = new TestSigner(ecLeaf.keyPair.getPrivate(), X5U)
				.sign("A", ORIG_TN, DEST_TN, iat, "test-future");

		VerifyResult r = verifier.verify(header, ORIG_TN);

		assertEquals(VerifyResult.Reason.IAT_IN_FUTURE, r.getReason());
	}

	@Test
	void missingPpt_reportsMissingPpt() throws Exception {
		String header = new TestSigner(ecLeaf.keyPair.getPrivate(), X5U)
				.signNow(ORIG_TN, DEST_TN);
		String stripped = header.replace(";ppt=shaken", "");

		VerifyResult r = verifier.verify(stripped, ORIG_TN);

		assertEquals(VerifyResult.Reason.MISSING_PPT, r.getReason());
	}

	@Test
	void rsaSignedPassport_reportsBadAlg() throws Exception {
		Leaf rsaLeaf = ca.issueRsaLeaf("Test RSA Signer");
		CertResolver rsaResolver = uri -> Collections.singletonList(rsaLeaf.certificate);
		Verifier rsaAwareVerifier = new Verifier(rsaResolver,
				Collections.singleton(ca.caCert()));

		String header = new TestSigner(rsaLeaf.keyPair.getPrivate(), X5U)
				.signNow(ORIG_TN, DEST_TN);

		VerifyResult r = rsaAwareVerifier.verify(header, ORIG_TN);

		assertEquals(VerifyResult.Reason.BAD_ALG, r.getReason());
	}

	@Test
	void untrustedChain_reportsUntrusted() throws Exception {
		TestCertAuthority rogueCa = new TestCertAuthority("Rogue CA");
		Leaf rogueLeaf = rogueCa.issueEcLeaf("Rogue Signer");
		CertResolver rogueResolver = uri -> Collections.singletonList(rogueLeaf.certificate);

		Verifier strictVerifier = new Verifier(rogueResolver,
				Collections.singleton(ca.caCert()));

		String header = new TestSigner(rogueLeaf.keyPair.getPrivate(), X5U)
				.signNow(ORIG_TN, DEST_TN);

		VerifyResult r = strictVerifier.verify(header, ORIG_TN);

		assertEquals(VerifyResult.Reason.UNTRUSTED_CHAIN, r.getReason());
	}

	@Test
	void missingX5u_reportsMissingX5u() throws Exception {
		String header = new TestSigner(ecLeaf.keyPair.getPrivate(), X5U)
				.signNow(ORIG_TN, DEST_TN);
		String stripped = header.replaceAll(";info=<[^>]+>", "");

		VerifyResult r = verifier.verify(stripped, ORIG_TN);

		assertEquals(VerifyResult.Reason.MISSING_X5U, r.getReason());
	}

	@Test
	void iatWindow_isConfigurable() throws Exception {
		Verifier laxVerifier = new Verifier(resolver,
				Collections.singleton(ca.caCert()),
				Duration.ofSeconds(300), Clock.systemUTC());

		long iat = (System.currentTimeMillis() / 1000L) - 200;
		String header = new TestSigner(ecLeaf.keyPair.getPrivate(), X5U)
				.sign("A", ORIG_TN, DEST_TN, iat, "test-window");

		VerifyResult r = laxVerifier.verify(header, ORIG_TN);

		assertTrue(r.isOk(), () -> "expected OK with 300s window but got " + r);
	}

	@Test
	void multipleTrustAnchors_anyMatches() throws Exception {
		TestCertAuthority other = new TestCertAuthority("Other CA");
		Set<X509Certificate> roots = new HashSet<>(Arrays.asList(ca.caCert(), other.caCert()));
		Verifier multiRoot = new Verifier(resolver, roots);

		String header = new TestSigner(ecLeaf.keyPair.getPrivate(), X5U)
				.signNow(ORIG_TN, DEST_TN);

		VerifyResult r = multiRoot.verify(header, ORIG_TN);

		assertTrue(r.isOk(), () -> "expected OK with multi-anchor trust set but got " + r);
	}
}
