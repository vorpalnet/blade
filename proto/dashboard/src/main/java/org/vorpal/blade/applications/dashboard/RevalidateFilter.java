package org.vorpal.blade.applications.dashboard;

import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.annotation.WebFilter;
import javax.servlet.http.HttpServletResponse;

/// Makes the browser ask before it reuses a page, script or stylesheet.
///
/// The container serves static files with `Last-Modified` and no
/// `Cache-Control`, which lets a browser reuse its copy on a heuristic without
/// asking. After a redeploy it can pair a new `index.html` with yesterday's
/// `board.js`, and the board renders unstyled or throws.
/// `no-cache` still allows the copy; it only requires a conditional request,
/// answered `304` while the file is unchanged. The data endpoints are fetched
/// fresh anyway, so one mapping covers the whole application.
@WebFilter("/*")
public class RevalidateFilter implements Filter {

	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
			throws IOException, ServletException {
		((HttpServletResponse) response).setHeader("Cache-Control", "no-cache");
		chain.doFilter(request, response);
	}
}
