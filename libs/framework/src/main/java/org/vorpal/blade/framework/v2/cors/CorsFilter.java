package org.vorpal.blade.framework.v2.cors;

/**
 * The v2 name for the CORS filter. Its implementation is now the
 * version-neutral {@link org.vorpal.blade.framework.cors.CorsFilter}; this class
 * is retained so a deployment descriptor naming the old class keeps working.
 * Behavior is unchanged.
 *
 * @deprecated Use {@link org.vorpal.blade.framework.cors.CorsFilter}. The
 *             framework's own {@code META-INF/web-fragment.xml} already names
 *             the new class, so nothing needs to change unless a WAR declares
 *             this filter itself.
 */
@Deprecated
public class CorsFilter extends org.vorpal.blade.framework.cors.CorsFilter {
}
