<%@ page language="java" pageEncoding="UTF-8" contentType="text/html; charset=UTF-8" trimDirectiveWhitespaces="true"
	import="java.util.*, javax.xml.parsers.*, org.w3c.dom.*" %><%--
  The master "not allowed" page: an admin application's 403 error page.
  Copied into each admin WAR at build time beside login.jsp and mapped in its
  web.xml (<error-page><error-code>403</error-code>), so a person signed in
  without a role that opens the application learns which roles do, instead of
  a bare "Forbidden". Edit it here, never in an application.

  A request that does not ask for HTML (a fetch from the application's own
  page, a script) gets the refusal as plain text, as it did before the page
  existed, so a JSON client never has to parse this markup.
--%><%!
	/// The role names inside this application's web.xml auth-constraints,
	/// sorted, once each: the roles that open it.
	static List<String> rolesThatOpen(javax.servlet.ServletContext app) {
		TreeSet<String> names = new TreeSet<>();
		try (java.io.InputStream in = app.getResourceAsStream("/WEB-INF/web.xml")) {
			if (in == null) return new ArrayList<>(names);
			DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
			f.setNamespaceAware(true);
			f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			NodeList constraints = f.newDocumentBuilder().parse(in).getElementsByTagNameNS("*", "auth-constraint");
			for (int i = 0; i < constraints.getLength(); i++) {
				NodeList r = ((Element) constraints.item(i)).getElementsByTagNameNS("*", "role-name");
				for (int j = 0; j < r.getLength(); j++) {
					String n = r.item(j).getTextContent().trim();
					if (!n.isEmpty() && !"*".equals(n)) names.add(n);
				}
			}
		} catch (Exception e) {
			// Unreadable: the page still says what happened, without the list.
		}
		return new ArrayList<>(names);
	}

	static String esc(Object o) {
		if (o == null) return "";
		return o.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}

	/// The group WebLogic maps each administration role to out of the box.
	static String group(String role) {
		switch (role) {
		case "Admin": return "Administrators";
		case "Deployer": return "Deployers";
		case "Monitor": return "Monitors";
		case "Operator": return "Operators";
		default: return null;
		}
	}
%><%
	response.setStatus(HttpServletResponse.SC_FORBIDDEN);
	response.setHeader("Cache-Control", "no-store");
	Object said = request.getAttribute("javax.servlet.error.message");
	String accept = request.getHeader("Accept");
	if (accept == null || !accept.contains("text/html")) {
		response.setContentType("text/plain; charset=UTF-8");
		out.print(said != null && !said.toString().isEmpty() ? said : "Forbidden");
		return;
	}
	String appName = application.getServletContextName() != null ? application.getServletContextName()
			: request.getContextPath();
	String user = request.getRemoteUser();
	List<String> roles = rolesThatOpen(application);
	List<String> held = new ArrayList<>();
	for (String r : roles) {
		if (request.isUserInRole(r)) held.add(r);
	}
%><!DOCTYPE html>
<html lang="en">
<head>
	<meta charset="UTF-8">
	<meta name="viewport" content="width=device-width, initial-scale=1.0">
	<title>Not allowed</title>
	<link rel="icon" type="image/svg+xml" href="/blade/portal/brand/favicon.svg">
	<style>
		:root { --bg: #f4f2f6; --panel: #fff; --ink: #1d1a22; --ink-2: #4a4552; --line: #d9d4df; --brand: #602671; }
		@media (prefers-color-scheme: dark) {
			:root { --bg: #16131a; --panel: #211d27; --ink: #ece8f1; --ink-2: #b4adbd; --line: #3a3442; --brand: #b97cd0; }
		}
		html, body { margin: 0; min-height: 100vh; background: var(--bg); color: var(--ink);
			font: 15px/1.5 system-ui, -apple-system, "Segoe UI", sans-serif; }
		main { box-sizing: border-box; max-width: 560px; margin: 12vh auto 0; padding: 28px 28px 24px;
			background: var(--panel); border: 1px solid var(--line); border-radius: 12px; }
		@media (max-width: 600px) { main { margin: 16px; } }
		h1 { margin: 0 0 12px; font-size: 22px; font-weight: 600; }
		p { margin: 0 0 12px; color: var(--ink-2); }
		strong { color: var(--ink); }
		ul { margin: 0 0 14px; padding-left: 20px; }
		li { margin: 2px 0; }
		.said { font-size: 13px; border-left: 3px solid var(--line); padding-left: 10px; }
		nav { display: flex; flex-wrap: wrap; gap: 18px; margin-top: 18px; }
		a { color: var(--brand); font-weight: 600; }
	</style>
</head>
<body>
	<main>
		<h1>Not allowed</h1>
		<p>You are signed in as <strong><%= esc(user != null ? user : "an unknown user") %></strong>,
			but <strong><%= esc(appName) %></strong> does not open for your account.</p>
<% if (!roles.isEmpty()) { %>
		<p>It opens for anyone with one of these WebLogic roles:</p>
		<ul>
<% for (String r : roles) { String g = group(r); %>
			<li><strong><%= esc(r) %></strong><%= g != null ? " · the " + esc(g) + " group by default" : "" %></li>
<% } %>
		</ul>
<% if (held.isEmpty()) { %>
		<p>You hold none of them. Ask your WebLogic administrator to add your account to one of these groups,
			then sign in again.</p>
<% } else { %>
		<p>You hold <strong><%= esc(String.join(", ", held)) %></strong>, which opens the application,
			but not this part of it.</p>
<% } %>
<% } %>
<% if (said != null && !said.toString().isEmpty() && !"Forbidden".equalsIgnoreCase(said.toString())) { %>
		<p class="said"><%= esc(said) %></p>
<% } %>
		<nav>
			<a href="/blade/dashboard/">Back to the dashboard</a>
			<a href="/blade/portal/logout">Sign in as someone else</a>
		</nav>
	</main>
</body>
</html>
