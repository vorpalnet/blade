package org.vorpal.blade.services.crud;

import java.io.Serializable;
import java.util.Vector;

import javax.sdp.Attribute;
import javax.sdp.Connection;
import javax.sdp.MediaDescription;
import javax.sdp.Origin;
import javax.sdp.SdpFactory;
import javax.sdp.SessionDescription;
import javax.sdp.SessionName;
import javax.sdp.Time;
import javax.sdp.TimeDescription;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;

/**
 * Converts SDP content to/from a clean JSON representation suitable for
 * JsonPath manipulation. Uses the NIST SDP parser for parsing and builds
 * SDP text output directly from the JSON structure.
 *
 * <p>JSON structure:
 * <pre>
 * {
 *   "version": "0",
 *   "origin": { "username":"-", "sessionId":"123", "sessionVersion":"1",
 *               "netType":"IN", "addressType":"IP4", "address":"10.0.0.1" },
 *   "sessionName": "-",
 *   "connection": { "netType":"IN", "addressType":"IP4", "address":"10.0.0.1" },
 *   "time": [{ "start":"0", "stop":"0" }],
 *   "attributes": [
 *     { "name":"group", "value":"BUNDLE audio video" }
 *   ],
 *   "media": [
 *     {
 *       "type":"audio", "port":8000, "protocol":"RTP/AVP",
 *       "formats":["0","96"],
 *       "connection": { "netType":"IN", "addressType":"IP4", "address":"10.0.0.2" },
 *       "attributes": [
 *         { "name":"rtpmap", "value":"0 PCMU/8000" },
 *         { "name":"sendrecv" }
 *       ]
 *     }
 *   ]
 * }
 * </pre>
 */
public class SdpHelper implements Serializable {
	private static final long serialVersionUID = 1L;
	private static final ObjectMapper mapper = new ObjectMapper();

	private SdpHelper() {
	}

	/**
	 * Parses an SDP string into a JSON ObjectNode.
	 */
	@SuppressWarnings("unchecked")
	public static ObjectNode sdpToJson(String sdpText) throws Exception {
		SdpFactory factory = SdpFactory.getInstance();
		SessionDescription sd = factory.createSessionDescription(sdpText);

		ObjectNode root = mapper.createObjectNode();

		// v=
		if (sd.getVersion() != null) {
			root.put("version", String.valueOf(sd.getVersion().getVersion()));
		}

		// o=
		Origin origin = sd.getOrigin();
		if (origin != null) {
			ObjectNode o = mapper.createObjectNode();
			o.put("username", origin.getUsername());
			o.put("sessionId", String.valueOf(origin.getSessionId()));
			o.put("sessionVersion", String.valueOf(origin.getSessionVersion()));
			o.put("netType", origin.getNetworkType());
			o.put("addressType", origin.getAddressType());
			o.put("address", origin.getAddress());
			root.set("origin", o);
		}

		// s=
		SessionName sn = sd.getSessionName();
		if (sn != null) {
			root.put("sessionName", sn.getValue());
		}

		// c= (session level)
		Connection conn = sd.getConnection();
		if (conn != null) {
			root.set("connection", connectionToJson(conn));
		}

		// t=
		Vector<TimeDescription> times = sd.getTimeDescriptions(false);
		if (times != null && !times.isEmpty()) {
			ArrayNode timeArray = mapper.createArrayNode();
			for (Object tdObj : times) {
				TimeDescription td = (TimeDescription) tdObj;
				Time t = td.getTime();
				ObjectNode tn = mapper.createObjectNode();
				tn.put("start", String.valueOf(t.getStart()));
				tn.put("stop", String.valueOf(t.getStop()));
				timeArray.add(tn);
			}
			root.set("time", timeArray);
		}

		// a= (session level)
		Vector<Attribute> attrs = sd.getAttributes(false);
		if (attrs != null && !attrs.isEmpty()) {
			root.set("attributes", attributesToJson(attrs));
		}

		// m= (media descriptions)
		Vector<MediaDescription> medias = sd.getMediaDescriptions(false);
		if (medias != null && !medias.isEmpty()) {
			ArrayNode mediaArray = mapper.createArrayNode();
			for (Object mdObj : medias) {
				MediaDescription md = (MediaDescription) mdObj;
				ObjectNode mn = mapper.createObjectNode();

				mn.put("type", md.getMedia().getMediaType());
				mn.put("port", md.getMedia().getMediaPort());
				mn.put("protocol", md.getMedia().getProtocol());

				// formats
				Vector formats = md.getMedia().getMediaFormats(false);
				if (formats != null) {
					ArrayNode fmts = mapper.createArrayNode();
					for (Object f : formats) {
						fmts.add(String.valueOf(f));
					}
					mn.set("formats", fmts);
				}

				// c= (media level)
				Connection mc = md.getConnection();
				if (mc != null) {
					mn.set("connection", connectionToJson(mc));
				}

				// a= (media level)
				Vector<Attribute> mattrs = md.getAttributes(false);
				if (mattrs != null && !mattrs.isEmpty()) {
					mn.set("attributes", attributesToJson(mattrs));
				}

				mediaArray.add(mn);
			}
			root.set("media", mediaArray);
		}

		return root;
	}

	/**
	 * Converts a JSON ObjectNode back to an SDP string.
	 */
	public static String jsonToSdp(JsonNode root) {
		StringBuilder sb = new StringBuilder();

		// v=
		sb.append("v=").append(textOrDefault(root, "version", "0")).append("\r\n");

		// o=
		JsonNode origin = root.get("origin");
		if (origin != null) {
			sb.append("o=")
					.append(textOrDefault(origin, "username", "-")).append(" ")
					.append(textOrDefault(origin, "sessionId", "0")).append(" ")
					.append(textOrDefault(origin, "sessionVersion", "0")).append(" ")
					.append(textOrDefault(origin, "netType", "IN")).append(" ")
					.append(textOrDefault(origin, "addressType", "IP4")).append(" ")
					.append(textOrDefault(origin, "address", "0.0.0.0"))
					.append("\r\n");
		}

		// s=
		sb.append("s=").append(textOrDefault(root, "sessionName", "-")).append("\r\n");

		// c= (session level)
		JsonNode conn = root.get("connection");
		if (conn != null) {
			appendConnection(sb, conn);
		}

		// t=
		JsonNode times = root.get("time");
		if (times != null && times.isArray()) {
			for (JsonNode t : times) {
				sb.append("t=")
						.append(textOrDefault(t, "start", "0")).append(" ")
						.append(textOrDefault(t, "stop", "0"))
						.append("\r\n");
			}
		} else {
			sb.append("t=0 0\r\n");
		}

		// a= (session level)
		JsonNode attrs = root.get("attributes");
		if (attrs != null && attrs.isArray()) {
			appendAttributes(sb, attrs);
		}

		// m= (media descriptions)
		JsonNode medias = root.get("media");
		if (medias != null && medias.isArray()) {
			for (JsonNode md : medias) {
				sb.append("m=")
						.append(textOrDefault(md, "type", "audio")).append(" ")
						.append(textOrDefault(md, "port", "0")).append(" ")
						.append(textOrDefault(md, "protocol", "RTP/AVP"));

				JsonNode formats = md.get("formats");
				if (formats != null && formats.isArray()) {
					for (JsonNode f : formats) {
						sb.append(" ").append(f.asText());
					}
				}
				sb.append("\r\n");

				// c= (media level)
				JsonNode mc = md.get("connection");
				if (mc != null) {
					appendConnection(sb, mc);
				}

				// a= (media level)
				JsonNode mattrs = md.get("attributes");
				if (mattrs != null && mattrs.isArray()) {
					appendAttributes(sb, mattrs);
				}
			}
		}

		return sb.toString();
	}

	/**
	 * Parses SDP to JSON, applies a JsonPath read, and returns the value.
	 */
	public static String readValue(String sdpText, String jsonPath) throws Exception {
		ObjectNode json = sdpToJson(sdpText);
		DocumentContext ctx = JsonPath.parse(mapper.writeValueAsString(json));
		Object result = ctx.read(jsonPath);
		return (result != null) ? result.toString() : null;
	}

	/**
	 * Parses SDP to JSON, applies a JsonPath update, and returns new SDP text.
	 */
	public static String updateValue(String sdpText, String jsonPath, String value) throws Exception {
		ObjectNode json = sdpToJson(sdpText);
		String jsonStr = mapper.writeValueAsString(json);
		DocumentContext ctx = JsonPath.parse(jsonStr);
		ctx.set(jsonPath, value);
		JsonNode modified = mapper.readTree(ctx.jsonString());
		return jsonToSdp(modified);
	}

	/**
	 * Parses SDP to JSON, adds a property via JsonPath, and returns new SDP text.
	 */
	public static String createValue(String sdpText, String parentPath, String key, Object value) throws Exception {
		ObjectNode json = sdpToJson(sdpText);
		String jsonStr = mapper.writeValueAsString(json);
		DocumentContext ctx = JsonPath.parse(jsonStr);
		ctx.put(parentPath, key, value);
		JsonNode modified = mapper.readTree(ctx.jsonString());
		return jsonToSdp(modified);
	}

	/**
	 * Parses SDP to JSON, deletes a node via JsonPath, and returns new SDP text.
	 */
	public static String deleteValue(String sdpText, String jsonPath) throws Exception {
		ObjectNode json = sdpToJson(sdpText);
		String jsonStr = mapper.writeValueAsString(json);
		DocumentContext ctx = JsonPath.parse(jsonStr);
		ctx.delete(jsonPath);
		JsonNode modified = mapper.readTree(ctx.jsonString());
		return jsonToSdp(modified);
	}

	// --- private helpers ---

	private static ObjectNode connectionToJson(Connection conn) throws Exception {
		ObjectNode c = mapper.createObjectNode();
		c.put("netType", conn.getNetworkType());
		c.put("addressType", conn.getAddressType());
		c.put("address", conn.getAddress());
		return c;
	}

	private static ArrayNode attributesToJson(Vector<Attribute> attrs) throws Exception {
		ArrayNode array = mapper.createArrayNode();
		for (Object aObj : attrs) {
			Attribute a = (Attribute) aObj;
			ObjectNode an = mapper.createObjectNode();
			an.put("name", a.getName());
			if (a.getValue() != null && !a.getValue().isEmpty()) {
				an.put("value", a.getValue());
			}
			array.add(an);
		}
		return array;
	}

	private static void appendConnection(StringBuilder sb, JsonNode conn) {
		sb.append("c=")
				.append(textOrDefault(conn, "netType", "IN")).append(" ")
				.append(textOrDefault(conn, "addressType", "IP4")).append(" ")
				.append(textOrDefault(conn, "address", "0.0.0.0"))
				.append("\r\n");
	}

	private static void appendAttributes(StringBuilder sb, JsonNode attrs) {
		for (JsonNode a : attrs) {
			String name = a.has("name") ? a.get("name").asText() : null;
			if (name == null) {
				continue;
			}
			String value = a.has("value") ? a.get("value").asText() : null;
			if (value != null && !value.isEmpty()) {
				sb.append("a=").append(name).append(":").append(value).append("\r\n");
			} else {
				sb.append("a=").append(name).append("\r\n");
			}
		}
	}

	private static String textOrDefault(JsonNode node, String field, String defaultValue) {
		if (node == null || !node.has(field)) {
			return defaultValue;
		}
		return node.get(field).asText(defaultValue);
	}
}
