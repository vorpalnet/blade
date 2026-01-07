/**
 * Provides the callflow framework for building SIP applications using Java lambda expressions.
 *
 * <p>Key classes:</p>
 * <ul>
 *   <li>{@link Callflow} - Base class for all callflows with request/response handling utilities</li>
 *   <li>{@link Callback} - Functional interface for lambda-based asynchronous callbacks</li>
 *   <li>{@link ClientCallflow} - Base class for client-initiated (UAC) callflows</li>
 *   <li>{@link Expectation} - Manages expected SIP method callbacks on sessions</li>
 * </ul>
 *
 * @since 2.0
 */
package org.vorpal.blade.framework.v2.callflow;