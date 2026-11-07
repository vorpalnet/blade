package org.vorpal.blade.framework.v3.rest;

import javax.annotation.Priority;
import javax.ws.rs.ConstrainedTo;
import javax.ws.rs.RuntimeType;
import javax.ws.rs.core.FeatureContext;

import org.glassfish.jersey.internal.spi.ForcedAutoDiscoverable;

import com.fasterxml.jackson.jaxrs.json.JacksonJaxbJsonProvider;
import com.fasterxml.jackson.jaxrs.json.JacksonJsonProvider;

/// Makes Jackson, through [RestJson], the only JSON a BLADE REST application
/// writes. Jersey finds it in the framework jar's `META-INF/services` and runs
/// it in every application that bundles the framework, before any JSON
/// feature; no application registers anything.
///
/// **The defect it fixes.** The JSON an application returned changed shape
/// from one deployment to the next. Jersey registers several JSON writers
/// for every application and ranks them equally, then picks one in an order
/// that is not stable across deployments. Redeploying one unchanged WAR, the
/// choice was measured flipping between
///
/// - the container's JSON-B writer, which `jersey-media-json-binding` forces
///   on every application: properties alphabetical, nulls left out, and every
///   Jackson annotation ignored, `@JsonIgnore` included;
/// - the Jackson writers in `blade-shared`: declared order, nulls written out.
///
/// **Why not just win the ranking.** A writer registered by the application,
/// even at `@Priority(1)`, only ties with the rest. Naming providers in
/// `jersey.config.server.provider.classnames` turns off scanning, and with it
/// every resource. So instead of outranking the others this removes their
/// differences:
///
/// - it sets `jersey.config.jsonFeature`, the property each JSON feature
///   (JSON-B, MOXy, Jersey's own Jackson) claims and backs off from when
///   another holds it, to a name none of them owns, so all three stand down;
/// - it registers [RestJson], which every remaining Jackson writer takes its
///   mapper from;
/// - it registers `JacksonJsonProvider` when no Jackson writer is registered
///   yet. An application whose `Application#getClasses()` lists its classes
///   turns scanning off, so `blade-shared`'s writers never arrive; JSON-B used
///   to be its only writer, and with JSON-B stood down it would have none and
///   answer every JSON request 500 (measured).
///
/// It runs first because Jersey configures auto-discoverables in ascending
/// `@Priority` and JSON-B's is 1800. An application that sets
/// `jersey.config.jsonFeature` itself keeps its choice. Server only: a JAX-RS
/// client in the same application is left as it is.
@Priority(1000)
@ConstrainedTo(RuntimeType.SERVER)
public class RestJsonDiscoverable implements ForcedAutoDiscoverable {

	/// Jersey's `CommonProperties.JSON_FEATURE`.
	static final String JSON_FEATURE = "jersey.config.jsonFeature";

	/// The value claimed: the name of no JSON feature.
	static final String CLAIM = "BladeRestJson";

	@Override
	public void configure(FeatureContext context) {
		if (context.getConfiguration().getProperty(JSON_FEATURE) == null) {
			context.property(JSON_FEATURE, CLAIM);
		}
		if (!context.getConfiguration().isRegistered(RestJson.class)) {
			context.register(RestJson.class);
		}
		if (!context.getConfiguration().isRegistered(JacksonJsonProvider.class)
				&& !context.getConfiguration().isRegistered(JacksonJaxbJsonProvider.class)) {
			context.register(JacksonJsonProvider.class);
		}
	}
}
