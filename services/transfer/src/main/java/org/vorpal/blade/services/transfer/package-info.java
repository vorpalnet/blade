/// This package provides comprehensive SIP transfer capabilities through REST API and servlet 
/// implementations. It enables blind transfers, attended transfers, and conference transfers 
/// for SIP communications within the Vorpal Blade framework, offering both HTTP-based and 
/// SIP protocol-level interfaces for transfer operations.
///
/// ## Key Components
///
/// - [TransferRestAPI] - RESTful web service interface extending `TransferAPI` and implementing 
///   `TransferListener` to provide HTTP-based access to transfer functionality with OpenAPI documentation
/// - [TransferServlet] - SIP servlet implementation extending `B2buaServlet` that provides 
///   Back-to-Back User Agent functionality with comprehensive transfer capabilities and listener implementations
/// - [TransferSettingsSample] - Sample configuration class demonstrating transfer settings setup 
///   with analytics, logging, and session parameter configurations
///
/// ## Architecture Overview
///
/// The package implements a dual-interface architecture supporting both REST and SIP protocols:
///
/// ### REST Interface
/// The [TransferRestAPI] provides asynchronous HTTP endpoints for transfer operations, maintaining
/// a concurrent response map for managing async operations. It integrates OpenAPI documentation
/// and supports inspection and invocation of transfers through RESTful endpoints.
///
/// ### SIP Interface  
/// The [TransferServlet] handles SIP protocol operations as a B2BUA, implementing both
/// `B2buaListener` and `TransferListener` interfaces. It manages the complete transfer lifecycle
/// including request handling, initiation, completion, decline, and abandonment scenarios.
/// The servlet also provides comprehensive call state management with callbacks for call
/// start, answer, connection, completion, decline, and abandonment events.
///
/// ### Configuration Management
/// Transfer settings are managed through a `SettingsManager` system, with [TransferSettingsSample]
/// providing example configurations for analytics integration, attribute selection, translations,
/// session parameters, and logging levels.
///
/// ## Transfer Types Supported
///
/// The framework supports multiple transfer scenarios through specialized callflow implementations:
/// - Blind transfers via `BlindTransfer` callflows
/// - Attended transfers via `AttendedTransfer` callflows  
/// - Conference transfers via `ConferenceTransfer` callflows
/// - Transfer initial invites via `TransferInitialInvite` callflows
///
/// ## Integration Points
///
/// The package integrates with several Vorpal Blade framework components including the transfer API
/// system, B2BUA framework, callflow management, settings configuration, and analytics reporting.
/// It provides both programmatic SIP servlet interfaces and HTTP REST endpoints for maximum
/// flexibility in integration scenarios.
///
/// @see [org.vorpal.blade.framework.v2.transfer.api.TransferAPI]
/// @see [org.vorpal.blade.framework.v2.transfer.TransferListener]
/// @see [org.vorpal.blade.framework.v2.b2bua.B2buaServlet]
/// @see [org.vorpal.blade.framework.v2.b2bua.B2buaListener]
package org.vorpal.blade.services.transfer;
