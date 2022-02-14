package org.vorpal.blade.services.keepalive;

import java.io.Serializable;

public class KeepAliveConfig implements Serializable {
//	private int sessionExpires = 1800;
	private int sessionExpires = 240;
	private int minSE = 90;

	public int getSessionExpires() {
		return sessionExpires;
	}

	public void setSessionExpires(int sessionExpires) {
		this.sessionExpires = sessionExpires;
	}

	public int getMinSE() {
		return minSE;
	}

	public void setMinSE(int minSE) {
		this.minSE = minSE;
	}

}
