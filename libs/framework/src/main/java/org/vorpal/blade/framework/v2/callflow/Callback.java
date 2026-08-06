/**
 *  MIT License
 *
 *  Copyright (c) 2021 Vorpal Networks, LLC
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

package org.vorpal.blade.framework.v2.callflow;

/**
 * The v2 face of the baseline {@link org.vorpal.blade.framework.Callback}.
 *
 * <p>A serializable functional interface for SIP callflow callbacks that can
 * throw exceptions. Its definition now lives in the version-neutral baseline so
 * v2 and v3 callbacks share a single type; this interface is retained unchanged
 * as a name so existing v2 source and serialized lambdas that reference
 * {@code org.vorpal.blade.framework.v2.callflow.Callback} are unaffected. The
 * single abstract method ({@code acceptThrows}) and the {@code accept} default
 * are inherited from the baseline.
 *
 * @param <T> the type of the input to the callback (typically SipServletRequest or SipServletResponse)
 * @deprecated Import the baseline
 *             {@link org.vorpal.blade.framework.Callback} instead. There is one
 *             callback type; this is a second name for it, and the framework is
 *             collapsing onto the baseline. Blade no longer imports this face —
 *             existing applications still may, so it stays, and a serialized
 *             lambda naming this interface still resolves on failover.
 */
@Deprecated
@FunctionalInterface
public interface Callback<T> extends org.vorpal.blade.framework.Callback<T> {
}
