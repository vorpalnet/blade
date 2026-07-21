/// SIP **trunk gateway** — the PSTN front door into BLADE/Gryphon.
///
/// One SIP servlet ({@link org.vorpal.blade.proto.gateway.GatewaySipServlet}) hosts N
/// independent {@link org.vorpal.blade.proto.gateway.VirtualGateway}s, each with its own
/// Contact IP. How each stays registered with its carrier is a pluggable
/// {@link org.vorpal.blade.proto.gateway.RegistrationStyle} (Jackson‑polymorphic, `type`
/// discriminator): {@link org.vorpal.blade.proto.gateway.RegisterDigestStyle} keeps a
/// credentialed trunk (Flowroute) alive with REGISTER + digest auth + a timer refresh
/// ({@link org.vorpal.blade.proto.gateway.RegisterCallflow}); {@link org.vorpal.blade.proto.gateway.IpAuthStyle}
/// is for IP‑authenticated carriers that need no REGISTER. New carriers = a new style
/// subclass. Modernized from the 2020 `vorpal-blade-gateway`; see the module README.
///
/// Phase 1 (here) is registration only. Phase 2 adds the B2BUA call bridge (inbound from
/// trunk → internal; outbound → trunk).
package org.vorpal.blade.proto.gateway;
