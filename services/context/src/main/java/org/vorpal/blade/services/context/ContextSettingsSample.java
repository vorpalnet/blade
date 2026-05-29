package org.vorpal.blade.services.context;

import java.util.List;

import org.vorpal.blade.framework.v2.config.AttributeSelector;
import org.vorpal.blade.framework.v2.config.SessionParametersDefault;
import org.vorpal.blade.framework.v2.logging.LogParameters.LoggingLevel;
import org.vorpal.blade.framework.v2.logging.LogParametersDefault;

public class ContextSettingsSample extends ContextSettings {

	private static final long serialVersionUID = 1L;

	public ContextSettingsSample() {
		this.logging = new LogParametersDefault();
		this.logging.setLoggingLevel(LoggingLevel.INFO);

		this.session = new SessionParametersDefault();
		this.getSession().setExpiration(60);

		List<AttributeSelector> indexKeySelectors = this.getSession().getSessionSelectors();

		AttributeSelector callIdSel = new AttributeSelector();
		callIdSel.setId("callIdSelector");
		callIdSel.setDescription("Index by SIP Call-ID");
		callIdSel.setAttribute("Call-ID");
		callIdSel.setPattern("^(?<callId>.*)$");
		callIdSel.setExpression("${callId}");
		indexKeySelectors.add(callIdSel);

		AttributeSelector txIdSel = new AttributeSelector();
		txIdSel.setId("appTxIdSelector");
		txIdSel.setDescription("Index by an app-assigned correlator carried in X-App-Tx-Id");
		txIdSel.setAttribute("X-App-Tx-Id");
		txIdSel.setPattern("^(?<txId>.*)$");
		txIdSel.setExpression("${txId}");
		indexKeySelectors.add(txIdSel);
	}

}
