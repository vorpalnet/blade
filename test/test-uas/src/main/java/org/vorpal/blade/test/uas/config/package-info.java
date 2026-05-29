/// Configuration classes for the Test UAS module.
///
/// Response behavior is driven entirely by the Request-URI (`status`, `delay`,
/// `refer`), so there are no app-specific settings here.
///
/// ## Key Components
///
/// - [TestUasConfig] - concrete [org.vorpal.blade.framework.v2.config.Configuration]
///   subclass carrying only the inherited logging and session parameters
/// - [TestUasConfigSample] - seeds FINEST logging and a 60-second session
///   expiration for the SettingsManager-generated sample
///
/// @see org.vorpal.blade.test.uas.UasServlet
/// @see org.vorpal.blade.framework.v2.config.Configuration
package org.vorpal.blade.test.uas.config;
