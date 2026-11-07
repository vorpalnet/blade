/// JSON for every BLADE JAX-RS application, the same on every deployment.
///
/// The framework jar carries a Jersey auto-discoverable,
/// [RestJsonDiscoverable], registered in its `META-INF/services`. Jersey runs
/// it in every application that bundles the framework, so no application
/// configures anything: each REST response is written by Jackson, with
/// properties in their declared order and nulls left out. See
/// [RestJsonDiscoverable] for why that takes more than a mapper.
package org.vorpal.blade.framework.v3.rest;
