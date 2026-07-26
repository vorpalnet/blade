package org.vorpal.blade.framework.v2;

/**
 * The v2 SIP servlet base. Its implementation is now the version-neutral
 * baseline {@link org.vorpal.blade.framework.AsyncSipServlet}; this class is
 * retained as the v2 name so existing v2 servlets that extend it are
 * unaffected. Behavior is unchanged.
 */
public abstract class AsyncSipServlet extends org.vorpal.blade.framework.AsyncSipServlet {
	private static final long serialVersionUID = 1L;
}
