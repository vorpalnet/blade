<%@ page import="org.vorpal.blade.applications.console.config.*"%>
<%@ page import="java.util.*"%>
<%
StringBuilder appsHtml = new StringBuilder();

Set<String> apps = ConfigurationMonitor.queryApps();

for (String app : apps) {
	System.out.println("Running app: " + app);

	appsHtml.append("<li class=\"nav-item\"><a href=\"../json/index.jsp?configType=Domain&app=" + app
	+ "\" class=\"nav-link\" target=\"content_iframe\">" + app + "</a></li>\n");

}
%>
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta http-equiv="X-UA-Compatible" content="IE=edge">
<meta name="viewport"
	content="width=device-width, initial-scale=1, shrink-to-fit=no">
<title>Vorpal BLADE</title>

<!-- Global stylesheets -->
<link
	href="https://fonts.googleapis.com/css?family=Roboto:400,300,100,500,700,900"
	rel="stylesheet" type="text/css">
<link href="global_assets/css/icons/icomoon/styles.min.css"
	rel="stylesheet" type="text/css">
<link href="assets/css/all.min.css" rel="stylesheet" type="text/css">
<!-- /global stylesheets -->

<!-- Core JS files -->
<script src="jquery-3.6.0/jquery-3.6.0.min.js"></script>
<script src="bootstrap-4.6.0-dist/js/bootstrap.bundle.min.js"></script>
<!-- /core JS files -->

<!-- Theme JS files -->
<script src="assets/js/app.js"></script>
<!-- /theme JS files -->

</head>

<body class="">

	<!-- Main navbar -->
	<div class="navbar navbar-expand-lg navbar-dark navbar-static">
		<div class="d-flex flex-1 d-lg-none">
			<button type="button"
				class="navbar-toggler sidebar-mobile-main-toggle">
				<i class="icon-transmission"></i>
			</button>
			<button data-target="#navbar_search" type="button"
				class="navbar-toggler" data-toggle="collapse">
				<i class="icon-search4"></i>
			</button>
		</div>

		<div class="navbar-brand text-center text-lg-left">
			<a href="index.jsp" class="d-inline-block"> <img
				src="global_assets/images/logo_light.png" class="d-none d-sm-block"
				alt=""> <img src="global_assets/images/logo_icon_light.png"
				class="d-sm-none" alt="">
			</a>
		</div>

		<div class="collapse navbar-collapse order-2 order-lg-1"
			id="navbar_search">
			<div class="navbar-search d-flex align-items-center py-3 py-lg-0">
				<div
					class="form-group-feedback form-group-feedback-left flex-grow-1">
					<input type="text" class="form-control" placeholder="Search">
					<div class="form-control-feedback">
						<i class="icon-search4 text-white opacity-50"></i>
					</div>
				</div>
			</div>
		</div>

		<div
			class="order-1 order-lg-2 d-flex flex-1 flex-lg-0 justify-content-end align-items-center">
			<ul class="navbar-nav flex-row">
				<li class="nav-item"><a href="#"
					class="navbar-nav-link navbar-nav-link-toggler"> <i
						class="icon-make-group"></i> <span
						class="d-none d-lg-inline-block ml-2">Text link</span>
				</a></li>

				<li class="nav-item"><a href="#"
					class="navbar-nav-link navbar-nav-link-toggler"> <i
						class="icon-bell2"></i> <span
						class="badge badge-warning badge-pill">3</span>
				</a></li>

				<li
					class="nav-item nav-item-dropdown-lg dropdown dropdown-user h-100">
					<a href="#"
					class="navbar-nav-link navbar-nav-link-toggler dropdown-toggle d-inline-flex align-items-center h-100"
					data-toggle="dropdown"> <img
						src="global_assets/images/placeholders/placeholder.jpg"
						class="rounded-pill mr-lg-2" height="34" alt=""> <span
						class="d-none d-lg-inline-block">User</span>
				</a>

					<div class="dropdown-menu dropdown-menu-right">
						<a href="../logout" class="dropdown-item">Logout</a>
<!-- 					
						<a href="#"
							class="dropdown-item">Menu item 2</a> <a href="#"
							class="dropdown-item"> Menu item 3 <span
							class="badge badge-primary badge-pill ml-auto">2</span>
						</a>
						<div class="dropdown-divider"></div>
						<a href="#" class="dropdown-item">Menu item 4</a>
 -->					
					</div>
				</li>
			</ul>
		</div>
	</div>
	<!-- /main navbar -->


	<!-- Page header -->
	<div class="page-header">
		<div
			class="breadcrumb-line breadcrumb-line-light header-elements-sm-inline border-bottom">
			<div class="d-flex">
				<div class="breadcrumb">
					<a href="#" class="breadcrumb-item"><i class="icon-home2 mr-2"></i>
						Home</a> <a href="#" class="breadcrumb-item">Link</a> <span
						class="breadcrumb-item active">Current</span>
				</div>

				<a href="#" class="header-elements-toggle text-body d-sm-none"><i
					class="icon-more"></i></a>
			</div>

			<div class="header-elements d-none">
				<div class="breadcrumb justify-content-center">
					<a href="#" class="breadcrumb-elements-item"> Link </a>

					<div class="breadcrumb-elements-item dropdown p-0">
						<a href="#" class="breadcrumb-elements-item dropdown-toggle"
							data-toggle="dropdown"> Dropdown </a>

						<div class="dropdown-menu dropdown-menu-right">
							<a href="#" class="dropdown-item">Action</a> <a href="#"
								class="dropdown-item">Another action</a> <a href="#"
								class="dropdown-item">One more action</a>
							<div class="dropdown-divider"></div>
							<a href="#" class="dropdown-item">Separate action</a>
						</div>
					</div>
				</div>
			</div>
		</div>

		<div class="page-header-content d-sm-flex">
			<div class="page-title">
				<h4>
					<span class="font-weight-semibold">Seed</span> - Boxed content
				</h4>
			</div>

			<div class="my-sm-auto ml-sm-auto mb-3 mb-sm-0">
				<button type="button" class="btn btn-primary w-100 w-sm-auto">Button</button>
			</div>
		</div>
	</div>
	<!-- /page header -->


	<!-- Page content -->
	<div class="page-content pt-0">

		<!-- Main sidebar -->
		<div
			class="sidebar sidebar-light sidebar-main sidebar-expand-lg align-self-start">

			<!-- Sidebar content -->
			<div class="sidebar-content">

				<!-- Header -->
				<div class="sidebar-section sidebar-header">
					<div
						class="sidebar-section-body d-flex align-items-center justify-content-center pb-0">
						<h5 class="sidebar-resize-hide flex-1 mb-0">Navigation</h5>
						<div>
							<button type="button"
								class="btn btn-outline-light text-body border-transparent btn-icon rounded-pill btn-sm sidebar-control sidebar-main-resize d-none d-lg-inline-flex">
								<i class="icon-transmission"></i>
							</button>

							<button type="button"
								class="btn btn-outline-light text-body border-transparent btn-icon rounded-pill btn-sm sidebar-mobile-main-toggle d-lg-none">
								<i class="icon-cross2"></i>
							</button>
						</div>
					</div>
				</div>
				<!-- /header -->


				<!-- User menu -->
				<div class="sidebar-section sidebar-user">
					<div class="sidebar-section-body d-flex justify-content-center">
						<a href="#"> <img
							src="global_assets/images/placeholders/placeholder.jpg"
							class="rounded-circle" alt="">
						</a>

						<div class="sidebar-resize-hide flex-1 ml-3">
							<div class="font-weight-semibold">Jeff McDonald</div>
							<div class="font-size-sm line-height-sm text-muted">Developer
								Wizard</div>
						</div>
					</div>
				</div>
				<!-- /user menu -->


				<!-- Main navigation -->
				<div class="sidebar-section">
					<ul class="nav nav-sidebar" data-nav-type="accordion">

						<!-- Main -->
						<li class="nav-item-header pt-0"><div
								class="text-uppercase font-size-xs line-height-xs">Main</div> <i
							class="icon-menu" title="Main"></i></li>
						<li class="nav-item"><a href="index.html" class="nav-link">
								<i class="icon-home4"></i> <span> Dashboard <span
									class="d-block font-weight-normal opacity-50">No active
										orders</span>
							</span>
						</a></li>
						<!-- /main -->

						<!-- Layout -->
						<li
							class="nav-item nav-item-submenu nav-item-open nav-item-expanded">
							<a href="#" class="nav-link"><i class="icon-stack2"></i> <span>Applications</span></a>
							<ul class="nav nav-group-sub" data-submenu-title="Applications">

								<%=appsHtml.toString()%>

								<!-- 
								<li class="nav-item"><a href="../json/index.jsp?app=b2bua"
									class="nav-link" target="content_iframe">b2bua</a></li>

								<li class="nav-item"><a
									href="../json/index.jsp?app=genrec2" class="nav-link"
									target="content_iframe">genrec2</a></li>

								<li class="nav-item"><a
									href="../json/index.jsp?app=mediahub2" class="nav-link"
									target="content_iframe">mediahub2</a></li>

								<li class="nav-item"><a href="../json/index.jsp?app=minsdp"
									class="nav-link" target="content_iframe">minsdp</a></li>

								<li class="nav-item"><a
									href="../json/index.jsp?app=proxy-registrar" class="nav-link"
									target="content_iframe">proxy-registrar</a></li>

								<li class="nav-item"><a
									href="../json/index.jsp?app=transfer" class="nav-link"
									target="content_iframe">transfer</a></li>
-->

							</ul>
						</li>

						<!-- /layout -->

					</ul>
				</div>

				<div class="sidebar-section">
					<ul class="nav nav-sidebar" data-nav-type="accordion">

						<!-- Layout -->

						<li
							class="nav-item nav-item-submenu nav-item-open nav-item-expanded">
							<a href="#" class="nav-link"><i class="icon-stack2"></i> <span>Tools</span></a>
							<ul class="nav nav-group-sub" data-submenu-title="Tools">
								<li class="nav-item"><a href="../mxgraph/index.html"
									class="nav-link" target="content_iframe">FSMAR</a></li>
								<li class="nav-item"><a href="../json/index.jsp?configType=Domian&app=b2bua"
									class="nav-link" target="content_iframe">Configurator</a></li>
								<li class="nav-item"><a href="../easyui/index.html"
									class="nav-link" target="content_iframe">Media Tester</a></li>
								<li class="nav-item"><a href="../swagger/index.html"
									class="nav-link" target="content_iframe">Swagger</a></li>
							<!-- 	
								<li class="nav-item"><a href="layout_navbar_fixed.html"
									class="nav-link" target="content_iframe">Fixed navbar</a></li>
								<li class="nav-item"><a href="layout_navbar_hideable.html"
									class="nav-link" target="content_iframe">Hideable navbar</a></li>
								<li class="nav-item-divider"></li>
								<li class="nav-item"><a href="layout_no_header.html"
									class="nav-link" target="content_iframe">No header</a></li>
								<li class="nav-item"><a href="layout_no_footer.html"
									class="nav-link" target="content_iframe">No footer</a></li>
								<li class="nav-item"><a href="layout_fixed_footer.html"
									class="nav-link" target="content_iframe">Fixed footer</a></li>
								<li class="nav-item-divider"></li>
								<li class="nav-item"><a
									href="layout_2_sidebars_1_side.html" class="nav-link"
									target="content_iframe">2 sidebars on 1 side</a></li>
								<li class="nav-item"><a
									href="layout_2_sidebars_2_sides.html" class="nav-link"
									target="content_iframe">2 sidebars on 2 sides</a></li>
								<li class="nav-item"><a href="layout_3_sidebars.html"
									class="nav-link" target="content_iframe">3 sidebars</a></li>
								<li class="nav-item-divider"></li>
								<li class="nav-item"><a href="layout_boxed_page.html"
									class="nav-link" target="content_iframe">Boxed page</a></li>
								<li class="nav-item"><a href="layout_boxed_content.html"
									class="nav-link active" target="content_iframe">Boxed
										content</a></li>
							-->
							</ul>
						</li>
						
<!-- 						
						<li class="nav-item nav-item-submenu"><a href="#"
							class="nav-link"><i class="icon-tree5"></i> <span>Menu
									levels</span></a>
							<ul class="nav nav-group-sub" data-submenu-title="Menu levels">
								<li class="nav-item"><a href="#" class="nav-link"><i
										class="icon-IE"></i> Second level</a></li>
								<li class="nav-item nav-item-submenu"><a href="#"
									class="nav-link"><i class="icon-firefox"></i> Second level
										with child</a>
									<ul class="nav nav-group-sub">
										<li class="nav-item"><a href="#" class="nav-link"><i
												class="icon-android"></i> Third level</a></li>
										<li class="nav-item nav-item-submenu"><a href="#"
											class="nav-link"><i class="icon-apple2"></i> Third level
												with child</a>
											<ul class="nav nav-group-sub">
												<li class="nav-item"><a href="#" class="nav-link"><i
														class="icon-html5"></i> Fourth level</a></li>
												<li class="nav-item"><a href="#" class="nav-link"><i
														class="icon-css3"></i> Fourth level</a></li>
											</ul></li>
										<li class="nav-item"><a href="#" class="nav-link"><i
												class="icon-windows"></i> Third level</a></li>
									</ul></li>
								<li class="nav-item"><a href="#" class="nav-link"><i
										class="icon-chrome"></i> Second level</a></li>
							</ul></li>
 -->							
							
						<!-- /layout -->

					</ul>
				</div>

				<!-- /main navigation -->

			</div>
			<!-- /sidebar content -->

		</div>
		<!-- /main sidebar -->


		<!-- Main content -->
		<div class="content-wrapper container">

			<!-- Content area -->
			<div class="content">

				<!-- Basic card -->

				<iframe src="layout_boxed_content.html" width="100%" height="100%"
					name="content_iframe"></iframe>

				<!-- /basic card -->


				<!-- Basic table -->

				<!-- /basic table -->


				<!-- Form layouts -->

				<!-- /form layouts -->

			</div>
			<!-- /content area -->

		</div>
		<!-- /main content -->

	</div>
	<!-- /page content -->


	<!-- Footer -->
	<div
		class="navbar navbar-expand-lg navbar-light border-bottom-0 border-top">
		<div class="text-center d-lg-none w-100">
			<button type="button" class="navbar-toggler dropdown-toggle"
				data-toggle="collapse" data-target="#navbar-third">
				<i class="icon-menu mr-2"></i> Bottom navbar
			</button>
		</div>

		<div class="navbar-collapse collapse" id="navbar-third">
			<span class="navbar-text"> © 2015 - 2018. <a href="#">Limitless
					Web App Kit</a> by <a href="https://themeforest.net/user/Kopyov"
				target="_blank">Eugene Kopyov</a>
			</span>

			<ul class="navbar-nav ml-lg-auto">
				<li class="nav-item"><a href="#" class="navbar-nav-link">Help
						center</a></li>
				<li class="nav-item"><a href="#" class="navbar-nav-link">Policy</a></li>
				<li class="nav-item"><a href="#"
					class="navbar-nav-link font-weight-semibold">Upgrade your
						account</a></li>
				<li class="nav-item dropup"><a href="#" class="navbar-nav-link"
					data-toggle="dropdown"> <i
						class="icon-share4 d-none d-lg-inline-block"></i> <span
						class="d-lg-none">Share</span>
				</a>

					<div class="dropdown-menu dropdown-menu-right">
						<a href="#" class="dropdown-item"><i class="icon-dribbble3"></i>
							Dribbble</a> <a href="#" class="dropdown-item"><i
							class="icon-pinterest2"></i> Pinterest</a> <a href="#"
							class="dropdown-item"><i class="icon-github"></i> Github</a> <a
							href="#" class="dropdown-item"><i class="icon-stackoverflow"></i>
							Stack Overflow</a>
					</div></li>
			</ul>
		</div>
	</div>
	<!-- /footer -->



	<div class="btn-to-top btn-to-top-visible">
		<button type="button" class="btn btn-dark btn-icon rounded-pill">
			<i class="icon-arrow-up8"></i>
		</button>
	</div>
</body>
</html>

</html>
