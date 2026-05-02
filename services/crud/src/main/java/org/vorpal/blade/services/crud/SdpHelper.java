package org.vorpal.blade.services.crud;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.sdp.Sdp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.spi.json.JacksonJsonNodeJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;

/// Bridges SDP text to JsonPath manipulation by way of [Sdp] (the framework's
/// native RFC 4566 model). Each operation parses the SDP, runs the JsonPath
/// over a Jackson tree built from the model, then writes the result back as
/// SDP text.
///
/// The model is lossless: fields the operation does not touch — `b=`, `i=`,
/// `u=`, `e=`, `p=`, `k=`, `r=`, `z=` — are preserved through the round trip.
public class SdpHelper implements Serializable {
	private static final long serialVersionUID = 1L;
	private static final ObjectMapper mapper = new ObjectMapper();
	private static final Configuration jsonPathConfig = Configuration.builder()
			.jsonProvider(new JacksonJsonNodeJsonProvider(mapper))
			.mappingProvider(new JacksonMappingProvider(mapper))
			.options(Option.SUPPRESS_EXCEPTIONS)
			.build();

	private SdpHelper() {
	}

	/// Parses SDP text to a JSON tree. The shape mirrors [Sdp]'s field names
	/// so JsonPath expressions work directly.
	public static JsonNode sdpToJson(String sdpText) {
		return mapper.valueToTree(Sdp.parse(sdpText));
	}

	/// Renders a JSON tree (in the [Sdp] shape) back to SDP text.
	public static String jsonToSdp(JsonNode root) throws Exception {
		Sdp sdp = mapper.treeToValue(root, Sdp.class);
		return sdp.toString();
	}

	public static String readValue(String sdpText, String jsonPath) {
		JsonNode tree = sdpToJson(sdpText);
		DocumentContext ctx = JsonPath.using(jsonPathConfig).parse(tree);
		Object result = ctx.read(jsonPath);
		return stringify(result);
	}

	public static String updateValue(String sdpText, String jsonPath, String value) throws Exception {
		JsonNode tree = sdpToJson(sdpText);
		DocumentContext ctx = JsonPath.using(jsonPathConfig).parse(tree);
		ctx.set(jsonPath, MessageHelper.jsonOrString(value));
		return jsonToSdp(ctx.json());
	}

	public static String createValue(String sdpText, String parentPath, String key, Object value) throws Exception {
		JsonNode tree = sdpToJson(sdpText);
		DocumentContext ctx = JsonPath.using(jsonPathConfig).parse(tree);
		Object resolved = (value instanceof String) ? MessageHelper.jsonOrString((String) value) : value;
		if (key != null) ctx.put(parentPath, key, resolved);
		else ctx.add(parentPath, resolved);
		return jsonToSdp(ctx.json());
	}

	public static String deleteValue(String sdpText, String jsonPath) throws Exception {
		JsonNode tree = sdpToJson(sdpText);
		DocumentContext ctx = JsonPath.using(jsonPathConfig).parse(tree);
		ctx.delete(jsonPath);
		return jsonToSdp(ctx.json());
	}

	private static String stringify(Object result) {
		if (result == null) return null;
		if (result instanceof JsonNode) {
			JsonNode n = (JsonNode) result;
			if (n.isMissingNode() || n.isNull()) return null;
			if (n.isValueNode()) return n.asText();
			return n.toString();
		}
		return result.toString();
	}
}
