/**
 * B2BUA (Back-to-back User Agent) framework for creating SIP routing applications.
 *
 * <p>Key classes:</p>
 * <ul>
 *   <li>{@link B2buaServlet} - Base servlet implementing B2BUA pattern</li>
 *   <li>{@link B2buaListener} - Callback interface for call lifecycle events</li>
 *   <li>{@link InitialInvite} - Handles initial INVITE and call setup</li>
 *   <li>{@link Terminate} - Handles BYE and CANCEL for call teardown</li>
 *   <li>{@link Reinvite} - Handles mid-dialog re-INVITE for media changes</li>
 * </ul>
 *
 * @since 2.0
 */
package org.vorpal.blade.framework.v2.b2bua;