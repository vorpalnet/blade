package org.vorpal.blade.library.stir;

import java.net.URI;

/// Parsed PASSporT — JWS header essentials plus the parsed claim set.
///
/// Intentionally narrow: we keep only what verify decisions and downstream
/// callflow logic need. The original JWS string is recoverable from the
/// `Identity` header if anyone needs it later.
public final class PassPort {

	private final String alg;
	private final String typ;
	private final String ppt;
	private final URI x5u;
	private final PassPortClaims claims;

	public PassPort(String alg, String typ, String ppt, URI x5u, PassPortClaims claims) {
		this.alg = alg;
		this.typ = typ;
		this.ppt = ppt;
		this.x5u = x5u;
		this.claims = claims;
	}

	public String getAlg()             { return alg; }
	public String getTyp()             { return typ; }
	public String getPpt()             { return ppt; }
	public URI getX5u()                { return x5u; }
	public PassPortClaims getClaims()  { return claims; }
}
