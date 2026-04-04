/// This package provides utilities and servlets for integrating with mxGraph diagramming functionality,
/// including file processing, XML formatting, and web-based diagram operations within the Vorpal Blade
/// console application framework.
///
/// ## Core Components
///
/// ### Web Servlets
/// - [OpenServlet] - Handles file upload and processing for various diagram formats including Gliffy and GraphML, with support for PNG files containing embedded XML data
/// - [SaveServlet] - Manages diagram export and file download operations with filename validation and Base64 decoding
/// - [Roundtrip] - Provides diagram storage and retrieval capabilities for web-based editing sessions using in-memory caching
///
/// ### Utility Classes
/// - [Formatter] - XML document parsing, pretty-printing, and transformation operations using DOM and XSLT
/// - [FindProblemJars] - Diagnostic utility for identifying problematic JAR files in the classpath
///
/// ## Key Features
///
/// The package supports:
/// - Multiple diagram format processing including PNG with embedded XML, Gliffy JSON, and GraphML
/// - XML document parsing, transformation, and pretty-printing with DOM manipulation
/// - File upload handling via `ServletFileUpload` from Apache Commons FileUpload
/// - Base64 encoding/decoding for binary data using mxGraph utilities
/// - Compressed text extraction from PNG files using custom zTXt chunk parsing
/// - Web-based diagram editing workflow with session-based document storage
/// - Filename validation and sanitization for secure file operations
/// - Empty diagram template initialization for new diagrams
///
/// ## PNG Processing
///
/// The [OpenServlet] includes specialized PNG processing capabilities:
/// - Custom PNG chunk parsing for zTXt compressed text extraction
/// - Support for PNG files with embedded XML diagram data
/// - Binary stream processing with chunk type identification
///
/// ## External Dependencies
///
/// This package integrates with:
/// - mxGraph library (`com.mxgraph.*`) for diagram processing, utilities, and Base64 operations
/// - Apache Commons FileUpload for multipart request handling
/// - Apache Commons Lang for HTML string escaping utilities
/// - Standard servlet API for web functionality
/// - DOM API and JAXP for XML document manipulation and transformation
///
/// @see OpenServlet
/// @see SaveServlet
/// @see Roundtrip
/// @see Formatter
/// @see FindProblemJars
package org.vorpal.blade.applications.console.mxgraph;
