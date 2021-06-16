/**
 *  MIT License
 *  
 *  Copyright (c) 2016 Vorpal Networks, LLC
 *  
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *  
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *  
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

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
