package org.vorpal.blade.framework.v3.crud;

import javax.servlet.sip.SipServletMessage;
import javax.servlet.sip.SipServletRequest;

import org.vorpal.blade.framework.v2.config.SettingsManager;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/// Refuses the request instead of sending it on: the request it came from is
/// answered with `status` and `reasonPhrase`, and this one never leaves.
///
/// Only an initial INVITE can be refused; it is the request whose upstream
/// counterpart the sender can always find. Inside a dialog both ends hold
/// state the other must hear about: a BYE refused on one leg would leave the
/// other leg's call up, and a re-INVITE held back would leave the two ends
/// with different media. A rule that reaches an in-dialog request fails and
/// is undone, and the request goes on unchanged.
///
/// The refusal is recorded on the message; whoever sends it ([CrudHook])
/// answers upstream instead.
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonPropertyOrder({ "status", "reasonPhrase" })
public class RejectOperation implements Operation {
	private static final long serialVersionUID = 1L;

	private int status = 403;
	private String reasonPhrase;

	public RejectOperation() {
	}

	public RejectOperation(int status, String reasonPhrase) {
		this.status = status;
		this.reasonPhrase = reasonPhrase;
	}

	@Override
	public void process(SipServletMessage msg) throws Exception {
		if (!(msg instanceof SipServletRequest) || !((SipServletRequest) msg).isInitial()
				|| !"INVITE".equals(((SipServletRequest) msg).getMethod())) {
			throw new IllegalStateException("only an initial INVITE can be rejected");
		}
		if (status < 400 || status > 699) {
			throw new IllegalArgumentException("reject status " + status + " is not 400-699 (a redirect would need a Contact)");
		}
		msg.setAttribute(MessageHelper.PENDING_REJECT, new Refusal(status, reasonPhrase));
		SettingsManager.getSipLogger().finer(msg, "RejectOperation - " + status + " " + reasonPhrase);
	}

	@Override
	public void check() {
		if (status < 400 || status > 699) {
			throw new IllegalArgumentException("reject status " + status + " is not 400-699 (a redirect would need a Contact)");
		}
	}

	@JsonPropertyDescription("The failure to answer with, 400-699.")
	public int getStatus() {
		return status;
	}

	public void setStatus(int status) {
		this.status = status;
	}

	@JsonPropertyDescription("Optional reason phrase; empty uses the standard one.")
	public String getReasonPhrase() {
		return reasonPhrase;
	}

	public void setReasonPhrase(String reasonPhrase) {
		this.reasonPhrase = reasonPhrase;
	}

	/// A recorded refusal.
	public static final class Refusal implements java.io.Serializable {
		private static final long serialVersionUID = 1L;
		public final int status;
		public final String reasonPhrase;

		Refusal(int status, String reasonPhrase) {
			this.status = status;
			this.reasonPhrase = reasonPhrase;
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof Refusal && ((Refusal) o).status == status
					&& java.util.Objects.equals(((Refusal) o).reasonPhrase, reasonPhrase);
		}

		@Override
		public int hashCode() {
			return status;
		}
	}
}
