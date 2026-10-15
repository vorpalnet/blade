package org.vorpal.blade.applications.dashboard;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.vorpal.blade.framework.v2.config.FormLayout;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/// Who hears about trouble without watching the board ([Notifier]): webhooks
/// and an email list, and which kinds of trouble they hear about.
public class NotifySettings implements Serializable {

	private static final long serialVersionUID = 1L;

	private List<String> webhooks = new ArrayList<>();
	private List<String> emailTo = new ArrayList<>();
	private String emailFrom;
	private String smtpHost;
	private int smtpPort = 587;
	private boolean smtpStartTls = true;
	private String smtpUser;
	private String smtpPassword;
	private List<String> kinds = new ArrayList<>(Arrays.asList("problem", "endpoint", "trunk", "remote"));
	private String boardUrl;

	@JsonPropertyDescription("URLs each notice is POSTed to as JSON: {state, kind, subject, server, summary, at, domain, board}. A chat tool's incoming webhook, a paging service, a script.")
	public List<String> getWebhooks() {
		return webhooks;
	}

	public void setWebhooks(List<String> webhooks) {
		this.webhooks = webhooks == null ? new ArrayList<>() : webhooks;
	}

	@JsonPropertyDescription("Addresses each notice is emailed to. Needs smtpHost and emailFrom.")
	public List<String> getEmailTo() {
		return emailTo;
	}

	public void setEmailTo(List<String> emailTo) {
		this.emailTo = emailTo == null ? new ArrayList<>() : emailTo;
	}

	@JsonPropertyDescription("The sender of the notice emails.")
	public String getEmailFrom() {
		return emailFrom;
	}

	public void setEmailFrom(String emailFrom) {
		this.emailFrom = emailFrom;
	}

	@JsonPropertyDescription("The SMTP server the notice emails go through.")
	public String getSmtpHost() {
		return smtpHost;
	}

	public void setSmtpHost(String smtpHost) {
		this.smtpHost = smtpHost;
	}

	@JsonPropertyDescription("The SMTP server's port: 587 for submission with STARTTLS, 25 for plain relay.")
	public int getSmtpPort() {
		return smtpPort;
	}

	public void setSmtpPort(int smtpPort) {
		this.smtpPort = smtpPort;
	}

	@JsonPropertyDescription("Whether the connection to the SMTP server is upgraded to TLS before signing in.")
	public boolean isSmtpStartTls() {
		return smtpStartTls;
	}

	public void setSmtpStartTls(boolean smtpStartTls) {
		this.smtpStartTls = smtpStartTls;
	}

	@JsonPropertyDescription("The SMTP sign-in name; empty for a relay that takes mail without one.")
	public String getSmtpUser() {
		return smtpUser;
	}

	public void setSmtpUser(String smtpUser) {
		this.smtpUser = smtpUser;
	}

	@JsonPropertyDescription("The SMTP password. Encrypted at rest by the Configurator ({CLEARTEXT}→{AES}).")
	@FormLayout(password = true)
	public String getSmtpPassword() {
		return smtpPassword;
	}

	public void setSmtpPassword(String smtpPassword) {
		this.smtpPassword = smtpPassword;
	}

	@JsonPropertyDescription("The kinds of trouble that notify: problem (an application's, a certificate, parked events), endpoint, trunk, remote.")
	public List<String> getKinds() {
		return kinds;
	}

	public void setKinds(List<String> kinds) {
		this.kinds = kinds == null ? new ArrayList<>() : kinds;
	}

	@JsonPropertyDescription("The board's address as people open it, put in each notice so one click opens it, such as https://admin.example.net/blade/dashboard/.")
	public String getBoardUrl() {
		return boardUrl;
	}

	public void setBoardUrl(String boardUrl) {
		this.boardUrl = boardUrl;
	}
}
