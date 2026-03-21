/// # SIP Call Transfer Framework
///
/// Provides comprehensive SIP call transfer functionality for the BLADE framework,
/// implementing various transfer call flows and mechanisms according to SIP standards.
///
/// This package supports multiple transfer scenarios including unattended transfers,
/// attended transfers, REFER-based transfers, and conference transfers. All transfer
/// operations are built on top of the core BLADE framework and provide event-driven
/// callbacks for monitoring transfer progress and handling completion.
///
/// ## Core Transfer Classes
///
/// - [Transfer] - Abstract base class for all transfer operations
/// - [BlindTransfer] - Implements unattended (blind) transfer call flows
/// - [AttendedTransfer] - Handles attended transfer scenarios with consultation
/// - [ReferTransfer] - REFER method-based transfer implementation
/// - [ConferenceTransfer] - Multi-party conference transfer operations
///
/// ## Configuration and Control
///
/// - [TransferSettings] - Configuration parameters for transfer behavior
/// - [TransferCondition] - Conditional logic for transfer execution
/// - [TransferInitialInvite] - Initial INVITE handling for transfers
/// - [TransferListener] - Event callback interface for transfer state changes
///
/// ## Usage Example
///
/// ```java
/// // Basic blind transfer setup
/// BlindTransfer transfer = new BlindTransfer();
/// transfer.setTargetUri("sip:destination@example.com");
/// transfer.setListener(new TransferListener() {
///     public void onTransferComplete(Transfer transfer) {
///         System.out.println("Transfer completed successfully");
///     }
/// });
/// transfer.execute(originalCall);
///
/// // Attended transfer with consultation
/// AttendedTransfer attendedTransfer = new AttendedTransfer();
/// attendedTransfer.consultFirst("sip:target@example.com")
///     .onConsultationEstablished(() -> {
///         attendedTransfer.completeTransfer();
///     });
/// ```
///
/// ## Transfer Flow Types
///
/// ### Blind Transfer
/// Immediately transfers the call to a third party without consultation.
/// The transferor drops out of the call once the transfer is initiated.
///
/// ### Attended Transfer
/// Establishes a consultation call with the transfer target before
/// completing the transfer, allowing the transferor to announce the call.
///
/// ### REFER Transfer
/// Uses the SIP REFER method to instruct one party to establish
/// a new dialog with a third party, following RFC 3515.
///
/// ### Conference Transfer
/// Creates a multi-party conference call by bridging multiple dialogs.
///
/// @see org.vorpal.blade.framework.v2.transfer.api
package org.vorpal.blade.framework.v2.transfer;
