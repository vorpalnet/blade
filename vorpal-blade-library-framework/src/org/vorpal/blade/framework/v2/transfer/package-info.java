/**
 * Provides SIP call transfer functionality for the BLADE framework.
 *
 * <p>This package contains classes for implementing various SIP transfer
 * call flows including blind transfers, attended transfers, conference
 * transfers, and REFER-based transfers.
 *
 * <h2>Key Classes:</h2>
 * <ul>
 *   <li>{@link org.vorpal.blade.framework.v2.transfer.Transfer} - Base class for all transfer operations</li>
 *   <li>{@link org.vorpal.blade.framework.v2.transfer.BlindTransfer} - Unattended transfer call flow</li>
 *   <li>{@link org.vorpal.blade.framework.v2.transfer.AttendedTransfer} - Attended transfer call flow</li>
 *   <li>{@link org.vorpal.blade.framework.v2.transfer.ReferTransfer} - REFER-based transfer call flow</li>
 *   <li>{@link org.vorpal.blade.framework.v2.transfer.ConferenceTransfer} - Conference transfer call flow</li>
 *   <li>{@link org.vorpal.blade.framework.v2.transfer.TransferListener} - Callback interface for transfer events</li>
 *   <li>{@link org.vorpal.blade.framework.v2.transfer.TransferSettings} - Configuration settings for transfers</li>
 * </ul>
 *
 * @see org.vorpal.blade.framework.v2.transfer.api
 */
package org.vorpal.blade.framework.v2.transfer;
