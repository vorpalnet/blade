/// # BLADE Framework v2
///
/// This is the base package of the BLADE framework library. The purpose of this library 
/// is to provide developers with a convenient and intuitive approach to developing 
/// SIP Servlet applications.
///
/// ## Framework Overview
///
/// The BLADE framework simplifies SIP servlet development by providing:
///
/// - High-level abstractions for SIP message handling
/// - Intuitive API design for common SIP operations
/// - Built-in support for SIP dialog management
/// - Streamlined request/response processing
/// - Enhanced error handling and logging capabilities
///
/// ## Key Features
///
/// - **Simplified SIP Operations**: Abstracts complex SIP protocol details
/// - **Fluent API Design**: Chainable method calls for readable code
/// - **Dialog Management**: Automatic handling of SIP dialogs and transactions
/// - **Extension Points**: Customizable behavior through configuration
/// - **Performance Optimized**: Designed for high-throughput SIP applications
///
/// ## Usage Example
///
/// ```java
/// // Basic SIP servlet using BLADE framework
/// public class MyBladeServlet extends BladeServlet {
///     @Override
///     public void handleRequest(BladeRequest request) throws ServletException {
///         if (request.isInvite()) {
///             request.createResponse(200, "OK")
///                   .addHeader("Contact", getContactHeader())
///                   .send();
///         }
///     }
/// }
/// ```
///
/// ## Architecture
///
/// The framework follows a layered architecture:
///
/// 1. **Application Layer** - Your SIP servlet implementations
/// 2. **BLADE Framework** - High-level SIP abstractions and utilities
/// 3. **SIP Servlet API** - Standard Java SIP servlet specification
/// 4. **SIP Container** - Underlying SIP servlet container
///
/// @since 2.0
package org.vorpal.blade.framework.v2;