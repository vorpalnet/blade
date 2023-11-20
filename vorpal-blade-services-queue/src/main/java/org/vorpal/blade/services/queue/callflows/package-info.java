/**
 *
 * This class connects to a media server to play announcements while the caller
 * is placed in a queue
 * 
 * <pre>
 * {@code
 * 
 *   WITHOUT MEDIA SERVER
 *   Alice               QUEUE               media                Bob
 *   -----               -----               -----                ---
 *     |                   |                   |                   |
 *   ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~Ringing~~~~~~~~~~~~~~~~~~~~~~~~~~~~ ; Ringing Callflow
 *     |                   |                   |                   |
 *     | INVITE            |                   |                   | 
 *     |-(sdp)------------>|                   |                   |
 *     |       180 Ringing |                   |                   |  ; Timer, send 180 Ringing every 30 seconds
 *     |<------------------|                   |                   |
 *     |                |--|                   |                   |  ; Place Continue Callflow in queue
 *     |          Queue |  |                   |                   |
 *     |                |->|                   |                   |
 *     |                   |                   |                   |
 *   ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~Continue~~~~~~~~~~~~~~~~~~~~~~~~~~~ ; Continue to Bob
 *     |                   |                   |                   |  ; Cancel 180 Ringing timer
 *     |                   | INVITE            |                   |
 *     |                   |-(sdp)-------------------------------->|
 *     |                   |                   |            200 OK |
 *     |                   |<--------------------------------(sdp)-|
 *     |            INVITE |                   |                   |
 *     |<------------(sdp)-|                   |                   |
 *     | ACK               |                   |                   |
 *     |------------------>|                   |                   |
 *     |                   | ACK               |                   |
 *     |                   |-------------------------------------->|
 *
 *   WITH MEDIA SERVER
 *   Alice               QUEUE               media                Bob
 *   -----               -----               -----                ---
 *     |                   |                   |                   |
 *   ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~Ringing~~~~~~~~~~~~~~~~~~~~~~~~~~~~ ; Ringing is optional
 *   ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~Continue~~~~~~~~~~~~~~~~~~~~~~~~~~~ ; Continue to Media (not Bob)
 *   ~~~~~~~~~~~~~~~~~~~~~~~UnsolicitedReinvite~~~~~~~~~~~~~~~~~~~~~~ ; Use 'empty' INVITE to call Bob
 *     |                   |                   |                   |
 *     |                   | INVITE            |                   |  
 *     |                   |-------------------------------------->|  
 *     |                   |                   |       180 Ringing |
 *     |                   |<--------------------------------------|
 *     |                   |                   |            200 OK |
 *     |                   |<--------------------------------(sdp)-|
 *     |            INVITE |                   |                   |
 *     |<------------(sdp)-|                   |                   |
 *     | ACK               |                   |                   |
 *     |-(sdp)------------>|                   |                   |
 *     |                   | ACK               |                   |
 *     |                   |-(sdp)-------------------------------->|
 *     |                   |                   |                   |
 * }
 * </pre>
 */

package org.vorpal.blade.services.queue.callflows;