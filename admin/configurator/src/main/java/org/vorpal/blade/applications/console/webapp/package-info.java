/// This package provides web-based console functionality for the Vorpal Blade
/// configurator application, implementing servlet-based HTTP request handling
/// for administrative session management.
///
/// ## Key Classes
///
/// - [Logout] - HTTP servlet that handles user logout operations via GET requests
///
/// ## Servlet Endpoints
///
/// ### Logout Servlet
/// The [Logout] servlet extends `HttpServlet` and handles HTTP GET requests to
/// terminate authenticated user sessions. The logout workflow:
/// 1. Sets anti-caching headers (`Cache-Control: no-cache, no-store` and
///    `Pragma: no-cache`) to prevent browsers from caching authenticated pages
/// 2. Invalidates the current HTTP session on the server
/// 3. Redirects the user to `login.jsp` within the configurator's context path
///
/// ### Session Security
/// The servlet uses `request.getSession().invalidate()` to ensure complete
/// server-side session cleanup. Combined with the cache-control headers, this
/// prevents unauthorized access to cached configurator pages after logout.
///
/// @see javax.servlet.http.HttpServlet
/// @see javax.servlet.http.HttpServletRequest
/// @see javax.servlet.http.HttpServletResponse
package org.vorpal.blade.applications.console.webapp;
