package org.vorpal.blade.services.transfer.callflows;

import org.vorpal.blade.framework.v2.config.Condition;
import org.vorpal.blade.services.transfer.TransferSettings.TransferStyle;

public class TransferCondition {

	private TransferStyle style = TransferStyle.blind;
	private Condition condition = new Condition();

	public TransferCondition() {
	}

	public TransferCondition(TransferStyle style, Condition condition) {
		this.style = style;
		this.condition = condition;
	}

	public TransferStyle getStyle() {
		return style;
	}

	public void setStyle(TransferStyle style) {
		this.style = style;
	}

	public Condition getCondition() {
		return condition;
	}

	public void setCondition(Condition condition) {
		this.condition = condition;
	}

}
