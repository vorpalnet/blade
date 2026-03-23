/// # Console Web Application Components
///
/// This package provides web servlet components for the Vorpal Blade console application's
/// web interface. It contains HTTP servlet implementations that handle various web-based
/// console operations and user interactions.
///
/// ## Key Classes
///
/// - [Logout] - HTTP servlet that handles user logout requests via GET method
///
/// The servlets in this package extend [javax.servlet.http.HttpServlet] and follow standard
/// Java EE servlet patterns for handling HTTP requests and responses. They implement the
/// `doGet` method to process HTTP GET requests and manage appropriate servlet responses.
/// These components are designed to work within the broader Vorpal Blade console application
/// framework to provide web-based administrative and user interface capabilities.
///
/// @see javax.servlet.http.HttpServlet
/// @see javax.servlet.http.HttpServletRequest
/// @see javax.servlet.http.HttpServletResponse
/// @see Logout
package org.vorpal.blade.applications.console.webapp;
