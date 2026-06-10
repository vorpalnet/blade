<%@ page language="java" pageEncoding="UTF-8" contentType="text/html; charset=UTF-8" %>
<!DOCTYPE html>
<html lang="en">
<head>
	<meta charset="UTF-8">
	<meta name="viewport" content="width=device-width, initial-scale=1.0">
	<title>Sign In · BLADE Tuning</title>
	<link rel="stylesheet" href="/blade/portal/brand/brand.css">
	<link rel="icon" type="image/svg+xml" href="/blade/tuning/favicon.svg">
</head>
<body class="vorpal-login">

	<%
		Integer attempts = (Integer) session.getAttribute("loginAttempts");
		attempts = (attempts == null) ? 0 : attempts + 1;
		session.setAttribute("loginAttempts", attempts);
	%>

	<div class="vorpal-login-stack">

		<div class="vorpal-login-header">
			<h1>Vorpal <strong>BLADE</strong></h1>
			<p>Tuning · Blended Layer Application Development Environment</p>
		</div>

		<form class="vorpal-login-card" action="j_security_check" method="post" autocomplete="on">

			<% if (attempts > 0) { %>
				<div class="vorpal-login-error">Authentication failed. Verify your username and password, then try again.</div>
			<% } %>

			<div class="vorpal-form-row">
				<label for="j_username">Username</label>
				<input id="j_username" type="text" name="j_username" autocomplete="username" autofocus>
			</div>

			<div class="vorpal-form-row">
				<label for="j_password">Password</label>
				<input id="j_password" type="password" name="j_password" autocomplete="current-password">
			</div>

			<button class="vorpal-btn vorpal-btn-block" type="submit">Sign in</button>

			<div class="vorpal-login-footer">
				Authorized use only. All sessions are logged for audit purposes.
			</div>
		</form>

	</div>

</body>
</html>
