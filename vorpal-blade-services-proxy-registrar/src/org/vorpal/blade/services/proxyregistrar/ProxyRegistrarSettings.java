package org.vorpal.blade.services.proxyregistrar;

import java.io.Serializable;

public class ProxyRegistrarSettings implements Serializable {
	private static final long serialVersionUID = 1L;
	public boolean proxyOnUnregistered = false;
	public boolean addToPath = false;
	public boolean noCancel = false;
	public boolean parallel = true;
	public int proxyTimeout = 180;
	public boolean recordRoute = false;
	public boolean recurse = true;

	public boolean isProxyOnUnregistered() {
		return proxyOnUnregistered;
	}

	public void setProxyOnUnregistered(boolean proxyOnUnregistered) {
		this.proxyOnUnregistered = proxyOnUnregistered;
	}

	public boolean isAddToPath() {
		return addToPath;
	}

	public void setAddToPath(boolean addToPath) {
		this.addToPath = addToPath;
	}

	public boolean isNoCancel() {
		return noCancel;
	}

	public void setNoCancel(boolean noCancel) {
		this.noCancel = noCancel;
	}

	public boolean isParallel() {
		return parallel;
	}

	public void setParallel(boolean parallel) {
		this.parallel = parallel;
	}

	public int getProxyTimeout() {
		return proxyTimeout;
	}

	public void setProxyTimeout(int proxyTimeout) {
		this.proxyTimeout = proxyTimeout;
	}

	public boolean isRecordRoute() {
		return recordRoute;
	}

	public void setRecordRoute(boolean recordRoute) {
		this.recordRoute = recordRoute;
	}

	public boolean isRecurse() {
		return recurse;
	}

	public void setRecurse(boolean recurse) {
		this.recurse = recurse;
	}

}
