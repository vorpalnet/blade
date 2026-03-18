/**
 * Use the SettingsManager Generics class to dynamically read and write configuration files.
 *
 * <h2>Key Classes:</h2>
 * <ul>
 *   <li>{@link org.vorpal.blade.framework.v2.config.SettingsManager} - Main entry point for configuration management</li>
 *   <li>{@link org.vorpal.blade.framework.v2.config.Configuration} - Base configuration class with logging and session parameters</li>
 *   <li>{@link org.vorpal.blade.framework.v2.config.RouterConfig} - Router configuration with selectors and translation maps</li>
 *   <li>{@link org.vorpal.blade.framework.v2.config.Translation} - Translation rule with attributes and request URI</li>
 *   <li>{@link org.vorpal.blade.framework.v2.config.TranslationsMap} - Abstract base for translation lookup maps</li>
 *   <li>{@link org.vorpal.blade.framework.v2.config.Selector} - Extracts keys from SIP messages using regex patterns</li>
 *   <li>{@link org.vorpal.blade.framework.v2.config.Condition} - Collection of comparisons for request matching</li>
 *   <li>{@link org.vorpal.blade.framework.v2.config.SessionParameters} - SIP session management configuration</li>
 * </ul>
 *
 * @since 2.0
 */
package org.vorpal.blade.framework.v2.config;