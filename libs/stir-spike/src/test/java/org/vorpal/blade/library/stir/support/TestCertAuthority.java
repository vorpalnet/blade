package org.vorpal.blade.library.stir.support;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/// A throwaway CA for tests. Issues EC (secp256r1) or RSA leaves under a
/// self-signed EC root. Not for production — no policy OIDs, no SHAKEN
/// TNAuthList extension; only what's required to make `Verifier` happy.
public final class TestCertAuthority {

	static {
		if (Security.getProvider("BC") == null) {
			Security.addProvider(new BouncyCastleProvider());
		}
	}

	private final KeyPair caKeyPair;
	private final X509Certificate caCertificate;

	public TestCertAuthority(String caCn) throws Exception {
		this.caKeyPair = newEcKeyPair();
		this.caCertificate = selfSignCa(caKeyPair, "CN=" + caCn);
	}

	public X509Certificate caCert() {
		return caCertificate;
	}

	public Leaf issueEcLeaf(String cn) throws Exception {
		KeyPair leafKp = newEcKeyPair();
		return new Leaf(leafKp, signLeaf(leafKp, "CN=" + cn));
	}

	public Leaf issueRsaLeaf(String cn) throws Exception {
		KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
		kpg.initialize(2048);
		KeyPair leafKp = kpg.generateKeyPair();
		return new Leaf(leafKp, signLeaf(leafKp, "CN=" + cn));
	}

	private static KeyPair newEcKeyPair() throws Exception {
		KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
		kpg.initialize(new ECGenParameterSpec("secp256r1"));
		return kpg.generateKeyPair();
	}

	private X509Certificate selfSignCa(KeyPair kp, String dn) throws Exception {
		X500Name issuer = new X500Name(dn);
		BigInteger serial = BigInteger.valueOf(System.nanoTime());
		Date notBefore = Date.from(Instant.now().minus(1, ChronoUnit.DAYS));
		Date notAfter = Date.from(Instant.now().plus(365, ChronoUnit.DAYS));
		X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
				issuer, serial, notBefore, notAfter, issuer, kp.getPublic());
		builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
		ContentSigner signer = new JcaContentSignerBuilder("SHA256withECDSA")
				.setProvider("BC").build(kp.getPrivate());
		return new JcaX509CertificateConverter().setProvider("BC")
				.getCertificate(builder.build(signer));
	}

	private X509Certificate signLeaf(KeyPair leafKp, String dn) throws Exception {
		X500Name issuer = new JcaX509CertificateHolder(caCertificate).getSubject();
		X500Name subject = new X500Name(dn);
		BigInteger serial = BigInteger.valueOf(System.nanoTime());
		Date notBefore = Date.from(Instant.now().minus(1, ChronoUnit.DAYS));
		Date notAfter = Date.from(Instant.now().plus(30, ChronoUnit.DAYS));
		X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
				issuer, serial, notBefore, notAfter, subject, leafKp.getPublic());
		builder.addExtension(Extension.basicConstraints, false, new BasicConstraints(false));
		ContentSigner signer = new JcaContentSignerBuilder("SHA256withECDSA")
				.setProvider("BC").build(caKeyPair.getPrivate());
		return new JcaX509CertificateConverter().setProvider("BC")
				.getCertificate(builder.build(signer));
	}

	public static final class Leaf {
		public final KeyPair keyPair;
		public final X509Certificate certificate;

		Leaf(KeyPair keyPair, X509Certificate certificate) {
			this.keyPair = keyPair;
			this.certificate = certificate;
		}
	}
}
