/// This package provides web servlet components for the Vorpal Blade console application's
/// web interface. It contains HTTP servlet implementations that handle session management
/// and user authentication workflows.
///
/// ## Key Classes
///
/// - [Logout] - HTTP servlet that handles user logout requests via GET method
///
/// ## Servlet Endpoints
///
/// ### Logout Servlet
/// The [Logout] servlet extends `HttpServlet` and processes HTTP GET requests to
/// terminate user sessions. When invoked, it performs the following steps:
/// 1. Sets `Cache-Control: no-cache, no-store` and `Pragma: no-cache` response headers
///    to prevent browser caching of the logout page
/// 2. Invalidates the current HTTP session via `request.getSession().invalidate()`
/// 3. Redirects the user to `login.jsp` under the application's context path
///
/// ### Session Management
/// The logout flow ensures clean session termination by invalidating the server-side
/// session before redirecting. The anti-caching headers prevent the browser from
/// serving stale authenticated pages after logout.
///
/// @see javax.servlet.http.HttpServlet
/// @see javax.servlet.http.HttpServletRequest
/// @see javax.servlet.http.HttpServletResponse
/// @see Logout
package org.vorpal.blade.applications.console.webapp;
