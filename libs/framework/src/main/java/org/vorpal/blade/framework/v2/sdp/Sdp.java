package org.vorpal.blade.framework.v2.sdp;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// RFC 4566 Session Description Protocol model. Lossless round-trip for
/// well-formed SDP: parse to [Sdp], mutate, then [#toString()] returns SDP
/// text in canonical line order.
///
/// The intended use is two-stage: parse into this model, optionally serialize
/// to JSON for JsonPath manipulation (every field has a stable name), then
/// serialize back. The model deliberately has no NIST/javax.sdp dependency.
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({ "version", "origin", "sessionName", "info", "uri", "emails", "phones",
		"connection", "bandwidths", "times", "timeZones", "key", "attributes", "media" })
public class Sdp implements Serializable {
	private static final long serialVersionUID = 1L;

	private String version = "0";
	private Origin origin;
	private String sessionName = "-";
	private String info;
	private String uri;
	private List<String> emails;
	private List<String> phones;
	private Connection connection;
	private List<Bandwidth> bandwidths;
	private List<Time> times;
	private String timeZones;
	private Key key;
	private List<Attribute> attributes;
	private List<Media> media;

	public Sdp() {
	}

	/// Parses RFC 4566 SDP text into a model. Tolerates `\r\n`, `\n`, and
	/// `\r` line endings; ignores blank lines.
	public static Sdp parse(String text) {
		if (text == null) throw new IllegalArgumentException("SDP text is null");
		Sdp sdp = new Sdp();
		Media currentMedia = null;
		Time currentTime = null;
		String[] lines = text.split("\\r\\n|\\n|\\r");
		for (String line : lines) {
			if (line.isEmpty()) continue;
			if (line.length() < 2 || line.charAt(1) != '=') continue;
			char type = line.charAt(0);
			String value = line.substring(2);
			switch (type) {
			case 'v':
				sdp.version = value;
				break;
			case 'o':
				sdp.origin = Origin.parse(value);
				break;
			case 's':
				sdp.sessionName = value;
				break;
			case 'i':
				if (currentMedia != null) currentMedia.info = value;
				else sdp.info = value;
				break;
			case 'u':
				sdp.uri = value;
				break;
			case 'e':
				if (sdp.emails == null) sdp.emails = new ArrayList<>();
				sdp.emails.add(value);
				break;
			case 'p':
				if (sdp.phones == null) sdp.phones = new ArrayList<>();
				sdp.phones.add(value);
				break;
			case 'c':
				if (currentMedia != null) currentMedia.connection = Connection.parse(value);
				else sdp.connection = Connection.parse(value);
				break;
			case 'b':
				Bandwidth bw = Bandwidth.parse(value);
				if (currentMedia != null) {
					if (currentMedia.bandwidths == null) currentMedia.bandwidths = new ArrayList<>();
					currentMedia.bandwidths.add(bw);
				} else {
					if (sdp.bandwidths == null) sdp.bandwidths = new ArrayList<>();
					sdp.bandwidths.add(bw);
				}
				break;
			case 't':
				if (sdp.times == null) sdp.times = new ArrayList<>();
				currentTime = Time.parse(value);
				sdp.times.add(currentTime);
				break;
			case 'r':
				if (currentTime != null) {
					if (currentTime.repeats == null) currentTime.repeats = new ArrayList<>();
					currentTime.repeats.add(value);
				}
				break;
			case 'z':
				sdp.timeZones = value;
				break;
			case 'k':
				if (currentMedia != null) currentMedia.key = Key.parse(value);
				else sdp.key = Key.parse(value);
				break;
			case 'a':
				Attribute a = Attribute.parse(value);
				if (currentMedia != null) {
					if (currentMedia.attributes == null) currentMedia.attributes = new ArrayList<>();
					currentMedia.attributes.add(a);
				} else {
					if (sdp.attributes == null) sdp.attributes = new ArrayList<>();
					sdp.attributes.add(a);
				}
				break;
			case 'm':
				currentMedia = Media.parse(value);
				if (sdp.media == null) sdp.media = new ArrayList<>();
				sdp.media.add(currentMedia);
				break;
			default:
				break;
			}
		}
		return sdp;
	}

	/// Renders the model back to RFC 4566 SDP text using `\r\n` line endings.
	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		appendLine(sb, "v", version != null ? version : "0");
		if (origin != null) appendLine(sb, "o", origin.format());
		appendLine(sb, "s", sessionName != null ? sessionName : "-");
		if (info != null) appendLine(sb, "i", info);
		if (uri != null) appendLine(sb, "u", uri);
		if (emails != null) for (String e : emails) appendLine(sb, "e", e);
		if (phones != null) for (String p : phones) appendLine(sb, "p", p);
		if (connection != null) appendLine(sb, "c", connection.format());
		if (bandwidths != null) for (Bandwidth b : bandwidths) appendLine(sb, "b", b.format());
		if (times == null || times.isEmpty()) {
			appendLine(sb, "t", "0 0");
		} else {
			for (Time t : times) {
				appendLine(sb, "t", t.format());
				if (t.repeats != null) for (String r : t.repeats) appendLine(sb, "r", r);
			}
		}
		if (timeZones != null) appendLine(sb, "z", timeZones);
		if (key != null) appendLine(sb, "k", key.format());
		if (attributes != null) for (Attribute a : attributes) appendLine(sb, "a", a.format());
		if (media != null) for (Media m : media) m.append(sb);
		return sb.toString();
	}

	private static void appendLine(StringBuilder sb, String type, String value) {
		sb.append(type).append('=').append(value).append("\r\n");
	}

	public String getVersion() { return version; }
	public void setVersion(String version) { this.version = version; }
	public Origin getOrigin() { return origin; }
	public void setOrigin(Origin origin) { this.origin = origin; }
	public String getSessionName() { return sessionName; }
	public void setSessionName(String sessionName) { this.sessionName = sessionName; }
	public String getInfo() { return info; }
	public void setInfo(String info) { this.info = info; }
	public String getUri() { return uri; }
	public void setUri(String uri) { this.uri = uri; }
	public List<String> getEmails() { return emails; }
	public void setEmails(List<String> emails) { this.emails = emails; }
	public List<String> getPhones() { return phones; }
	public void setPhones(List<String> phones) { this.phones = phones; }
	public Connection getConnection() { return connection; }
	public void setConnection(Connection connection) { this.connection = connection; }
	public List<Bandwidth> getBandwidths() { return bandwidths; }
	public void setBandwidths(List<Bandwidth> bandwidths) { this.bandwidths = bandwidths; }
	public List<Time> getTimes() { return times; }
	public void setTimes(List<Time> times) { this.times = times; }
	public String getTimeZones() { return timeZones; }
	public void setTimeZones(String timeZones) { this.timeZones = timeZones; }
	public Key getKey() { return key; }
	public void setKey(Key key) { this.key = key; }
	public List<Attribute> getAttributes() { return attributes; }
	public void setAttributes(List<Attribute> attributes) { this.attributes = attributes; }
	public List<Media> getMedia() { return media; }
	public void setMedia(List<Media> media) { this.media = media; }

	/// `o=` line: username, session id/version, network/address type, address.
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@JsonPropertyOrder({ "username", "sessionId", "sessionVersion", "netType", "addressType", "address" })
	public static class Origin implements Serializable {
		private static final long serialVersionUID = 1L;
		private String username = "-";
		private String sessionId = "0";
		private String sessionVersion = "0";
		private String netType = "IN";
		private String addressType = "IP4";
		private String address = "0.0.0.0";

		public Origin() {}

		public static Origin parse(String value) {
			Origin o = new Origin();
			String[] parts = value.split(" ", 6);
			if (parts.length >= 1) o.username = parts[0];
			if (parts.length >= 2) o.sessionId = parts[1];
			if (parts.length >= 3) o.sessionVersion = parts[2];
			if (parts.length >= 4) o.netType = parts[3];
			if (parts.length >= 5) o.addressType = parts[4];
			if (parts.length >= 6) o.address = parts[5];
			return o;
		}

		public String format() {
			return username + " " + sessionId + " " + sessionVersion + " "
					+ netType + " " + addressType + " " + address;
		}

		public String getUsername() { return username; }
		public void setUsername(String username) { this.username = username; }
		public String getSessionId() { return sessionId; }
		public void setSessionId(String sessionId) { this.sessionId = sessionId; }
		public String getSessionVersion() { return sessionVersion; }
		public void setSessionVersion(String sessionVersion) { this.sessionVersion = sessionVersion; }
		public String getNetType() { return netType; }
		public void setNetType(String netType) { this.netType = netType; }
		public String getAddressType() { return addressType; }
		public void setAddressType(String addressType) { this.addressType = addressType; }
		public String getAddress() { return address; }
		public void setAddress(String address) { this.address = address; }
	}

	/// `c=` line: net type, address type, address. The address may include
	/// `/TTL[/numAddrs]` for multicast — kept as a single string field so
	/// callers don't need to know about that format.
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@JsonPropertyOrder({ "netType", "addressType", "address" })
	public static class Connection implements Serializable {
		private static final long serialVersionUID = 1L;
		private String netType = "IN";
		private String addressType = "IP4";
		private String address;

		public Connection() {}

		public Connection(String netType, String addressType, String address) {
			this.netType = netType;
			this.addressType = addressType;
			this.address = address;
		}

		public static Connection parse(String value) {
			Connection c = new Connection();
			String[] parts = value.split(" ", 3);
			if (parts.length >= 1) c.netType = parts[0];
			if (parts.length >= 2) c.addressType = parts[1];
			if (parts.length >= 3) c.address = parts[2];
			return c;
		}

		public String format() {
			StringBuilder sb = new StringBuilder();
			sb.append(netType != null ? netType : "IN").append(' ');
			sb.append(addressType != null ? addressType : "IP4").append(' ');
			sb.append(address != null ? address : "0.0.0.0");
			return sb.toString();
		}

		public String getNetType() { return netType; }
		public void setNetType(String netType) { this.netType = netType; }
		public String getAddressType() { return addressType; }
		public void setAddressType(String addressType) { this.addressType = addressType; }
		public String getAddress() { return address; }
		public void setAddress(String address) { this.address = address; }
	}

	/// `b=` line: bandwidth type and value (e.g. `AS:64`, `TIAS:96000`).
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@JsonPropertyOrder({ "type", "value" })
	public static class Bandwidth implements Serializable {
		private static final long serialVersionUID = 1L;
		private String type;
		private String value;

		public Bandwidth() {}

		public Bandwidth(String type, String value) {
			this.type = type;
			this.value = value;
		}

		public static Bandwidth parse(String text) {
			Bandwidth b = new Bandwidth();
			int colon = text.indexOf(':');
			if (colon >= 0) {
				b.type = text.substring(0, colon);
				b.value = text.substring(colon + 1);
			} else {
				b.type = text;
			}
			return b;
		}

		public String format() {
			return value != null ? type + ":" + value : type;
		}

		public String getType() { return type; }
		public void setType(String type) { this.type = type; }
		public String getValue() { return value; }
		public void setValue(String value) { this.value = value; }
	}

	/// `t=` line: start and stop times. May be followed by `r=` repeat lines,
	/// kept as raw strings since they are rarely manipulated.
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@JsonPropertyOrder({ "start", "stop", "repeats" })
	public static class Time implements Serializable {
		private static final long serialVersionUID = 1L;
		private String start = "0";
		private String stop = "0";
		private List<String> repeats;

		public Time() {}

		public Time(String start, String stop) {
			this.start = start;
			this.stop = stop;
		}

		public static Time parse(String value) {
			Time t = new Time();
			String[] parts = value.split(" ", 2);
			if (parts.length >= 1) t.start = parts[0];
			if (parts.length >= 2) t.stop = parts[1];
			return t;
		}

		public String format() {
			return start + " " + stop;
		}

		public String getStart() { return start; }
		public void setStart(String start) { this.start = start; }
		public String getStop() { return stop; }
		public void setStop(String stop) { this.stop = stop; }
		public List<String> getRepeats() { return repeats; }
		public void setRepeats(List<String> repeats) { this.repeats = repeats; }
	}

	/// `k=` line: encryption key (`method[:value]`). Rarely used today.
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@JsonPropertyOrder({ "method", "value" })
	public static class Key implements Serializable {
		private static final long serialVersionUID = 1L;
		private String method;
		private String value;

		public Key() {}

		public static Key parse(String text) {
			Key k = new Key();
			int colon = text.indexOf(':');
			if (colon >= 0) {
				k.method = text.substring(0, colon);
				k.value = text.substring(colon + 1);
			} else {
				k.method = text;
			}
			return k;
		}

		public String format() {
			return value != null ? method + ":" + value : method;
		}

		public String getMethod() { return method; }
		public void setMethod(String method) { this.method = method; }
		public String getValue() { return value; }
		public void setValue(String value) { this.value = value; }
	}

	/// `a=` line: attribute name with optional value. Flag attributes
	/// (`sendrecv`, `recvonly`, etc.) leave value null.
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@JsonPropertyOrder({ "name", "value" })
	public static class Attribute implements Serializable {
		private static final long serialVersionUID = 1L;
		private String name;
		private String value;

		public Attribute() {}

		public Attribute(String name, String value) {
			this.name = name;
			this.value = value;
		}

		public static Attribute parse(String text) {
			Attribute a = new Attribute();
			int colon = text.indexOf(':');
			if (colon >= 0) {
				a.name = text.substring(0, colon);
				a.value = text.substring(colon + 1);
			} else {
				a.name = text;
			}
			return a;
		}

		public String format() {
			return value != null ? name + ":" + value : name;
		}

		public String getName() { return name; }
		public void setName(String name) { this.name = name; }
		public String getValue() { return value; }
		public void setValue(String value) { this.value = value; }
	}

	/// `m=` line plus the lines that follow up to the next `m=`.
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@JsonPropertyOrder({ "type", "port", "numPorts", "protocol", "formats", "info",
			"connection", "bandwidths", "key", "attributes" })
	public static class Media implements Serializable {
		private static final long serialVersionUID = 1L;
		private String type;
		private int port;
		private Integer numPorts;
		private String protocol;
		private List<String> formats = new LinkedList<>();
		private String info;
		private Connection connection;
		private List<Bandwidth> bandwidths;
		private Key key;
		private List<Attribute> attributes;

		public Media() {}

		public static Media parse(String value) {
			Media m = new Media();
			String[] parts = value.split(" ");
			if (parts.length < 3) throw new IllegalArgumentException("malformed m= line: " + value);
			m.type = parts[0];
			String portSpec = parts[1];
			int slash = portSpec.indexOf('/');
			if (slash >= 0) {
				m.port = Integer.parseInt(portSpec.substring(0, slash));
				m.numPorts = Integer.parseInt(portSpec.substring(slash + 1));
			} else {
				m.port = Integer.parseInt(portSpec);
			}
			m.protocol = parts[2];
			m.formats = new LinkedList<>();
			for (int i = 3; i < parts.length; i++) m.formats.add(parts[i]);
			return m;
		}

		void append(StringBuilder sb) {
			sb.append("m=").append(type).append(' ').append(port);
			if (numPorts != null) sb.append('/').append(numPorts);
			sb.append(' ').append(protocol);
			if (formats != null) for (String f : formats) sb.append(' ').append(f);
			sb.append("\r\n");
			if (info != null) sb.append("i=").append(info).append("\r\n");
			if (connection != null) sb.append("c=").append(connection.format()).append("\r\n");
			if (bandwidths != null) for (Bandwidth b : bandwidths) sb.append("b=").append(b.format()).append("\r\n");
			if (key != null) sb.append("k=").append(key.format()).append("\r\n");
			if (attributes != null) for (Attribute a : attributes) sb.append("a=").append(a.format()).append("\r\n");
		}

		public String getType() { return type; }
		public void setType(String type) { this.type = type; }
		public int getPort() { return port; }
		public void setPort(int port) { this.port = port; }
		public Integer getNumPorts() { return numPorts; }
		public void setNumPorts(Integer numPorts) { this.numPorts = numPorts; }
		public String getProtocol() { return protocol; }
		public void setProtocol(String protocol) { this.protocol = protocol; }
		public List<String> getFormats() { return formats; }
		public void setFormats(List<String> formats) { this.formats = formats; }
		public String getInfo() { return info; }
		public void setInfo(String info) { this.info = info; }
		public Connection getConnection() { return connection; }
		public void setConnection(Connection connection) { this.connection = connection; }
		public List<Bandwidth> getBandwidths() { return bandwidths; }
		public void setBandwidths(List<Bandwidth> bandwidths) { this.bandwidths = bandwidths; }
		public Key getKey() { return key; }
		public void setKey(Key key) { this.key = key; }
		public List<Attribute> getAttributes() { return attributes; }
		public void setAttributes(List<Attribute> attributes) { this.attributes = attributes; }
	}
}
