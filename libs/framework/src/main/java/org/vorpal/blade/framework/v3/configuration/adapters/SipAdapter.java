package org.vorpal.blade.framework.v3.configuration.adapters;

import java.io.Serializable;
import java.util.concurrent.CompletableFuture;

import org.vorpal.blade.framework.v3.configuration.Context;

/// Passive adapter that "talks to" the inbound SIP request itself.
/// No I/O — the request is already in hand. Each
/// [org.vorpal.blade.framework.v3.configuration.selectors.Selector]
/// gets the [javax.servlet.sip.SipServletRequest] as its payload
/// (pulled from [Context#getRequest]).
///
/// Use this as the first adapter in nearly every pipeline to pull
/// primary routing keys out of the SIP message (To-host, From-user,
/// Request-URI, etc.) before subsequent adapters run.
public class SipAdapter extends Adapter implements Serializable {
	private static final long serialVersionUID = 1L;

	public SipAdapter() {
	}

	@Override
	public CompletableFuture<Void> invoke(Context ctx) {
		runSelectors(ctx, ctx.getRequest());
		return CompletableFuture.completedFuture(null);
	}
}
