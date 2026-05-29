<%--
    Default-web-app redirect. Mapped to /* (see web.xml), so this runs for every
    path WLS doesn't route to a more-specific WAR. Sends a 302 to the portal.

    Loop guard: if the request is already under /blade/portal, the portal WAR
    isn't deployed (otherwise it would have claimed the path), so redirecting
    again would loop the browser. Fail with 503 instead.
--%><%
    String uri = request.getRequestURI();
    if (uri != null && uri.startsWith("/blade/portal")) {
        response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "BLADE portal is not available.");
    } else {
        response.sendRedirect("/blade/portal/");
    }
%>
