/// # Analytics Framework Package
///
/// This package provides a comprehensive analytics and monitoring framework for SIP servlet applications,
/// enabling detailed tracking, measurement, and analysis of SIP communication patterns and performance metrics.
///
/// ## Core Components
///
/// ### Main Analytics Engine
/// - [Analytics] - Central analytics engine that orchestrates data collection, processing, and reporting
/// - [AnalyticsFilter] - SIP servlet filter that intercepts and analyzes SIP messages in real-time
/// - [EventSelector] - Configurable component for filtering and selecting specific events for analysis
///
/// ### Request/Response Processing
/// - [BufferedRequestWrapper] - Wrapper for SIP requests that enables buffering and inspection
/// - [BufferedResponseWrapper] - Wrapper for SIP responses with buffering capabilities for analytics
///
/// ### Sample Applications
/// - [AnalyticsAsyncSipServletSample] - Demonstrates analytics integration with asynchronous SIP servlets
/// - [AnalyticsB2buaSample] - Example implementation for back-to-back user agent analytics
/// - [AnalyticsTransferSample] - Sample showing analytics for call transfer scenarios
///
/// ## Key Features
///
/// - **Real-time SIP Message Analysis** - Monitor and analyze SIP traffic as it flows through the system
/// - **Performance Metrics Collection** - Gather detailed performance data including response times and throughput
/// - **Event-driven Architecture** - Flexible event selection and processing based on configurable criteria
/// - **Buffered Message Processing** - Efficient handling of SIP messages with buffering for complex analytics
/// - **Sample Implementations** - Ready-to-use examples for common SIP scenarios
///
/// ## Usage Example
///
/// ```java
/// // Configure analytics filter in web.xml or programmatically
/// AnalyticsFilter filter = new AnalyticsFilter();
/// 
/// // Set up event selection criteria
/// EventSelector selector = new EventSelector();
/// selector.addCriteria("method", "INVITE");
/// 
/// // Initialize analytics engine
/// Analytics analytics = new Analytics();
/// analytics.setEventSelector(selector);
/// ```
///
/// ## Integration
///
/// This package integrates seamlessly with the Vorpal Blade framework v2, providing analytics
/// capabilities that can be easily added to existing SIP servlet applications through
/// configuration and minimal code changes.
///
/// @see Analytics
/// @see AnalyticsFilter
/// @see EventSelector
package org.vorpal.blade.framework.v2.analytics;