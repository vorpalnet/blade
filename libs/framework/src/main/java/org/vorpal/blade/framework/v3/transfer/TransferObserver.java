package org.vorpal.blade.framework.v3.transfer;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

import org.vorpal.blade.framework.v2.transfer.TransferListener;

/// A [TransferListener] that also hears what an [AttendedTransfer] has to say
/// and the v2 listener has no method for. Both defaults do nothing, so a
/// listener opts in by implementing this interface instead.
public interface TransferObserver extends TransferListener {

	/// The target (Carol) answered an attended transfer and is now talking to
	/// the transferor (Bob); the transferee (Alice) is parked. Not one of the
	/// framework's five transfer events: the transfer is not over until Bob
	/// hangs up on Carol, and [#transferCompleted] says so then. A listener
	/// may report it as its own (the transfer service does), and the REST
	/// API's waited request, which cannot wait that long, answers here.
	///
	/// @param targetAnswer Carol's 2xx to the INVITE
	default void consultationStarted(SipServletResponse targetAnswer) throws ServletException, IOException {
	}

	/// The transfer did not succeed and the reason is a request, not a
	/// response: Carol hung up during the consultation, or before Alice could
	/// be connected to her. Published on the bus as `transferDeclined`, the
	/// same as a refusal [#transferDeclined(SipServletResponse)] reports.
	///
	/// @param request the request that ended it, Carol's BYE
	default void transferDeclined(SipServletRequest request) throws ServletException, IOException {
	}
}
