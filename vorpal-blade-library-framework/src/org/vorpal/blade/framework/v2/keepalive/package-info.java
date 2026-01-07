/**
 * Provides SIP session keep-alive functionality for B2BUA applications.
 *
 * <p>Key classes:</p>
 * <ul>
 *   <li>{@link KeepAlive} - Refreshes RTP streams via re-INVITE on both call legs</li>
 *   <li>{@link KeepAliveExpiry} - Terminates calls when keep-alive timeout occurs</li>
 * </ul>
 *
 * @since 2.0
 */
package org.vorpal.blade.framework.v2.keepalive;