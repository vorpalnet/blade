<%@ page language="java" pageEncoding="UTF-8" contentType="text/html; charset=UTF-8" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<!DOCTYPE html>
<html lang="en">
<head>
	<title>BLADE Admin Portal</title>
	<meta charset="UTF-8" />
	<meta http-equiv="X-UA-Compatible" content="IE=edge,chrome=1">
	<meta name="viewport" content="width=device-width, initial-scale=1.0">
	<meta name="description" content="BLADE" />
	<link rel="stylesheet" href="/blade/portal/brand/brand.css">
	<link rel="stylesheet" type="text/css" href="/blade/portal/assets/style.css" />
	<link rel="icon" type="image/svg+xml" href="/blade/portal/brand/favicon.svg">
	<style>
		@import url(http://fonts.googleapis.com/css?family=Ubuntu:400,700);

		/* Fill the whole viewport so the backdrop image covers the page
		   (previously the body was only as tall as its content, so the image
		   stopped partway down and bare white showed below). */
		html, body {
			min-height: 100vh;
			margin: 0;
		}

		body {
			/* padding (not a child margin) creates the top breathing room —
			   a child margin-top collapses through the body and exposes a
			   white bar above the background. box-sizing keeps it within 100vh. */
			box-sizing: border-box;
			padding-top: 9vh;
			background: #563c55 url(/blade/portal/assets/blurred.jpg) no-repeat center center fixed;
			background-size: cover;
		}

		/* Match the gap above the panel to the gap below it (the panel's own
		   30px bottom margin / the "Authentication Denied" line). style.css
		   ships .form-3 with a 60px top margin; tighten it to 30px so the
		   "BLADE Login" title sits the same distance above the panel. */
		.form-3 {
			margin-top: 30px;
		}

		/* "BLADE Login" title with the white VorpalBoy splotch to its left,
		   centered above the panel. */
		.container > header {
			text-align: center;
			text-shadow: 0 1px 2px rgba(0, 0, 0, 0.55);
		}
		.container > header:first-child {
			display: flex;
			align-items: center;
			justify-content: center;
			gap: 14px;
		}
		.login-mark {
			height: 40px;
			width: auto;
		}
		.container > header h1 {
			margin: 0;
			font-weight: 300;
			font-size: 30px;
			letter-spacing: 0.04em;
			color: #fff;
		}
		/* Retry message (second header, below the panel) */
		.container > header h2 {
			margin: 12px 0 0;
			font-weight: 400;
			font-size: 14px;
			color: rgba(255, 255, 255, 0.85);
		}

		/* Standard copyright, pinned to the bottom over the backdrop. */
		.login-copyright {
			position: fixed;
			left: 0;
			right: 0;
			bottom: 0;
			text-align: center;
			padding: 14px;
			font-size: 12px;
			color: rgba(255, 255, 255, 0.6);
			text-shadow: 0 1px 2px rgba(0, 0, 0, 0.55);
		}
	</style>
</head>
<body>

	<c:choose>
		<c:when test="${sessionScope.loginAttempts == null}">
			<c:set var="loginAttempts" scope="session" value="0" />
		</c:when>
		<c:when test="${sessionScope.loginAttempts != null}">
			<c:set var="loginAttempts" scope="session" value="${sessionScope.loginAttempts + 1}" />
		</c:when>
	</c:choose>

	<div class="container">

		<header>
			<img class="login-mark" src="/blade/portal/brand/VorpalBoy_white.svg" alt="">
			<h1>BLADE Login</h1>
		</header>

		<section class="main">
			<form class="form-3" action="j_security_check" method="post" enctype="application/x-www-form-urlencoded">
				<p class="clearfix">
					<label for="j_username">Username</label>
					<input type="text" name="j_username" id="j_username" autocomplete="username" placeholder="Username" autofocus>
				</p>
				<p class="clearfix">
					<label for="j_password">Password</label>
					<input type="password" name="j_password" id="j_password" autocomplete="current-password" placeholder="Password">
				</p>
				<p class="clearfix">
					<input type="checkbox" name="remember" id="remember">
					<label for="remember">Remember me</label>
				</p>
				<p class="clearfix">
					<input type="submit" name="submit" value="Sign in"
						onclick="form.submit();this.disabled=true;document.body.style.cursor = 'wait'; this.className='formButton-disabled';">
				</p>
			</form>
		</section>

		<header>
			<h2>
				<c:if test="${sessionScope.loginAttempts > 0}">
					Authentication Denied.<br />
					Please Try Again.
				</c:if>
			</h2>
		</header>

	</div>

	<footer class="login-copyright">
		&copy; Vorpal Networks 2013&ndash;2026 &middot; MIT License
	</footer>
</body>
</html>
