package org.vorpal.blade.services.proxy.block;

import org.vorpal.blade.framework.v3.configuration.SchemaAbout;
import org.vorpal.blade.framework.v3.irouter.IRouterConfig;

/// An [IRouterConfig] with the call-blocking name on it. No fields: the
/// Configurator and Portal read the app's name from this class, and the
/// pipeline and routing are plain iRouter.
@SchemaAbout(
		name = "Call Blocking",
		tagline = "Screen Robocalls and Spoofed Caller ID",
		description = "Decides on the INVITE, before a call reaches the IVR or an agent. Allow and "
				+ "block lists, the caller spoofing your own numbers, malformed caller ID, call rate, "
				+ "and the carrier's STIR/SHAKEN verdict each pick an outcome: pass the call, tag it, "
				+ "send it to a challenge IVR, a review voicemail or a tarpit, or decline it.")
public class CallBlockingConfig extends IRouterConfig {
	private static final long serialVersionUID = 1L;
}
