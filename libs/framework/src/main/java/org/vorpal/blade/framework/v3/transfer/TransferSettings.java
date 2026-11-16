package org.vorpal.blade.framework.v3.transfer;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.vorpal.blade.framework.v2.config.RouterConfig;
import org.vorpal.blade.framework.v3.configuration.SchemaAbout;
import org.vorpal.blade.framework.v3.crud.Crud;
import org.vorpal.blade.framework.v3.crud.CrudCapable;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// The transfer service's settings: the transfer style a request gets when it
/// names none, the methods and headers carried across a transfer, the CRUD
/// rules run on every message the service sends (`crud`), and the web services
/// it reports transfer events to (`webhooks`, `defaultWebhook`).
///
/// It extends the v2 [RouterConfig] for the routing the service shares with
/// every v2-configured application, and carries the CRUD section through
/// [CrudCapable]. A configuration written for the v2 class loads unchanged.
@SchemaAbout(
		name = "Transfer",
		tagline = "Attended & Blind Call Transfer",
		description = "Implements SIP call transfer (RFC 3515 REFER) for both attended "
				+ "(consultative) and blind transfers, managing the REFER/NOTIFY signaling and "
				+ "transfer state through completion or failure.")
public class TransferSettings extends RouterConfig implements CrudCapable, Serializable {
	private static final long serialVersionUID = 3L;

	/** Default allowed SIP methods. */
	private static final String DEFAULT_ALLOW = "MESSAGE, REFER, NOTIFY, CANCEL, ACK, UPDATE, PRACK, OPTIONS, INVITE, INFO, SUBSCRIBE, BYE";

	public enum TransferStyle {
		blind, attended, conference, refer
	}

	@JsonProperty(defaultValue = "false")
	protected Boolean transferAllRequests;

	@JsonProperty(defaultValue = "attended")
	protected TransferStyle defaultTransferStyle;

	@JsonPropertyDescription("SIP application name to handle conference calls")
	protected String conferenceApp;

	protected String allow = DEFAULT_ALLOW;

	protected List<String> preserveInviteHeaders = new ArrayList<>();

	protected List<String> preserveReferHeaders = new ArrayList<>();

	@JsonProperty(defaultValue = "false")
	protected Boolean allowCallerRefer;

	protected Crud crud;

	protected Map<String, Webhook> webhooks = new LinkedHashMap<>();
	protected String defaultWebhook;

	public TransferSettings() {
	}

	/// Whether a REFER from the caller's leg may start a transfer. Off by default:
	/// the caller is outside the contact center, and a REFER from that side
	/// names any destination it likes, so honouring it lets a caller place a
	/// call to an international or premium number at the operator's expense.
	/// The contact-center platform sends its REFERs on the callee leg.
	@JsonPropertyDescription("Allow a REFER from the caller's leg to start a transfer. Default false: only the callee"
			+ " (contact-center) leg may transfer, since a caller-side REFER can name any destination.")
	public Boolean getAllowCallerRefer() {
		return allowCallerRefer;
	}

	public void setAllowCallerRefer(Boolean allowCallerRefer) {
		this.allowCallerRefer = allowCallerRefer;
	}

	@JsonPropertyDescription("List of SIP header names to preserve when forwarding REFER requests during transfer")
	public List<String> getPreserveReferHeaders() {
		return preserveReferHeaders;
	}

	public void setPreserveReferHeaders(List<String> preserveReferHeaders) {
		this.preserveReferHeaders = preserveReferHeaders;
	}

	@JsonPropertyDescription("List of SIP header names to preserve when forwarding INVITE requests during transfer")
	public List<String> getPreserveInviteHeaders() {
		return preserveInviteHeaders;
	}

	public void setPreserveInviteHeaders(List<String> preserveInviteHeaders) {
		this.preserveInviteHeaders = preserveInviteHeaders;
	}

	@JsonPropertyDescription("Comma-separated list of allowed SIP methods for transfer operations")
	public String getAllow() {
		return allow;
	}

	public TransferSettings setAllow(String allow) {
		this.allow = allow;
		return this;
	}

	@JsonPropertyDescription("When true, all requests are eligible for transfer, not just INVITE")
	public Boolean getTransferAllRequests() {
		return transferAllRequests;
	}

	public void setTransferAllRequests(Boolean transferAllRequests) {
		this.transferAllRequests = transferAllRequests;
	}

	@JsonPropertyDescription("Default transfer method: blind, attended, conference, or refer")
	public TransferStyle getDefaultTransferStyle() {
		if (defaultTransferStyle != null) {
			return defaultTransferStyle;
		} else {
			return TransferStyle.blind;
		}
	}

	public void setDefaultTransferStyle(TransferStyle defaultTransferStyle) {
		this.defaultTransferStyle = defaultTransferStyle;
	}

	@Override
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@JsonPropertyDescription("CRUD rules run on every message the transfer service sends, such as the INVITE to a transfer target")
	public Crud getCrud() {
		return crud;
	}

	public void setCrud(Crud crud) {
		this.crud = crud;
	}

	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	@JsonPropertyDescription("Web services transfer events are POSTed to, by name. A REST request picks one with notification.style 'callback' and notification.callbackName")
	public Map<String, Webhook> getWebhooks() {
		return webhooks;
	}

	public void setWebhooks(Map<String, Webhook> webhooks) {
		this.webhooks = (webhooks != null) ? webhooks : new LinkedHashMap<>();
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	@JsonPropertyDescription("The webhook that gets the events of every transfer no REST request named one for, such as a REFER's; none when unset")
	public String getDefaultWebhook() {
		return defaultWebhook;
	}

	public void setDefaultWebhook(String defaultWebhook) {
		this.defaultWebhook = defaultWebhook;
	}
}
