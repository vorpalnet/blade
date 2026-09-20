package org.vorpal.blade.framework.v3.configuration.selectors;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.ListIterator;
import java.util.Locale;
import java.util.Map;

import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v3.configuration.Context;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/// Reads a STIR `Identity` header (RFC 8224) and stores the claims of its
/// SHAKEN PASSporT (RFC 8225, RFC 8588) in the context.
///
/// Stored under this selector's `id` (shown here as `stir`):
///
/// | Variable | Source | Example |
/// |---|---|---|
/// | `${stir}` | `attest` claim | `A` |
/// | `${stir.attest}` | `attest` claim | `A` |
/// | `${stir.origTn}` | `orig.tn` | `18165551234` |
/// | `${stir.destTn}` | first `dest.tn` | `18005550001` |
/// | `${stir.origid}` | `origid` | a UUID naming the originating carrier's customer |
/// | `${stir.iat}` | `iat` | epoch seconds |
/// | `${stir.age}` | now minus `iat` | seconds; RFC 8224 treats more than 60 as stale |
/// | `${stir.ppt}` | `ppt` parameter or JWS header | `shaken` |
/// | `${stir.x5u}` | `info` parameter or JWS header | the signing certificate URL |
///
/// No `Identity` header, or none that decodes, stores nothing, so a condition
/// such as `${stir} == ''` catches an unsigned call.
///
/// ## Decoded, not verified
///
/// This selector does not check the signature. Verification needs the
/// certificate at `x5u`, a trusted STI-CA list and a clock, and in most
/// networks the terminating carrier's verification service has already done
/// it, reporting the result as `verstat` on the P-Asserted-Identity or From
/// URI. Read that with a [RegexSelector] and trust these claims only when it
/// says `TN-Validation-Passed`. Without it they are what the originating
/// carrier asserted, which is still useful: `${stir.origTn}` differing from
/// the From number, or `${stir.age}` in the hundreds, are signals on their own.
///
/// A call can carry several `Identity` headers (SHAKEN, `div` for a
/// diversion, `rcd` for rich call data). On a SIP request every instance is
/// read and the first one with an `attest` claim wins.
@JsonPropertyOrder({ "type", "id", "attribute" })
public class IdentitySelector extends Selector implements Serializable {
	private static final long serialVersionUID = 1L;

	private static final ObjectMapper MAPPER = new ObjectMapper();

	public IdentitySelector() {
		this.attribute = "Identity";
	}

	public IdentitySelector(String id) {
		this.id = id;
		this.attribute = "Identity";
	}

	@Override
	public void extract(Context ctx, Object payload) {
		String name = (attribute != null) ? attribute : "Identity";

		if (payload instanceof SipServletRequest) {
			@SuppressWarnings("unchecked")
			ListIterator<String> it = ((SipServletRequest) payload).getHeaders(name);
			while (it != null && it.hasNext()) {
				if (storeClaims(ctx, parse(it.next()))) return;
			}
			return;
		}

		storeClaims(ctx, parse(readSource(payload, name)));
	}

	private boolean storeClaims(Context ctx, Map<String, String> claims) {
		if (claims == null || claims.get("attest") == null) return false;
		store(ctx, id, claims.get("attest"));
		if (id != null) {
			for (Map.Entry<String, String> e : claims.entrySet()) {
				store(ctx, id + "." + e.getKey(), e.getValue());
			}
		}
		return true;
	}

	/// Decodes one `Identity` header value into its claims: `attest`, `origTn`,
	/// `destTn`, `origid`, `iat`, `age`, `ppt`, `x5u`, `alg`. Absent claims are
	/// left out. Returns null when the value is not a compact JWS whose payload
	/// is JSON.
	public static Map<String, String> parse(String headerValue) {
		if (headerValue == null) return null;

		String[] parts = headerValue.trim().split(";");
		String[] jws = parts[0].trim().split("\\.", -1);
		if (jws.length != 3) return null;

		Map<String, String> claims = new LinkedHashMap<>();
		try {
			JsonNode header = decode(jws[0]);
			JsonNode body = decode(jws[1]);

			put(claims, "attest", body.path("attest"));
			put(claims, "origTn", body.path("orig").path("tn"));
			JsonNode dest = body.path("dest").path("tn");
			put(claims, "destTn", dest.isArray() ? dest.path(0) : dest);
			put(claims, "origid", body.path("origid"));
			JsonNode iat = body.path("iat");
			if (iat.canConvertToLong()) {
				claims.put("iat", Long.toString(iat.asLong()));
				claims.put("age", Long.toString(System.currentTimeMillis() / 1000 - iat.asLong()));
			}

			put(claims, "ppt", header.path("ppt"));
			put(claims, "x5u", header.path("x5u"));
			put(claims, "alg", header.path("alg"));
		} catch (Exception e) {
			return null;
		}

		// Header parameters override the JWS header; RFC 8224 makes info= the
		// authoritative certificate location.
		for (int i = 1; i < parts.length; i++) {
			String p = parts[i].trim();
			int eq = p.indexOf('=');
			if (eq <= 0) continue;
			String key = p.substring(0, eq).trim().toLowerCase(Locale.ROOT);
			String value = unquote(p.substring(eq + 1).trim());
			switch (key) {
			case "info":
				claims.put("x5u", value);
				break;
			case "ppt":
			case "alg":
				claims.put(key, value);
				break;
			default:
				break;
			}
		}
		return claims;
	}

	private static JsonNode decode(String base64url) throws Exception {
		byte[] bytes = Base64.getUrlDecoder().decode(base64url);
		return MAPPER.readTree(new String(bytes, StandardCharsets.UTF_8));
	}

	private static void put(Map<String, String> claims, String name, JsonNode node) {
		if (node != null && node.isValueNode() && !node.isNull()) {
			claims.put(name, node.asText());
		}
	}

	private static String unquote(String v) {
		if (v.length() >= 2 && ((v.startsWith("<") && v.endsWith(">"))
				|| (v.startsWith("\"") && v.endsWith("\"")))) {
			return v.substring(1, v.length() - 1);
		}
		return v;
	}
}
