package org.vorpal.blade.framework.v3.configuration.selectors;

import java.io.IOException;
import java.io.Serializable;
import java.util.Map;

import javax.servlet.sip.SipApplicationSession;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.framework.v3.configuration.Context;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

/// Pulls a value out of whatever payload its parent
/// [org.vorpal.blade.framework.v3.configuration.adapters.Adapter]
/// produced and writes it to the SIP session via the [Context].
///
/// Concrete selectors choose their own extraction technique:
///
/// - [AttributeSelector] — named SIP header / Map field
/// - [JsonSelector] — JsonPath
/// - [XmlSelector] — XPath
/// - [SdpSelector] — SDP field code
/// - [RegexSelector] — regex with named groups + expression template
///
/// They are peers — none extends another. If you need regex parsing
/// on top of a JSON-extracted value, chain a [JsonSelector] (writes
/// its result into the session) followed by a [RegexSelector]
/// (reads from the session attribute by name and applies its pattern).
@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type",
		defaultImpl = AttributeSelector.class)
@JsonSubTypes({
		@JsonSubTypes.Type(value = AttributeSelector.class, name = "attribute"),
		@JsonSubTypes.Type(value = JsonSelector.class, name = "json"),
		@JsonSubTypes.Type(value = XmlSelector.class, name = "xml"),
		@JsonSubTypes.Type(value = SdpSelector.class, name = "sdp"),
		@JsonSubTypes.Type(value = RegexSelector.class, name = "regex")
})
@JsonPropertyOrder({ "type", "id", "description", "attribute", "index", "applicationSession" })
public abstract class Selector implements Serializable {
	private static final long serialVersionUID = 1L;

	protected String id;
	protected String description;
	protected String attribute;
	protected boolean index = false;
	protected boolean applicationSession = false;

	public Selector() {
	}

	@JsonPropertyDescription("Unique identifier; also the default session attribute name for the extracted value")
	public String getId() { return id; }
	public void setId(String id) { this.id = id; }

	@JsonPropertyDescription("Human-readable description")
	public String getDescription() { return description; }
	public void setDescription(String description) { this.description = description; }

	@JsonPropertyDescription("What to extract: header name / JsonPath / XPath / SDP field / column / source attribute")
	public String getAttribute() { return attribute; }
	public void setAttribute(String attribute) { this.attribute = attribute; }

	@JsonPropertyDescription("If true, register the extracted value as a SipApplicationSession index key")
	public boolean isIndex() { return index; }
	public void setIndex(boolean index) { this.index = index; }

	@JsonPropertyDescription("If true, write to SipApplicationSession; otherwise to SipSession")
	public boolean isApplicationSession() { return applicationSession; }
	public void setApplicationSession(boolean applicationSession) { this.applicationSession = applicationSession; }

	/// Subclasses implement this to read from `payload` (whatever the
	/// parent adapter provided) and call [#store] with the resulting
	/// value. Errors are swallowed by the parent adapter.
	public abstract void extract(Context ctx, Object payload);

	/// Write a value to the session (App or Sip depending on
	/// `applicationSession`), and optionally register it as a
	/// SipApplicationSession index key.
	///
	/// `${var}` placeholders in the value are resolved against the
	/// current session state (via [Context#resolve]) before storage.
	protected void store(Context ctx, String name, String value) {
		if (name == null || value == null || ctx == null) return;

		if (applicationSession) {
			ctx.putAppSession(name, value);
		} else {
			ctx.put(name, value);
		}

		if (index) {
			// "_index_<name>" — the Configurator's REST lookup uses
			// these keys to find sessions by attribute.
			ctx.putAppSession("_index_" + name, value);
		}
	}

	/// Read a raw string value identified by `name` from the adapter
	/// payload:
	///
	/// - `Map<String,String>` payload (REST/JDBC/LDAP/Map adapters):
	///   name is a map key.
	/// - [SipServletRequest] payload (SipAdapter): name is a SIP
	///   header name, with special pseudo-headers `Request-URI`,
	///   `Remote-IP`, `content`/`body` handled directly.
	///
	/// Returns null if `payload` isn't recognized or the name isn't
	/// present.
	@SuppressWarnings("unchecked")
	public static String readSource(Object payload, String name) {
		if (name == null) return null;

		if (payload instanceof Map) {
			Object v = ((Map<String, Object>) payload).get(name);
			return (v != null) ? v.toString() : null;
		}

		if (payload instanceof SipServletRequest) {
			SipServletRequest request = (SipServletRequest) payload;
			try {
				switch (name) {
				case "Request-URI":
				case "requestURI":
				case "RequestURI":
				case "ruri":
					return request.getRequestURI() != null ? request.getRequestURI().toString() : null;

				case "Remote-IP":
				case "remoteIP":
					String addr = request.getRemoteAddr();
					return (addr != null) ? addr : "127.0.0.1";

				case "content":
				case "Content":
				case "body":
				case "Body":
					if (request.getContent() == null) return null;
					if (request.getContent() instanceof String) return (String) request.getContent();
					return new String((byte[]) request.getContent());

				default:
					return request.getHeader(name);
				}
			} catch (IOException e) {
				SettingsManager.getSipLogger().logStackTrace(e);
				return null;
			}
		}

		return null;
	}

	// Kept for convenience — some subclasses need to know the
	// SipApplicationSession directly (to null-check before writing
	// an "_index_*" key when the session machinery is rebuilding).
	// Most callers should use the Context API.
	protected static SipApplicationSession appSessionOf(Context ctx) {
		if (ctx == null || ctx.getRequest() == null) return null;
		return ctx.getRequest().getApplicationSession();
	}
}
