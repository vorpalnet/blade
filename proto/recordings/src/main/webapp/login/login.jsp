<%--
  The container FORM login this app's web.xml names as its fallback for when
  WEB-INF/blade-oidc.properties is absent (OidcLoginFilter then calls
  req.authenticate(), which the container satisfies with this page). The action
  posts to j_security_check at the context root, not under /login/, so the
  container's FORM machinery sees it.
--%>
<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" session="false" %>
<!doctype html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Recordings Sign In</title>
<style>
  :root { --ink: #1f1f1f; --muted: #5a5a5a; --line: #d8d8d8; --panel: #f6f6f6; --brand: #602671; }
  body { margin: 0; font: 14px/1.45 -apple-system, "Segoe UI", Helvetica, Arial, sans-serif; color: var(--ink); background: #fff; display: flex; min-height: 100vh; align-items: center; justify-content: center; }
  .card { border: 1px solid var(--line); border-radius: 6px; padding: 28px 30px; width: 300px; }
  h1 { font-size: 18px; font-weight: 600; margin: 0 0 18px; }
  label { display: block; font-size: 12px; color: var(--muted); margin: 12px 0 4px; }
  input[type=text], input[type=password] { width: 100%; box-sizing: border-box; font: inherit; padding: 7px 9px; border: 1px solid var(--line); border-radius: 4px; }
  button { margin-top: 20px; width: 100%; font: inherit; padding: 8px 14px; border: 1px solid var(--brand); background: var(--brand); color: #fff; border-radius: 4px; cursor: pointer; }
  .app { color: var(--muted); font-size: 12px; margin-bottom: 16px; }
</style>
</head>
<body>
  <form class="card" action="<%= request.getContextPath() %>/j_security_check" method="post" enctype="application/x-www-form-urlencoded">
    <h1>Recordings</h1>
    <div class="app">Review &amp; Access Control</div>
    <label for="j_username">Username</label>
    <input type="text" name="j_username" id="j_username" autocomplete="username" autofocus>
    <label for="j_password">Password</label>
    <input type="password" name="j_password" id="j_password" autocomplete="current-password">
    <button type="submit">Sign in</button>
  </form>
</body>
</html>
