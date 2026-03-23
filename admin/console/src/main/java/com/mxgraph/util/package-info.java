/// # Utility Classes for mxGraph
///
/// This package provides essential utility classes and constants for the mxGraph library,
/// offering encoding, string manipulation, I/O operations, geometric transformations, and 
/// configuration capabilities.
///
/// ## Key Classes
///
/// - [Constants] - Defines application-wide constants including maximum request size 
///   (52,428,800 bytes), maximum area limits (100,000,000), and image domain configuration
/// - [Utils] - Comprehensive utility class providing string/byte array encoding and
///   manipulation functions, including compression/decompression using deflate/inflate
///   algorithms, stream operations with configurable buffer sizes, URL encoding with
///   ISO-8859-1 charset, and geometric transformations for rotated points and geometries
/// - [mxBase64] - High-performance BASE64 encoder and decoder implementation that
///   provides RFC 2045 compliant encoding/decoding methods for byte arrays, char arrays,
///   and strings, with both standard and optimized fast processing variants
///
/// ## Functionality Overview
///
/// ### Encoding and Compression
/// The package includes utilities for:
/// - BASE64 encoding and decoding with RFC 2045 compliance and optional line separators
/// - Data compression and decompression using Java's Deflater/Inflater classes
/// - URL component encoding with configurable character sets (default ISO-8859-1)
///
/// ### Stream and I/O Operations  
/// Provides helper methods for:
/// - Stream copying with default 4KB buffer size or configurable buffer sizes
/// - Input stream reading and conversion to strings using `BufferedReader`
/// - Efficient I/O operations optimized for performance
///
/// ### Geometric Transformations
/// Offers mathematical utilities for:
/// - Point rotation calculations using precomputed cosine and sine values
/// - Geometry transformation for rotated objects with center point coordinates
/// - Support for `mxGeometry` and `mxPoint` coordinate manipulations
///
/// ### Configuration Constants
/// Defines system-wide limits and settings for:
/// - Maximum request size of approximately 50MB
/// - Maximum area calculations (10,000 x 10,000)
/// - Image domain URL configuration for diagram resources
///
/// ## Performance Characteristics
///
/// The [mxBase64] implementation is optimized for high performance, offering approximately
/// 10x faster encoding/decoding on small arrays (10-1000 bytes) and 2-3x performance
/// improvement on larger arrays (10,000-1,000,000 bytes) compared to standard implementations.
/// The implementation avoids creating temporary arrays, reducing memory overhead and garbage
/// collection pressure.
///
/// @see Constants
/// @see Utils  
/// @see mxBase64
/// @see com.mxgraph.model.mxGeometry
package com.mxgraph.util;
