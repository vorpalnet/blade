<%@ page language="java" pageEncoding="UTF-8" contentType="text/html; charset=UTF-8" trimDirectiveWhitespaces="true" %><%--
    Default-web-app handler. Mapped to /* (see web.xml), so this runs for every
    path WLS doesn't route to a more-specific WAR.

    The server's bare address ("/", "/blade") gets a 302 to the dashboard,
    BLADE's front door: its rail lists every admin application and service.

    Any other path is one no application on this server claims: answer 404 and
    say so. Redirecting those to the dashboard too made a link to an
    application deployed elsewhere (an engine-tier app on the admin address)
    look like a link to the dashboard.

    Loop guard: a request under /blade/dashboard reached here only because the
    dashboard WAR isn't deployed, so redirecting would loop. Fail with 503.
--%><%!
	static String esc(String s) {
		return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}
%><%
	String uri = request.getRequestURI();
	// "/" arrives as its welcome file, /index.jsp.
	if (uri == null || uri.equals("/") || uri.equals("/index.jsp") || uri.equals("/blade") || uri.equals("/blade/")) {
		response.sendRedirect("/blade/dashboard/");
		return;
	}
	if (uri.startsWith("/blade/dashboard")) {
		response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "The BLADE dashboard is not available.");
		return;
	}
	response.setStatus(HttpServletResponse.SC_NOT_FOUND);
%><!DOCTYPE html>
<html lang="en">
<head>
	<meta charset="UTF-8">
	<meta name="viewport" content="width=device-width, initial-scale=1.0">
	<title>Not on this server</title>
	<link rel="stylesheet" href="/blade/portal/brand/brand.css">
	<script src="/blade/portal/brand/theme.js"></script>
	<link rel="icon" type="image/svg+xml" href="/blade/portal/brand/favicon.svg">
</head>
<body>
	<main class="vorpal-page vorpal-page-narrow">
		<div class="vorpal-docs-content">
			<h1>Not on this server</h1>
			<p>Nothing on this server answers at <code><%= esc(uri) %></code>. The application may not be
				deployed, or it runs on another server, such as the engine tier, which has an address of its own.</p>
			<p><a href="/blade/dashboard/">Go to the dashboard</a>: its rail lists every application and opens
				each one where it runs.</p>
		</div>
	</main>
</body>
</html>
