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

import java.io.Serializable;
import java.util.function.Consumer;

/**
 * A serializable functional interface for SIP callflow callbacks that can throw exceptions.
 * Extends Consumer to allow use with lambda expressions while supporting checked exceptions.
 *
 * @param <T> the type of the input to the callback (typically SipServletRequest or SipServletResponse)
 */
@FunctionalInterface
public interface Callback<T> extends Consumer<T>, Serializable {

	/**
	 * Wraps the exception-throwing acceptThrows method to comply with Consumer interface.
	 * Null elements are silently ignored.
	 *
	 * @param elem the input element to process
	 * @throws RuntimeException wrapping any exception thrown by acceptThrows
	 */
	@Override
	default void accept(final T elem) {
		try {
			if (elem != null) {
				acceptThrows(elem);
			}
		} catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}

	/**
	 * Performs this callback operation on the given element.
	 *
	 * @param t the input element
	 * @throws Exception if the callback operation fails
	 */
	void acceptThrows(T t) throws Exception;
}
