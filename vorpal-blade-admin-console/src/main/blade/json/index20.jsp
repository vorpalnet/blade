<%@ page import="org.vorpal.blade.applications.console.config.*"%>
<%@ page import="java.util.*"%>
<%@ page import="java.security.*"%>
<%@ page import="org.vorpal.blade.applications.console.config.test.*"%>
<%@ page import="java.util.*"%>
<%@ page import="java.nio.file.*"%>
<%
StringBuilder appsHtml = new StringBuilder();

Set<String> apps = ConfigurationMonitor.queryApps();

// ServletContext sc = getServletContext();
Principal principal = request.getUserPrincipal();
String user = principal.getName();

for (String app : apps) {
	System.out.println("Running app: " + app);

	appsHtml.append("<li class=\"nav-item\"><a href=\"./index.jsp?configType=Domain&app=" + app
	+ "\" class=\"nav-link\" target=\"content_iframe\">" + app + "</a></li>\n");

}

System.out.println("Test #2");

String exception = "No Exceptions.";
String app = request.getParameter("app");
String configType = request.getParameter("configType");
System.out.println("app=" + app + ", configType=" + configType);

String jsonData = request.getParameter("jsonData");

System.out.println("Form submitted jsonData: ");
System.out.println(jsonData);

ConfigHelper cfgHelper = new ConfigHelper(app, configType);
cfgHelper.getSettings();

String jsonSchema = cfgHelper.loadFile("SCHEMA");
String jsonSample = cfgHelper.loadFile("SAMPLE");

if (jsonData != null && jsonData.length() > 0) {
	System.out.println("Saving submitted form data locally..." + jsonData.length());
	System.out.println(jsonData);
	cfgHelper.saveFileLocally(configType, jsonData);
} else {

	try {

		System.out.println("Loading json data for type " + configType);
		jsonData = cfgHelper.loadFile(configType);

		if (jsonData == null || jsonData.length() == 0) {
	System.out.println("No json data found, using sample.");
	jsonData = jsonSample;
		}
	} catch (Exception ex) {
		System.out.println("Exception loading jasonData: " + ex.getMessage());
		jsonData = jsonSample;
	}

}

cfgHelper.closeSettings();

Set<String> dirContents = cfgHelper.listFilesUsingFilesList("config/custom/vorpal/");
%>
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta http-equiv="X-UA-Compatible" content="IE=edge">
<meta name="viewport" content="width=device-width, initial-scale=1, shrink-to-fit=no">
<title>Vorpal BLADE</title>

<!-- Global stylesheets -->
<link href="https://fonts.googleapis.com/css?family=Roboto:400,300,100,500,700,900" rel="stylesheet" type="text/css">
<link href="./global_assets/css/icons/icomoon/styles.min.css" rel="stylesheet" type="text/css">
<link href="./assets/css/all.min.css" rel="stylesheet" type="text/css">
<!-- /global stylesheets -->

<!-- Core JS files -->
<script src="jquery-3.6.0/jquery-3.6.0.min.js"></script>
<script src="bootstrap-4.6.0-dist/js/bootstrap.bundle.min.js"></script>
<!-- /core JS files -->

<!-- Theme JS files -->
<script src="assets/js/app.js"></script>
<!-- /theme JS files -->



<link rel="stylesheet" href='lib/bootstrap-3.4.1-dist/css/bootstrap.min.css' />
<link rel="stylesheet" href="lib/codemirror/codemirror.css">
<link rel="stylesheet" href="lib/bootstrap-select-v1.13.14/css/bootstrap-select.min.css">
<link rel="stylesheet" href="lib/octicons/octicons.css">
<link rel="stylesheet" href='lib/brutusin-json-forms.min.css' />
<script src='lib/jquery-1.11.3.min.js'></script>
<script src='lib/bootstrap-3.4.1-dist/js/bootstrap.min.js'></script>
<script src="lib/codemirror/codemirror.js"></script>
<script src="lib/codemirror/codemirror-javascript.js"></script>
<script src="lib/markdown.min.js"></script>
<script src="lib/bootstrap-select-v1.13.14/js/bootstrap-select.min.js"></script>
<script src="lib/bootstrap-select-v1.13.14/js/i18n/defaults-en_US.min.js"></script>
<script src="./lib/brutusin-json-forms.js"></script>
<script src="./lib/brutusin-json-forms-bootstrap.min.js"></script>

<script lang="javascript">

function saveData(app, configType, data){
	try {

		if(selectedTab=="form"){
			console.log("saving data from 'form'... ");
			inputString.data = JSON.stringify(bf.getData(), null, 4);
		}else if(selectedTab=="data"){
			console.log("saving data from 'JSON'... ");
			inputString.data = codeMirrors["data"].getValue()
		}else{
			console.log("Error: Choose either Form or JSON tab to save.");
			console.log("Defaulting to 'form'...");
			inputString.data = JSON.stringify(bf.getData(), null, 4);
		}
		
      // put the JSON 'data' in a hidden form field called 'jsonData'.      
 	  $("#jsonData").val(inputString.data);
 	  
	  // Modify the URL
	  $('#bladeForm').attr('action', $('#bladeForm').attr('action')+'index.jsp?configType='+configType+"&app="+app);

	} catch (e) {
	    console.error(e);
	}
}


function chgAction(){
	var configType = $("#examples :selected").text();

	console.log("configType: "+configType);
	
	var action = '?app={0}&configType={1}'; 
	action = action.replace('{0}', app);
	action = action.replace('{1}', configType);
    document.forms.bladeForm.action = action;
}





</script>


<script lang="javascript">
		const currentUrl = window.location.href;
		const queryString = window.location.search;
		const urlParams = new URLSearchParams(queryString);
		const app = urlParams.get('app');

        jsonSchema = <%=jsonSchema%>;
		jsonData = <%=jsonData%>;
		jsonSample = <%=jsonSample%>;

        
            var BrutusinForms = brutusin["json-forms"];
            BrutusinForms.bootstrap.addFormatDecorator("inputstream", "file", "glyphicon-search", function (element) {
                alert("user callback on element " + element)
            });
            BrutusinForms.bootstrap.addFormatDecorator("color", "color");
            BrutusinForms.bootstrap.addFormatDecorator("date", "date");
            var codeMirrors = new Object();

			





            
            var input = new Object();
            var inputString = new Object();
            var title;
            var desc;
            var selectedDemo;
            var demos = [
                ["Domain",
                    jsonSchema,
                    jsonData,
                    jsonSample,
                    "Domain-wide configuration (typical)"],
                ["Cluster",
                    jsonSchema,
                    jsonData,
                    jsonSample,
                    "Cluster-specific configuration"],
                ["Server",
                    jsonSchema,
                    jsonData,
                    jsonSample,
                    "Server-specific configuration"]                        
            ];
            
//            var selectedTab = "schema";
            //var selectedTab = "data";
            var selectedTab;
            var bf;

            function selectExample(selectedExampleIndex) {

                console.log("function selectExample, selectedExampleIndex="+selectedExampleIndex);

				switch(selectedExampleIndex){
				case 0:
					configType = "domain";
					break;
				case 1:
					configType = "cluster";
					break;
				case 2:
					configType = "server";
					break;
				}
                
                document.getElementById("examples").selectedIndex = selectedExampleIndex;

                input.schema = demos[selectedExampleIndex][1];
                input.data = demos[selectedExampleIndex][2];
                input.sample = demos[selectedExampleIndex][3];
                
                inputString.schema = JSON.stringify(input.schema, null, 2);
                inputString.data = input.data ? JSON.stringify(input.data, null, 2) : "";
                inputString.sample = input.sample ? JSON.stringify(input.sample, null, 2) : "";

				console.log("selectExample... selectedTab="+selectedTab);

                if(selectedTab){
                   if (codeMirrors[selectedTab]) {
                       codeMirrors[selectedTab].setValue(inputString[selectedTab]);
                   }
                }
                title = demos[selectedExampleIndex][0];
                desc = markdown.toHTML(demos[selectedExampleIndex][4]);
            }

            function route() {
                if (!window.onhashchange) {
                    window.onhashchange = route;
                }
                selectedDemo = parseInt(window.location.hash.substring(1));
                if (isNaN(selectedDemo) || selectedDemo < 0 || selectedDemo >= demos.length) {
                    selectedDemo = 0;
                }
                selectExample(selectedDemo);
            }

            function generateForm() {
                var schema;
                var data;
                var message;
                var sample;
                var tabId;

				console.log("generateForm... selectedTab="+selectedTab);

/*
				if(prevTab=="form"){
					inputString.data = JSON.stringify(bf.getData(), null, 4);
					console.log("setting inputString.data from bf.getData()");
				}else if(prevTab=="data"){
					inputString.data = codeMirrors["data"].getValue()
					console.log("setting inputString.data from codeMirrors[\"data\"].getValue()");
				}
*/
				inputString.data = JSON.stringify(jsonData, null, 4)
				inputString.schema = JSON.stringify(jsonSchema, null, 4)
				inputString.sample = JSON.stringify(jsonSample, null, 4)



				
                //inputString[selectedTab] = codeMirrors[selectedTab].getValue();
                
                $("#jsonAlert").hide();
                try {
                    message = "The was a syntax error in the schema JSON";
                    tabId = "schema";
                    eval("schema=" + inputString.schema);
                    if (inputString.data) {
                        message = "The was a syntax error in the initial data JSON";
                        tabId = "data";
                        eval("data=" + inputString.data);
                    } else {
                        data = null;
                    }

                    if (inputString.sample) {
                        message = "The was a syntax error in the initial sample JSON";
                        tabId = "sample";
                        eval("sample=" + inputString.sample);
                    } else {
                        sample = null;
                    }

                    
/*                     if (inputString.sample) {
                        tabId = "sample";
                        message = "The was a syntax error in the resolver code";
                        eval("sample=" + inputString.sample);
                        if ("function" !== typeof sample) {
                            throw "Schema sample does not evaluate to a function";
                        }
                    }
  */                   
                } catch (err) {
                    document.getElementById('error-message').innerHTML = message + (err ? ". " + err : "");
                    $('[href=#' + tabId + ']').tab('show');
                    $("#jsonAlert").show();
                    return;
                }
                

                // $('#formLink').click();


                bf = BrutusinForms.create(schema);

/*                 if (resolver) {
                    bf.schemaResolver = resolver;
                }
 */                
                // jwm - make container global?
                container = document.getElementById('form-container');
 				// var container = document.getElementById('form-container');
				console.log("container.firstChild="+container.firstChild); 				
                while (container.firstChild) {
                    container.removeChild(container.firstChild);
                }
                if (title) {
                    document.getElementById('example-title').innerHTML = title;
                }
                if (desc) {
                    document.getElementById('example-desc').innerHTML = desc;
                }

                bf.render(container, data);
            }

        </script>


</head>

<body onload="generateForm()">

	<!-- Main navbar -->

	<div>
		<h1>
			<img alt="vorpal-logo" src="./vorpal-logo-small.png" style="padding: 5px 5px 5px 5px; border: 0; height: 80px;" />
			<code>BLADE</code>
			Configurator
		</h1>
	</div>

	<!-- 
	<div class="navbar navbar-expand-lg navbar-dark navbar-static">
		<div class="d-flex flex-1 d-lg-none">
			<button type="button" class="navbar-toggler sidebar-mobile-main-toggle">
				<i class="icon-transmission"></i>
			</button>
			<button data-target="#navbar_search" type="button" class="navbar-toggler" data-toggle="collapse">
				<i class="icon-search4"></i>
			</button>
		</div>

		<div class="navbar-brand text-center text-lg-left">

			<ul class="navbar-nav flex-row">
				<li class="nav-item"><a href="/blade/limitless/index.jsp" class="navbar-nav-link navbar-nav-link-toggler"> <i class="icon-make-group"></i> <span
						class="d-none d-lg-inline-block ml-2">BLADE</span>
				</a></li>
			</ul>
		</div>

		<div class="collapse navbar-collapse order-2 order-lg-1" id="navbar_search">
			<div class="navbar-search d-flex align-items-center py-3 py-lg-0">
				<div class="form-group-feedback form-group-feedback-left flex-grow-1">
					<input type="text" class="form-control" placeholder="Search">
					<div class="form-control-feedback">
						<i class="icon-search4 text-white opacity-50"></i>
					</div>
				</div>
			</div>
		</div>

		<div class="order-1 order-lg-2 d-flex flex-1 flex-lg-0 justify-content-end align-items-center">
			<ul class="navbar-nav flex-row">
				<li class="nav-item"><a href="#" class="navbar-nav-link navbar-nav-link-toggler"> <i class="icon-make-group"></i> <span
						class="d-none d-lg-inline-block ml-2">Text link</span>
				</a></li>

				<li class="nav-item"><a href="#" class="navbar-nav-link navbar-nav-link-toggler"> <i class="icon-bell2"></i> <span
						class="badge badge-warning badge-pill">3</span>
				</a></li>

				<li class="nav-item nav-item-dropdown-lg dropdown dropdown-user h-100"><a href="#"
					class="navbar-nav-link navbar-nav-link-toggler dropdown-toggle d-inline-flex align-items-center h-100" data-toggle="dropdown"> <img
						src="./global_assets/images/placeholders/placeholder.jpg" class="rounded-pill mr-lg-2" height="34" alt=""> <span class="d-none d-lg-inline-block"><%=user%></span>
				</a>

					<div class="dropdown-menu dropdown-menu-right">
						<a href="../logout" class="dropdown-item">Logout</a>
					
						<a href="#"
							class="dropdown-item">Menu item 2</a> <a href="#"
							class="dropdown-item"> Menu item 3 <span
							class="badge badge-primary badge-pill ml-auto">2</span>
						</a>
						<div class="dropdown-divider"></div>
						<a href="#" class="dropdown-item">Menu item 4</a>
					</div></li>
			</ul>
		</div>
	</div>
 -->




	<!-- /main navbar -->


	<!-- Page header -->

	<!-- 	
	<div class="page-header">
		<div class="breadcrumb-line breadcrumb-line-light header-elements-sm-inline border-bottom">
			<div class="d-flex">
				<div class="breadcrumb">
					<a href="#" class="breadcrumb-item"><i class="icon-home2 mr-2"></i> Home</a> <a href="#" class="breadcrumb-item">Link</a> <span
						class="breadcrumb-item active">Current</span>
				</div>

				<a href="#" class="header-elements-toggle text-body d-sm-none"><i class="icon-more"></i></a>
			</div>

			<div class="header-elements d-none">
				<div class="breadcrumb justify-content-center">
					<a href="#" class="breadcrumb-elements-item"> Link </a>

					<div class="breadcrumb-elements-item dropdown p-0">
						<a href="#" class="breadcrumb-elements-item dropdown-toggle" data-toggle="dropdown"> Dropdown </a>

						<div class="dropdown-menu dropdown-menu-right">
							<a href="#" class="dropdown-item">Action</a> <a href="#" class="dropdown-item">Another action</a> <a href="#" class="dropdown-item">One more action</a>
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
	 -->

	<!-- /page header -->


	<!-- Page content -->
	<div class="page-content pt-0">

		<!-- Main sidebar -->
		<div class="sidebar sidebar-light sidebar-main sidebar-expand-lg align-self-start">

			<!-- Sidebar content -->
			<div class="sidebar-content">

				<!-- Header -->
				<div class="sidebar-section sidebar-header">
					<div class="sidebar-section-body d-flex align-items-center justify-content-center pb-0">
						<h5 class="sidebar-resize-hide flex-1 mb-0">Navigation</h5>
						<div>
							<button type="button"
								class="btn btn-outline-light text-body border-transparent btn-icon rounded-pill btn-sm sidebar-control sidebar-main-resize d-none d-lg-inline-flex">
								<i class="icon-transmission"></i>
							</button>

							<button type="button" class="btn btn-outline-light text-body border-transparent btn-icon rounded-pill btn-sm sidebar-mobile-main-toggle d-lg-none">
								<i class="icon-cross2"></i>
							</button>
						</div>
					</div>
				</div>
				<!-- /header -->


				<!-- User menu -->
				<div class="sidebar-section sidebar-user">
					<div class="sidebar-section-body d-flex justify-content-center">
						<a href="#"> <img src="./global_assets/images/placeholders/placeholder.jpg" class="rounded-circle" alt="">
						</a>

						<div class="sidebar-resize-hide flex-1 ml-3">
							<div class="font-weight-semibold"><%=user%></div>
							<div class="font-size-sm line-height-sm text-muted">Administrator</div>
						</div>
					</div>
				</div>
				<!-- /user menu -->


				<!-- Main navigation -->
				<div class="sidebar-section">
					<ul class="nav nav-sidebar" data-nav-type="accordion">

						<!-- Main -->
						<li class="nav-item-header pt-0"><div class="text-uppercase font-size-xs line-height-xs">Main</div> <i class="icon-menu" title="Main"></i></li>
						<li class="nav-item"><a href="index.html" class="nav-link"> <i class="icon-home4"></i> <span> Dashboard <span
									class="d-block font-weight-normal opacity-50">No active orders</span>
							</span>
						</a></li>
						<!-- /main -->

						<!-- Layout -->
						<li class="nav-item nav-item-submenu nav-item-open nav-item-expanded"><a href="#" class="nav-link"><i class="icon-stack2"></i> <span>Applications</span></a>
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

							</ul></li>

						<!-- /layout -->

					</ul>
				</div>

				<div class="sidebar-section">
					<ul class="nav nav-sidebar" data-nav-type="accordion">

						<!-- Layout -->

						<li class="nav-item nav-item-submenu nav-item-open nav-item-expanded"><a href="#" class="nav-link"><i class="icon-stack2"></i> <span>Tools</span></a>
							<ul class="nav nav-group-sub" data-submenu-title="Tools">
								<li class="nav-item"><a href="../mxgraph/index.html" class="nav-link" target="content_iframe">FSMAR</a></li>
								<li class="nav-item"><a href="../json/index.jsp?configType=Domian&app=b2bua" class="nav-link" target="content_iframe">Configurator</a></li>
								<li class="nav-item"><a href="../easyui/index.html" class="nav-link" target="content_iframe">Media Tester</a></li>
								<li class="nav-item"><a href="../swagger/index.html" class="nav-link" target="content_iframe">Swagger</a></li>
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
							</ul></li>

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
		<!--  div class="container"-->
		<div style="width: 90%;">
			<!-- 
			<h1>
				<img alt="vorpal-logo" src="./vorpal-logo-small.png" style="padding: 5px 5px 5px 5px; border: 0; height: 80px;" />
				<code>BLADE</code>
				Configurator
			</h1>
 -->
			<div class="panel-group" id="accordion" role="tablist" aria-multiselectable="true">

				<form id="bladeForm" name="bladeForm" method="post" action="">

					<input type="hidden" id="jsonData" name="jsonData">


					<div class="panel panel-primary">
						<div class="panel-heading" role="tab" id="headingOne">
							<h4 class="panel-title"><%=app.toUpperCase()%></h4>
						</div>
						<div id="collapseInput" class="panel-collapse collapse in" role="tabpanel" aria-labelledby="headingOne">


							<div class="panel-body">
								<label for="examples">Configuration type:</label>

								<!--select
							class="form-control" id="examples"
							onchange="document.location.hash = this.selectedIndex;">
							<script type="text/javascript">
                                    for (var i = 0; i < demos.length; i++) {
                                        document.write("<option " + (selectedDemo === i ? "selected=true" : "") + ">" + demos[i][0] + "</option>");
                                    }
                            </script>
						</select-->

								<select class="form-control" id="examples" onchange="chgAction();this.form.submit()">
									<script type="text/javascript">
										for (var i = 0; i < demos.length; i++) {
											document
													.write("<option "
															+ (selectedDemo === i ? "selected=true"
																	: "") + ">"
															+ demos[i][0]
															+ "</option>");
										}
									</script>
								</select> <br>

								<ul class="nav nav-tabs" role="tablist">
									<li role="presentation" class="active"><a href="index.jsp#form" aria-controls="form" role="tab" data-toggle="tab">Form</a></li>
									<li role="presentation"><a href="index.jsp#data" aria-controls="data" role="tab" data-toggle="tab">JSON</a></li>
									<li role="presentation"><a href="index.jsp#sample" aria-controls="sample" role="tab" data-toggle="tab">Sample</a></li>
									<li role="presentation" class=""><a href="index.jsp#schema" aria-controls="schema" role="tab" data-toggle="tab">Schema</a></li>
								</ul>
								<!-- Tab panes -->
								<div class="tab-content">
									<div role="tabpanel" class="tab-pane active" id="form">
										<div id='form-container' style="padding-left: 6px; padding-right: 6px; padding-top: 6px; padding-bottom: 6px;"></div>
									</div>
									<div role="tabpanel" class="tab-pane" id="data"></div>
									<div role="tabpanel" class="tab-pane" id="sample"></div>
									<div role="tabpanel" class="tab-pane" id="schema"></div>
									<!--div role="tabpanel" class="tab-pane" id="resolver"></div-->
								</div>
								<div class="alert alert-danger in" role="alert" id="jsonAlert" style="display: none">
									<a href="index.jsp#" onclick='$("#jsonAlert").hide();' class="close">&times;</a> <strong>Error!</strong> <span id="error-message"></span>
								</div>

							</div>
							<!-- panel-body -->

						</div>
					</div>


					<div class="panel panel-primary">

						<div id="collapseForm" class="panel-collapse collapse" role="tabpanel">

							<div class="alert alert-info" role="alert">
								<strong id="example-title"></strong>
								<div id="example-desc"></div>
							</div>

							<div id='container' style="padding-left: 12px; padding-right: 12px; padding-bottom: 12px;"></div>

						</div>

						<div class="panel-footer">
							<button class="btn btn-primary" onclick="saveData( app, configType, JSON.stringify(bf.getData(), null, 4))">Save</button>
							&nbsp;
							<button class="btn btn-primary" onclick="alert(JSON.stringify(bf.getData(), null, 4))">Display</button>
							&nbsp;
							<button class="btn btn-primary"
								onclick="if (bf.validate()) {
                                        alert('Validation succeeded')
                                    }">Validate</button>
						</div>

					</div>

				</form>

			</div>
		</div>


		<!-- 
		<div class="content-wrapper container">
			<div class="content">
				<iframe src="layout_boxed_content.html" width="100%" height="100%" name="content_iframe"></iframe>
			</div>
		</div>
 -->

		<!-- /main content -->

	</div>



	<!-- /page content -->


	<!-- Footer -->
	<div class="navbar navbar-expand-lg navbar-light border-bottom-0 border-top">
		<div class="text-center d-lg-none w-100">
			<button type="button" class="navbar-toggler dropdown-toggle" data-toggle="collapse" data-target="#navbar-third">
				<i class="icon-menu mr-2"></i> Bottom navbar
			</button>
		</div>

		<div class="navbar-collapse collapse" id="navbar-third">
			<span class="navbar-text"> &copy; 2015 - 2025. <a href="https://vorpal.net">Vorpal Networks</a>
			</span>

			<ul class="navbar-nav ml-lg-auto">
				<li class="nav-item"><a href="#" class="navbar-nav-link">Help center</a></li>
				<li class="nav-item"><a href="#" class="navbar-nav-link">Policy</a></li>
				<li class="nav-item"><a href="#" class="navbar-nav-link font-weight-semibold">Upgrade your account</a></li>
				<li class="nav-item dropup"><a href="#" class="navbar-nav-link" data-toggle="dropdown"> <i class="icon-share4 d-none d-lg-inline-block"></i> <span
						class="d-lg-none">Share</span>
				</a>

					<div class="dropdown-menu dropdown-menu-right">
						<a href="#" class="dropdown-item"><i class="icon-dribbble3"></i> Dribbble</a> <a href="#" class="dropdown-item"><i class="icon-pinterest2"></i>
							Pinterest</a> <a href="#" class="dropdown-item"><i class="icon-github"></i> Github</a> <a href="#" class="dropdown-item"><i class="icon-stackoverflow"></i>
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


	<script lang="javascript">
	
// jwm - what does route do?		
            route(); 

            codeMirrors["data"] = CodeMirror(document.getElementById("data"), {
                value: JSON.stringify(jsonData, null, 4),
                mode: "javascript",
                lineNumbers: true
            });

            codeMirrors["sample"] = CodeMirror(document.getElementById("sample"), {
                value: JSON.stringify(jsonSample, null, 4),
                mode: "javascript",
                readOnly: true,
                lineNumbers: true
            });

            codeMirrors["schema"] = CodeMirror(document.getElementById("schema"), {
                value: JSON.stringify(schema, null, 4),
                mode: "javascript",
                readOnly: true,
                lineNumbers: true
            });

            var prevTab;
            var selectedTab;
            var currentData;
            
            $('a[data-toggle="tab"]').on('shown.bs.tab', function (e) {
                prevTab = (selectedTab) ? selectedTab : "form";
                selectedTab = $(e.target).attr("aria-controls");
	            console.log("jquery selectedTab="+selectedTab+", prevTab="+prevTab);

				
	            
				if(prevTab=="form"){
					inputString.data = JSON.stringify(bf.getData(), null, 4);
					console.log("setting inputString.data from bf.getData()");
				}else if(prevTab=="data"){
					inputString.data = codeMirrors["data"].getValue()
					console.log("setting inputString.data from codeMirrors[\"data\"].getValue()");
				}

	            if(selectedTab=="form"){
	            	// remove old form
                	while (container.firstChild) {
                    	container.removeChild(container.firstChild);
                	}
					// render new form	                
                    bf.render(container, JSON.parse(inputString.data));					
				}else if(selectedTab=="data"){
					codeMirrors[selectedTab].setValue(inputString.data);
				}else if(selectedTab=="schema" ){
					codeMirrors[selectedTab].setValue(JSON.stringify(jsonSchema, null, 4));
				}else if(selectedTab=="sample" ){
					codeMirrors[selectedTab].setValue(JSON.stringify(jsonSample, null, 4));
				}
                
            }
            );

			
        </script>

</body>
</html>

</html>
