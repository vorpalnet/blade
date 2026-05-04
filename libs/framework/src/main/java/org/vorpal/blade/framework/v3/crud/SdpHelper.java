package org.vorpal.blade.framework.v3.crud;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.sdp.Sdp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;

/// Bridges SDP text to JsonPath manipulation by way of [Sdp] (the framework's
/// native RFC 4566 model). Each operation parses the SDP, runs the JsonPath
/// over a Java-collections view of the model, then writes the result back as
/// SDP text.
///
/// **Why Java collections** — `JacksonJsonNodeJsonProvider` has a long-running
/// bug where `delete` against a filter result (e.g. `$.media[0].attributes[?(...)]`)
/// throws `ClassCastException: ArrayNode cannot be cast to List` inside
/// `JsonContext.delete`. Converting through `Map`/`List` via
/// [ObjectMapper#convertValue] avoids that path entirely. The conversion cost
/// is negligible for SDP-sized payloads.
///
/// The model is lossless: fields the operation does not touch — `b=`, `i=`,
/// `u=`, `e=`, `p=`, `k=`, `r=`, `z=` — are preserved through the round trip.
public class SdpHelper implements Serializable {
	private static final long serialVersionUID = 1L;
	private static final ObjectMapper mapper = new ObjectMapper();
	private static final Configuration jsonPathConfig = Configuration.builder()
			.options(Option.SUPPRESS_EXCEPTIONS)
			.build();

	private SdpHelper() {
	}

	/// Parses SDP text to a Jackson tree. Public so callers (e.g. tests
	/// and the legacy v2 SdpHelper API surface) can inspect the parsed
	/// shape directly. The internal pipeline uses Java collections for
	/// JsonPath compatibility — see the class javadoc.
	public static JsonNode sdpToJson(String sdpText) {
		return mapper.valueToTree(Sdp.parse(sdpText));
	}

	/// Renders a Jackson tree (in the [Sdp] shape) back to SDP text.
	public static String jsonToSdp(JsonNode root) throws Exception {
		Sdp sdp = mapper.treeToValue(root, Sdp.class);
		return sdp.toString();
	}

	public static String readValue(String sdpText, String jsonPath) {
		Object root = sdpToCollection(sdpText);
		DocumentContext ctx = JsonPath.using(jsonPathConfig).parse(root);
		Object result = ctx.read(jsonPath);
		return stringify(result);
	}

	public static String updateValue(String sdpText, String jsonPath, String value) throws Exception {
		Object root = sdpToCollection(sdpText);
		DocumentContext ctx = JsonPath.using(jsonPathConfig).parse(root);
		ctx.set(jsonPath, splice(MessageHelper.jsonOrString(value)));
		return collectionToSdp(ctx.json());
	}

	public static String createValue(String sdpText, String parentPath, String key, Object value) throws Exception {
		Object root = sdpToCollection(sdpText);
		DocumentContext ctx = JsonPath.using(jsonPathConfig).parse(root);
		Object resolved = (value instanceof String) ? MessageHelper.jsonOrString((String) value) : value;
		Object spliced = splice(resolved);
		if (key != null) ctx.put(parentPath, key, spliced);
		else ctx.add(parentPath, spliced);
		return collectionToSdp(ctx.json());
	}

	public static String deleteValue(String sdpText, String jsonPath) throws Exception {
		Object root = sdpToCollection(sdpText);
		DocumentContext ctx = JsonPath.using(jsonPathConfig).parse(root);
		ctx.delete(jsonPath);
		return collectionToSdp(ctx.json());
	}

	/// SDP → Map/List tree (what JsonPath natively works with).
	private static Object sdpToCollection(String sdpText) {
		return mapper.convertValue(Sdp.parse(sdpText), Object.class);
	}

	/// Map/List tree → SDP text.
	private static String collectionToSdp(Object root) {
		Sdp sdp = mapper.convertValue(root, Sdp.class);
		return sdp.toString();
	}

	/// Make sure values being spliced into the tree are themselves
	/// Java collections, not Jackson nodes — the default JsonPath
	/// provider doesn't know how to descend a JsonNode.
	private static Object splice(Object value) {
		if (value instanceof JsonNode) {
			return mapper.convertValue(value, Object.class);
		}
		return value;
	}

	private static String stringify(Object result) {
		if (result == null) return null;
		if (result instanceof JsonNode) {
			JsonNode n = (JsonNode) result;
			if (n.isMissingNode() || n.isNull()) return null;
			if (n.isValueNode()) return n.asText();
			return n.toString();
		}
		// Maps and Lists must serialize as JSON, not Java's
		// `{key=value}` toString — values saved here are read back later
		// through MessageHelper.jsonOrString and need to be valid JSON
		// for the save-and-restore-across-messages pattern to work.
		if (result instanceof java.util.Map || result instanceof java.util.List) {
			try {
				return mapper.writeValueAsString(result);
			} catch (Exception e) {
				return result.toString();
			}
		}
		return result.toString();
	}
}
