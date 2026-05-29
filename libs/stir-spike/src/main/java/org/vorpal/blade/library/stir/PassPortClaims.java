package org.vorpal.blade.library.stir;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// Parsed PASSporT claim set per ATIS-1000074 (SHAKEN).
///
/// `rcd` is the optional Rich Call Data claim from
/// draft-ietf-stir-passport-rcd; stored as a raw map for now and parsed
/// lazily once the verifier learns to consume it.
public final class PassPortClaims {

	private final String attest;
	private final List<String> dest;
	private final long iat;
	private final String origTn;
	private final String origid;
	private final Map<String, Object> rcd;

	public PassPortClaims(String attest, List<String> dest, long iat,
			String origTn, String origid, Map<String, Object> rcd) {
		this.attest = attest;
		this.dest = dest == null ? Collections.emptyList()
				: Collections.unmodifiableList(dest);
		this.iat = iat;
		this.origTn = Objects.requireNonNull(origTn, "origTn");
		this.origid = origid;
		this.rcd = rcd == null ? null : Collections.unmodifiableMap(rcd);
	}

	public String getAttest()           { return attest; }
	public List<String> getDest()       { return dest; }
	public long getIat()                { return iat; }
	public String getOrigTn()           { return origTn; }
	public String getOrigid()           { return origid; }
	public Map<String, Object> getRcd() { return rcd; }
}
