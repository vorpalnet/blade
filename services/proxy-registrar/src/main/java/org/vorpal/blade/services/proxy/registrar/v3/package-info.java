/// This package provides a comprehensive SIP proxy and registrar service implementation
/// built on the Vorpal Blade framework. It handles SIP REGISTER requests for location
/// services and proxies INVITE requests to registered contacts.
///
/// ## Core Components
///
/// ### Main Service Classes
///
/// - [PRServlet] - Main SIP servlet that handles incoming requests and manages the application lifecycle
/// - [Registrar] - Core registrar functionality for managing contact bindings and location services
/// - [Settings] - Configuration class for proxy and registrar behavior settings
/// - [SettingsSample] - Sample configuration with default settings and logging parameters
///
/// ### Call Flow Handlers
///
/// - [RegisterCallflow] - Processes SIP REGISTER requests for contact registration
/// - [InviteCallflow] - Handles SIP INVITE requests with proxy functionality to registered contacts
///
/// ### Data Models
///
/// - [ContactInfo] - Represents a contact binding with SIP address and expiration information
///
/// ## Functionality
///
/// The service provides:
/// - **Registration Services**: Accepts and processes SIP REGISTER requests to maintain contact bindings
/// - **Location Services**: Maintains a registry of contact addresses mapped to SIP URIs using an in-memory HashMap
/// - **Proxy Services**: Routes INVITE requests to registered contacts based on the location database
/// - **Contact Management**: Handles contact expiration and binding updates through the registrar component
/// - **Configuration Management**: Supports dynamic configuration including proxy timeout, record routing, and parallel processing options
///
/// ## Architecture
///
/// This implementation uses the Vorpal Blade v2 framework's callflow pattern, where different
/// SIP methods are handled by dedicated callflow classes that extend `Callflow`.
/// The main servlet [PRServlet] dispatches requests to appropriate callflows based on the SIP method
/// and implements session lifecycle management through SIP application session and session listeners.
///
/// The [Registrar] component maintains contact bindings in memory and provides methods to retrieve
/// contacts for routing and update contact information from REGISTER requests. Configuration is
/// managed through the [Settings] class which provides proxy behavior customization including
/// timeout values, routing options, and network interface selection.
///
/// @see PRServlet
/// @see Registrar
/// @see RegisterCallflow
/// @see InviteCallflow
/// @see Settings
/// @see ContactInfo
package org.vorpal.blade.services.proxy.registrar.v3;
