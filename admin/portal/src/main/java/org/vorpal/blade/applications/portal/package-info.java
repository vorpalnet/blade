/// Launcher for every admin app deployed on this AdminServer.
///
/// The card deck is built live from JMX: `PortalCardsResource` walks the
/// deployment registry for `blade/*` web apps and reads each one's
/// name/tagline/description metadata from its `SettingsMXBean`, so any admin
/// app that registers one shows up automatically — with no portal redeploy.
package org.vorpal.blade.applications.portal;
