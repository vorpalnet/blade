package org.vorpal.blade.framework.v3.transfer;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

import org.vorpal.blade.framework.v3.crud.Crud;
import org.vorpal.blade.framework.v3.crud.CrudCapable;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// The transfer service's settings: the v2
/// [org.vorpal.blade.framework.v2.transfer.TransferSettings] plus the CRUD
/// rules the service runs on every message it sends (`crud`), and the web
/// services it reports transfer events to (`webhooks`, `defaultWebhook`).
///
/// It extends the v2 class, not the v3
/// [org.vorpal.blade.framework.v3.configuration.Configuration], because the v2
/// transfer callflows take the v2 settings type in their constructors. It
/// carries the section through [CrudCapable] instead. The on-disk shape is the
/// v2 one plus `crud`, so an existing transfer config loads unchanged.
public class TransferSettings extends org.vorpal.blade.framework.v2.transfer.TransferSettings
		implements CrudCapable, Serializable {
	private static final long serialVersionUID = 1L;

	protected Crud crud;

	/// The v2 constructor is deprecated so that applications build this class
	/// instead; this is the one caller it is meant to keep.
	@SuppressWarnings("deprecation")
	public TransferSettings() {
		super();
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

	protected Map<String, Webhook> webhooks = new LinkedHashMap<>();
	protected String defaultWebhook;

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
