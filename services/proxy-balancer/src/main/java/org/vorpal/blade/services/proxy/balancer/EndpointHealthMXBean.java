package org.vorpal.blade.services.proxy.balancer;

/// Publishes this node's endpoint-health view for the admin tier, which
/// aggregates every engine's answer over federated DomainRuntime JMX.
/// Registered via explicit `StandardMBean(..., EndpointHealthMXBean.class,
/// true)` — never rely on reflective MBean introspection (it breaks; see the
/// Settings MBean precedent).
public interface EndpointHealthMXBean {

	/// The full plans/tiers/endpoints tree with per-endpoint health, as one
	/// JSON document (see [EndpointHealthMBean] for the shape).
	String getHealthJson();

}
