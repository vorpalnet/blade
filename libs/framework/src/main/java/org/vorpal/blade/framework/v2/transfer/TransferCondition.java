package org.vorpal.blade.framework.v2.transfer;

import java.io.Serializable;

import org.vorpal.blade.framework.v2.config.Condition;
import org.vorpal.blade.framework.v2.transfer.TransferSettings.TransferStyle;

/**
 * Associates a transfer style with a matching condition.
 *
 * <p>Used to configure conditional transfer behavior based on request attributes.
 */
public class TransferCondition implements Serializable {
	private static final long serialVersionUID = 1L;

	private TransferStyle style = TransferStyle.blind;
	private Condition condition = new Condition();

	public TransferCondition() {
		// Default constructor
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
