package org.vorpal.blade.framework;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.ar.SipApplicationRoutingDirective;

import inet.ipaddr.IPAddress;
import inet.ipaddr.IPAddressString;

/// Decides whether an inbound request came from another BLADE hop, so that the
/// framework's own headers on it (`X-Vorpal-ID` with its `origin`, `ts` and `se`
/// parameters, and the legacy `X-Vorpal-Session`) may be believed.
///
/// Those headers carry the original caller's address, the call's identity and
/// its session lifetime from one BLADE application to the next. A caller can
/// write them too. Believed from outside, `X-Vorpal-ID: x;origin=10.20.0.5`
/// passes a `${originIP} insubnet` rule, selects another customer's row in a
/// table keyed on the origin, collides with a real call's identity, and
/// stretches the session's lifetime. So they are read only from a trusted hop,
/// and ignored otherwise; an untrusted request is treated as first-touch.
///
/// A request is from a trusted hop when any of these hold:
///
/// - another application in this container passed it on: its routing directive
///   is `CONTINUE` or `REVERSE` (an external request arrives as `NEW`);
/// - it has no transport peer, or the peer is a loopback address;
/// - the peer address is inside a subnet listed in the `blade.trustedPeers`
///   system property, a comma-separated list of CIDR blocks or addresses such as
///   `10.20.0.0/16, 192.0.2.10`. List the engine clusters that forward calls to
///   each other; never list a carrier or SBC address that passes caller headers
///   through.
///
/// The property is read on each check and parsed only when it changes, so it
/// can be set with the other JVM arguments without a code change.
public final class TrustedPeers {

	/// System property holding the trusted CIDR blocks.
	public static final String PROPERTY = "blade.trustedPeers";

	private static volatile Parsed parsed = new Parsed(null, Collections.emptyList());

	private TrustedPeers() {
	}

	/// True when `request` came from a BLADE hop whose framework headers may be
	/// believed; see the class comment.
	public static boolean isTrusted(SipServletRequest request) {
		if (request == null) {
			return false;
		}
		if (passedOnInContainer(request)) {
			return true;
		}
		return isTrustedAddress(request.getRemoteAddr());
	}

	/// True for a missing peer, a loopback peer, or one inside `blade.trustedPeers`.
	public static boolean isTrustedAddress(String remoteAddr) {
		if (remoteAddr == null || remoteAddr.isEmpty()) {
			return true;
		}
		IPAddress ip = new IPAddressString(remoteAddr).getAddress();
		if (ip == null) {
			return false;
		}
		if (ip.isLoopback()) {
			return true;
		}
		for (IPAddress block : subnets()) {
			if (block.contains(ip)) {
				return true;
			}
		}
		return false;
	}

	private static boolean passedOnInContainer(SipServletRequest request) {
		try {
			SipApplicationRoutingDirective directive = request.getRoutingDirective();
			return directive == SipApplicationRoutingDirective.CONTINUE
					|| directive == SipApplicationRoutingDirective.REVERSE;
		} catch (IllegalStateException e) {
			// Not an initial request: no directive to read.
			return false;
		}
	}

	private static List<IPAddress> subnets() {
		String value = System.getProperty(PROPERTY);
		Parsed current = parsed;
		if (value == null ? current.source == null : value.equals(current.source)) {
			return current.blocks;
		}
		List<IPAddress> blocks = new ArrayList<>();
		if (value != null) {
			for (String entry : value.split(",")) {
				IPAddress block = new IPAddressString(entry.trim()).getAddress();
				if (block != null) {
					blocks.add(block.toPrefixBlock());
				}
			}
		}
		parsed = new Parsed(value, Collections.unmodifiableList(blocks));
		return parsed.blocks;
	}

	/// The property value and the blocks parsed from it, swapped as one.
	private static final class Parsed {
		final String source;
		final List<IPAddress> blocks;

		Parsed(String source, List<IPAddress> blocks) {
			this.source = source;
			this.blocks = blocks;
		}
	}
}
