<%@ page language="java"%>
<%@ taglib uri="http://java.sun.com/jsp/jstl/fmt" prefix="fmt"%>
<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c"%>
<html lang="en">
<head>
<title>BLADE Console</title>
<meta charset="UTF-8" />
<meta http-equiv="X-UA-Compatible" content="IE=edge,chrome=1">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<meta name="description" content="Vorpal BLADE Administration Console" />
<link rel="stylesheet" type="text/css" href="./style.css" />
<style>
@import url(http://fonts.googleapis.com/css?family=Ubuntu:400,700);

body {
	background: #563c55 url(./blurred.jpg) no-repeat center top;
	-webkit-background-size: cover;
	-moz-background-size: cover;
	background-size: cover;
}

.container>header h1, .container>header h2 {
	color: #fff;
	text-shadow: 0 1px 1px rgba(0, 0, 0, 0.7);
}
</style>
</head>
<body onload="document.loginData.j_username.focus();">

	<c:choose>
		<c:when test="${sessionScope.loginAttempts == null}">
			<c:set var="loginAttempts" scope="session" value="0" />
		</c:when>
		<c:when test="${sessionScope.loginAttempts != null}">
			<c:set var="loginAttempts" scope="session"
				value="${sessionScope.loginAttempts + 1}" />
		</c:when>
	</c:choose>

	<div class="container">

		<header>
			<h1>
				Vorpal <strong>BLADE</strong> Console
			</h1>
			<h2>Blended Layer Application Development Environment</h2>
		</header>

		<section class="main">
			<form class="form-3" action="j_security_check" method="post"
				enctype="application/x-www-form-urlencoded">
				<p class="clearfix">
					<label for="login">Username</label> <input type="text"
						name="j_username" id="j_username" autocomplete="on"
						placeholder="Username">
				</p>
				<p class="clearfix">
					<label for="password">Password</label> <input type="password"
						name="j_password" id="j_password" placeholder="Password">
				</p>
				<p class="clearfix">
					<input type="checkbox" name="remember" id="remember"> <label
						for="remember">Remember me</label>
				</p>
				<p class="clearfix">
					<input type="submit" name="submit" value="Sign in"
						onclick="form.submit();this.disabled=true;document.body.style.cursor = 'wait'; this.className='formButton-disabled';">
				</p>
			</form>
		</section>

		<header>
			<h2>
				<c:if test="${sessionScope.loginAttempts >0}">
                                Authentication Denied.<br />
                                Please Try Again.
                    </c:if>
			</h2>
		</header>

	</div>
</body>
</html>