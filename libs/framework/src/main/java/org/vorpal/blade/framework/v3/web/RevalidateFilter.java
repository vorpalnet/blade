package org.vorpal.blade.framework.v3.web;

import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;

/// Makes the browser ask before it reuses a page, script or stylesheet.
///
/// The container serves static files with `Last-Modified` and no
/// `Cache-Control`, which lets a browser reuse its copy on a heuristic without
/// asking. After a redeploy it can pair a new page with yesterday's script:
/// an editor page calling a function the portal's cached `blade-editor.js`
/// does not have yet throws, and the editor never loads. `no-cache` still
/// allows the copy; it only requires a conditional request, answered `304`
/// while the file is unchanged.
///
/// Not annotated: every application carries the framework JAR, and an
/// annotated filter would be installed in all of them. An application that
/// wants it declares it in `web.xml`:
///
/// ```xml
/// <filter>
///   <filter-name>revalidate</filter-name>
///   <filter-class>org.vorpal.blade.framework.v3.web.RevalidateFilter</filter-class>
/// </filter>
/// <filter-mapping>
///   <filter-name>revalidate</filter-name>
///   <url-pattern>/*</url-pattern>
/// </filter-mapping>
/// ```
public class RevalidateFilter implements Filter {

	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
			throws IOException, ServletException {
		((HttpServletResponse) response).setHeader("Cache-Control", "no-cache");
		chain.doFilter(request, response);
	}
}
