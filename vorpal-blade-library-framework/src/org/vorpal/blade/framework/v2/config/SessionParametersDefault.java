package org.vorpal.blade.framework.v2.config;

import java.io.Serializable;
import java.util.LinkedList;



public class SessionParametersDefault extends SessionParameters implements Serializable {
	private static final long serialVersionUID = 1L;

	public SessionParametersDefault() {
		this.expiration = 60;
		this.keepAlive = new KeepAliveParametersDefault();
		this.sessionSelectors = new LinkedList<>();
		
		AttributeSelector fromSel = new AttributeSelector();
		fromSel.setId("fromSel");
		fromSel.setAttribute("From");
		fromSel.setPattern(Configuration.SIP_ADDRESS_PATTERN);
		fromSel.setExpression("${user}");
		fromSel.setDescription("Create index key based on the user-part of the From header");
//		this.setDialog(DialogType.origin);
		this.sessionSelectors.add(fromSel);
	}

}
