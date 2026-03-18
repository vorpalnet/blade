package org.vorpal.blade.framework.v2.config;

import java.io.Serializable;
import java.util.LinkedList;

/**
 * Default session parameters with standard values and a From header selector.
 */
public class SessionParametersDefault extends SessionParameters implements Serializable {
	private static final long serialVersionUID = 1L;

	public SessionParametersDefault() {
		this.expiration = 60; // 1 hour
		this.keepAlive = null; // deprecated
		this.sessionSelectors = new LinkedList<>();

		AttributeSelector fromSel = new AttributeSelector();
		fromSel.setId("fromSel");
		fromSel.setAttribute("From");
		fromSel.setPattern(Configuration.SIP_ADDRESS_PATTERN);
		fromSel.setExpression("${user}");
		fromSel.setDescription("Create index key based on the user-part of the From header");
		this.sessionSelectors.add(fromSel);
	}

}
