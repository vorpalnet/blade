package org.vorpal.blade.services.tpcc;

import java.io.IOException;

import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.container.ContainerResponseFilter;
import javax.ws.rs.container.PreMatching;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.Provider;

/**
 * This class was derived from this discussion group:
 * https://stackoverflow.com/questions/28065963/how-to-handle-cors-using-jax-rs-with-jersey
 * 
 * @author jeff
 *
 */

@Provider
@PreMatching
public class CorsFilter implements ContainerRequestFilter, ContainerResponseFilter {

	/**
	 * Method for ContainerRequestFilter.
	 */
	@Override
	public void filter(ContainerRequestContext request) throws IOException {

		// If it's a preflight request, we abort the request with
		// a 200 status, and the CORS headers are added in the
		// response filter method below.
		if (isPreflightRequest(request)) {
			request.abortWith(Response.ok().build());
		}
	}

	/**
	 * A preflight request is an OPTIONS request with an Origin header.
	 */
	private static boolean isPreflightRequest(ContainerRequestContext request) {
		return request.getHeaderString("Origin") != null && request.getMethod().equalsIgnoreCase("OPTIONS");
	}

	/**
	 * Method for ContainerResponseFilter.
	 */
	@Override
	public void filter(ContainerRequestContext request, ContainerResponseContext response) throws IOException {
		// if there is no Origin header, then it is not a
		// cross origin request. We don't do anything.

		String origin = request.getHeaderString("Origin");
		if (origin != null) {

			if (isPreflightRequest(request)) {
				response.getHeaders().add("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS, HEAD");
			}

			response.getHeaders().add("Access-Control-Allow-Credentials", "true");
			response.getHeaders().add("Access-Control-Allow-Headers",
					"X-Requested-With, Authorization, Accept-Version, Content-MD5, CSRF-Token, Content-Type");
			response.getHeaders().add("Access-Control-Expose-Headers", "Location, X-SEMAFONE-TARGET, Date");
			response.getHeaders().add("Access-Control-Allow-Origin", origin);
		}

	}
}
