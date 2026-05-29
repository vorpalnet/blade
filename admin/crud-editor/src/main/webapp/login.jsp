<%@ page language="java" contentType="text/html; charset=UTF-8" %>
<!DOCTYPE html>
<html lang="en">
<head>
	<meta charset="UTF-8">
	<title>Sign in &middot; BLADE Crud Editor</title>
	<link rel="icon" type="image/svg+xml" href="/blade/crud-editor/favicon.svg">
	<link rel="stylesheet" href="/blade/portal/brand/brand.css">
</head>
<body class="vorpal-login">
	<div class="vorpal-login-stack">

		<!-- Wordmark + tagline above the card — same treatment as the
		     portal login.jsp; both share .vorpal-login* via brand.css. -->
		<div class="vorpal-login-header">
			<h1>Vorpal <strong>BLADE</strong></h1>
			<p>CRUD Editor · Translation Table Editor</p>
		</div>

		<div class="vorpal-login-card">
			<form action="j_security_check" method="post" autocomplete="on">
				<div class="vorpal-form-row">
					<label for="j_username">Username</label>
					<input type="text" name="j_username" id="j_username" autofocus>
				</div>
				<div class="vorpal-form-row">
					<label for="j_password">Password</label>
					<input type="password" name="j_password" id="j_password">
				</div>
				<button type="submit" class="vorpal-btn vorpal-btn-block">Sign in</button>
			</form>
		</div>

	</div>
</body>
</html>
