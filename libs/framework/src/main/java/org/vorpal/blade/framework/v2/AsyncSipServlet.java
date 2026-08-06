package org.vorpal.blade.framework.v2;

/**
 * The v2 SIP servlet base. Its implementation is now the version-neutral
 * baseline {@link org.vorpal.blade.framework.AsyncSipServlet}; this class is
 * retained as the v2 name so existing v2 servlets that extend it are
 * unaffected. Behavior is unchanged.
 *
 * @deprecated Extend the baseline
 *             {@link org.vorpal.blade.framework.AsyncSipServlet} instead. This
 *             class adds nothing — the whole implementation moved to the
 *             baseline, and this name remains only so existing servlets keep
 *             compiling and serialized state naming it still resolves on
 *             failover. Blade's own 14 subclasses have been migrated; att-tao
 *             (21), gryphon (2) and optum (1) have not, and will warn until
 *             they are.
 */
@Deprecated
public abstract class AsyncSipServlet extends org.vorpal.blade.framework.AsyncSipServlet {
	private static final long serialVersionUID = 1L;
}
