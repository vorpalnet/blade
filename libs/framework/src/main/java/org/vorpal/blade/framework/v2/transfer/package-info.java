/// # SIP Call Transfer Framework
///
/// This package provides a comprehensive framework for implementing SIP call transfer operations
/// in the Vorpal Blade framework. It supports multiple transfer styles including blind, attended,
/// conference, and REFER-based transfers with configurable behavior and lifecycle event handling.
///
/// ## Transfer Types
///
/// The framework supports four distinct transfer patterns:
///
/// - **[BlindTransfer]** - Unattended transfers where the transferor immediately disconnects
/// - **[AttendedTransfer]** - Consultative transfers allowing conversation before completion
/// - **[ConferenceTransfer]** - Multi-party conference bridging operations
/// - **[ReferTransfer]** - REFER-based transfers delegating control to the transferee
///
/// ## Core Components
///
/// ### Base Classes
///
/// - [Transfer] - Abstract base class providing common transfer functionality and SIP session management
/// - [TransferInitialInvite] - Specialized B2BUA handler for transfer-capable initial INVITE processing
///
/// ### Configuration
///
/// - [TransferSettings] - Comprehensive configuration for transfer behavior, allowed methods, and header preservation
/// - [TransferCondition] - Conditional transfer routing based on request attributes and transfer styles
///
/// ### Event Handling
///
/// - [TransferListener] - Interface for receiving transfer lifecycle events including request, initiation, completion, decline, and abandonment notifications
///
/// ## Transfer Workflows
///
/// Each transfer type implements specific SIP signaling patterns:
///
/// ### Blind Transfer
/// 1. Receives REFER from transferor
/// 2. Places outbound call to target
/// 3. Connects transferee to target upon answer
/// 4. Terminates transferor session
/// 5. Sends NOTIFY messages to track transfer progress
///
/// ### Attended Transfer
/// 1. Establishes consultation call between transferor and target
/// 2. Allows conversation before transfer completion
/// 3. Connects transferee to target
/// 4. Manages complex multi-session state transitions
///
/// ### REFER Transfer
/// 1. Sends REFER to transferee on behalf of transferor
/// 2. Transferee initiates call to target
/// 3. Provides NOTIFY updates on transfer progress
/// 4. Manages subscription state for event notifications
///
/// ### Conference Transfer
/// 1. Bridges multiple parties into a conference call
/// 2. Maintains connections between all participants
/// 3. Manages conference-specific SIP signaling
///
/// ## Features
///
/// - **Header Preservation** - Configurable copying of headers between INVITE and REFER requests via `preserveInviteHeaders()` and `preserveReferHeaders()`
/// - **Event Notifications** - Comprehensive lifecycle event callbacks through [TransferListener] for monitoring and logging
/// - **Conditional Routing** - Rule-based transfer style selection based on request attributes using [TransferCondition]
/// - **Session Management** - Proper handling of multiple concurrent SIP sessions during transfers
/// - **Error Handling** - Robust failure scenarios with appropriate SIP response codes and NOTIFY messages
/// - **B2BUA Integration** - Seamless integration with Back-to-Back User Agent functionality through [TransferInitialInvite]
///
/// ## Configuration
///
/// Transfer behavior is controlled through [TransferSettings] which allows configuration of:
/// - Default transfer styles and conditional routing via `TransferStyle` enumeration
/// - Allowed SIP methods for transfer operations
/// - Header preservation rules for INVITE and REFER requests
/// - Conference application settings
///
/// @see [org.vorpal.blade.framework.v2.b2bua]
/// @see [org.vorpal.blade.framework.v2.callflow]
/// @see [javax.servlet.sip]
package org.vorpal.blade.framework.v2.transfer;
