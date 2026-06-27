package org.vorpal.blade.services.hold;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.config.Configuration;

import org.vorpal.blade.framework.v3.configuration.SchemaAbout;

@SchemaAbout(
		name = "Hold",
		tagline = "Call Hold & Resume",
		description = "Handles SIP call hold and resume, managing the re-INVITE signaling and "
				+ "media-state transitions that suspend and restore an active call.")
public class HoldSettings extends Configuration implements Serializable {
	private static final long serialVersionUID = 2L;

}
