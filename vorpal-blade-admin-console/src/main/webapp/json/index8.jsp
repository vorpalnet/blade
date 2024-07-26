<%@ page import="org.vorpal.blade.applications.console.config.test.*"%>
<%@ page import="java.util.*"%>
<%
String exception = "No Exceptions.";
String app = request.getParameter("app");
ConfigHelper cfgHelper = new ConfigHelper(app);
String domainJson = cfgHelper.loadDomainJson();
String clusterJson = cfgHelper.loadClusterJson();
String serverJson = cfgHelper.loadServerJson();

String jsonSchema = cfgHelper.loadJsonSchema();
Set<String> dirContents = cfgHelper.listFilesUsingFilesList("config/custom/vorpal/");
%>
<!DOCTYPE html>
<html>
<head>
<meta name=viewport content='width=560'>

<link rel="stylesheet" href='lib/bootstrap/css/bootstrap.min.css' />
<link rel="stylesheet" href="lib/codemirror/codemirror.css">
<link rel="stylesheet"
	href="lib/bootstrap-select-v1.13.14/css/bootstrap-select.min.css">
<link rel="stylesheet" href="lib/octicons/octicons.css">
<link rel="stylesheet"
	href='http://rawgit.com/brutusin/json-forms/master/dist/css/brutusin-json-forms.min.css' />

<style>
.CodeMirror {
	height: 400px;
}
</style>

<script src='lib/jquery-1.11.3.min.js'></script>
<script src='lib/bootstrap/js/bootstrap.min.js'></script>
<script src="lib/codemirror/codemirror.js"></script>
<script src="lib/codemirror/codemirror-javascript.js"></script>
<script src="lib/markdown.min.js"></script>
<script src="lib/bootstrap-select-v1.13.14/js/bootstrap-select.min.js"></script>
<script
	src="lib/bootstrap-select-v1.13.14/js/i18n/defaults-en_US.min.js"></script>
<!--
        <script src="//rawgit.com/brutusin/json-forms/master/dist/js/brutusin-json-forms.min.js"></script>
         -->
<script
	src="http://rawgit.com/brutusin/json-forms/master/src/js/brutusin-json-forms.js"></script>
<script
	src="http://rawgit.com/brutusin/json-forms/master/dist/js/brutusin-json-forms-bootstrap.min.js"></script>




<script lang="javascript">

/*
async function saveData(app, configType, data){

	
	// Construct a FormData instance
	  const formData = new FormData();

	  // Add a text field
	  formData.append("app", app);
	  formData.append("configType", configType);
	  formData.append("data", data);

	  try {

	  } catch (e) {
	    console.error(e);
	  }
	
}*/


</script>


<script lang="javascript">

		const currentUrl = window.location.href;



		
		const queryString = window.location.search;
		const urlParams = new URLSearchParams(queryString);
		const app = urlParams.get('app');
		
        var schema = <%=jsonSchema%>;
        var domainJson = <%=domainJson%>;
        var clusterJson = <%=clusterJson%>;
        var serverJson = <%=serverJson%>;
		var configType = "domain";


        
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
                    schema,
                    domainJson,
                    null,
                    "Domain-wide configuration (typical)"],
                ["Cluster",
                    schema,
                    clusterJson,
                    null,
                    "Cluster-specific configuration"],
                ["Server",
                    schema,
                    serverJson,
                    null,
                    "Server-specific configuration"]                        
            ];
            
            var selectedTab = "schema";
            var bf;

            function selectExample(selectedExampleIndex) {

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
                eval("input.resolver=" + demos[selectedExampleIndex][3]);
                inputString.schema = JSON.stringify(input.schema, null, 2);
                inputString.data = input.data ? JSON.stringify(input.data, null, 2) : "";
                inputString.resolver = demos[selectedExampleIndex][3] ? demos[selectedExampleIndex][3] : "";
                if (codeMirrors[selectedTab]) {
                    codeMirrors[selectedTab].setValue(inputString[selectedTab]);
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
                var resolver;
                var tabId;
                inputString[selectedTab] = codeMirrors[selectedTab].getValue();
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
                    if (inputString.resolver) {
                        tabId = "resolver";
                        message = "The was a syntax error in the resolver code";
                        eval("resolver=" + inputString.resolver);
                        if ("function" !== typeof resolver) {
                            throw "Schema resolver does not evaluate to a function";
                        }
                    }
                } catch (err) {
                    document.getElementById('error-message').innerHTML = message + (err ? ". " + err : "");
                    $('[href=#' + tabId + ']').tab('show');
                    $("#jsonAlert").show();
                    return;
                }
                

                // $('#formLink').click();


                bf = BrutusinForms.create(schema);
                if (resolver) {
                    bf.schemaResolver = resolver;
                }
                var container = document.getElementById('form-container');
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
	<div class="container">
		<h1>
			<img alt="vorpal-logo" src="./vorpal-logo-small.png"
				style="padding: 5px 5px 5px 5px; border: 0; height: 80px;" />
			<code>BLADE</code>
			Configurator
		</h1>

		<blockquote>
			<p>
				<b>JSON Schema to HTML form generator</b>, supporting dynamic
				subschemas (on the fly resolution). Extensible and customizable
				library with zero dependencies.
			</p>
			<p>
				Source code and documentation available at <a
					href="https://github.com/vorpalnet/blade"><span
					class="octicon octicon-logo-github"></span></a>
			</p>
		</blockquote>
		<div class="panel-group" id="accordion" role="tablist"
			aria-multiselectable="true">
			<div class="panel panel-primary">
				<div class="panel-heading" role="tab" id="headingOne">
					<h4 class="panel-title">
						<a role="button" data-toggle="collapse" data-parent="#accordion"
							href="index.html#collapseInput" aria-expanded="true"
							aria-controls="collapseInput"> <%=app%>
						</a>
					</h4>
				</div>
				<div id="collapseInput" class="panel-collapse collapse in"
					role="tabpanel" aria-labelledby="headingOne">


					<div class="panel-body">
						<label for="examples">Configuration type:</label> <select
							class="form-control" id="examples"
							onchange="document.location.hash = this.selectedIndex;">
							<script>
                                    for (var i = 0; i < demos.length; i++) {
                                        document.write("<option " + (selectedDemo === i ? "selected=true" : "") + ">" + demos[i][0] + "</option>");
                                    }
                                </script>
						</select> <br>
						<ul class="nav nav-tabs" role="tablist">

							<li role="presentation" class="active"><a
								href="index.html#form" aria-controls="schema" role="tab"
								data-toggle="tab">Form</a></li>

							<li role="presentation"><a href="index.html#data"
								aria-controls="data" role="tab" data-toggle="tab">JSON</a></li>

							<li role="presentation" class=""><a href="index.html#schema"
								aria-controls="schema" role="tab" data-toggle="tab">Schema</a></li>

							<li role="presentation"><a href="index.html#resolver"
								aria-controls="resolver" role="tab" data-toggle="tab">Schema
									resolver</a></li>
						</ul>
						<!-- Tab panes -->
						<div class="tab-content">
							<div role="tabpanel" class="tab-pane active" id="form">
							
							<div id='form-container'
						style="padding-left: 12px; padding-right: 12px; padding-bottom: 12px;"></div>
							
							</div>
							<div role="tabpanel" class="tab-pane" id="data"></div>
							<div role="tabpanel" class="tab-pane" id="schema"></div>
							<div role="tabpanel" class="tab-pane" id="resolver"></div>
						</div>
						<div class="alert alert-danger in" role="alert" id="jsonAlert"
							style="display: none">
							<a href="index.html#" onclick='$("#jsonAlert").hide();'
								class="close">&times;</a> <strong>Error!</strong> <span
								id="error-message"></span>
						</div>

					</div>
					<!-- panel-body -->


					<div class="panel-footer">
					
					<!-- 
						<button class="btn btn-primary" onclick="generateForm()">Create
							form</button>
							
					-->

						<button class="btn btn-primary"
							onclick="saveData( app, configType, JSON.stringify(bf.getData(), null, 4))">Save</button>
						&nbsp;

						<button class="btn btn-primary"
							onclick="alert(JSON.stringify(bf.getData(), null, 4))">Display</button>
						&nbsp;

						<button class="btn btn-primary"
							onclick="if (bf.validate()) {
                                        alert('Validation succeeded')
                                    }">Validate</button>


					</div>



				</div>
			</div>
			
			
			<div class="panel panel-primary">
				<div class="panel-heading" role="tab" id="headingTwo">
					<h4 class="panel-title">
						<a class="collapsed" id="formLink" role="button"
							data-toggle="collapse" data-parent="#accordion"
							href="index.html#collapseForm" aria-expanded="false"
							aria-controls="collapseForm"> Generated form </a>
					</h4>
				</div>
				<div id="collapseForm" class="panel-collapse collapse"
					role="tabpanel" aria-labelledby="headingTwo">
					<div class="alert alert-info" role="alert">
						<strong id="example-title"></strong>
						<div id="example-desc"></div>
					</div>
					
					
					<div id='container'
						style="padding-left: 12px; padding-right: 12px; padding-bottom: 12px;"></div>
						
						
					<div class="panel-footer">
						<button class="btn btn-primary"
							onclick="saveData( app, configType, JSON.stringify(bf.getData(), null, 4))">Save</button>
						&nbsp;

						<button class="btn btn-primary"
							onclick="alert(JSON.stringify(bf.getData(), null, 4))">Display</button>
						&nbsp;

						<button class="btn btn-primary"
							onclick="if (bf.validate()) {
                                        alert('Validation succeeded')
                                    }">Validate</button>
					</div>
				</div>
			</div>
			
			
		</div>
	</div>


	<script lang="javascript">
            route();
            codeMirrors["schema"] = CodeMirror(document.getElementById("schema"), {
                value: JSON.stringify(demos[selectedDemo][1], null, 4),
                mode: "javascript",
                lineNumbers: true
            });
            $('a[data-toggle="tab"]').on('shown.bs.tab', function (e) {
                selectedTab = $(e.target).attr("aria-controls");
                var pt = $(e.relatedTarget);
                var prevTab;
                if (pt) {
                    prevTab = pt.attr("aria-controls");
                    inputString[prevTab] = codeMirrors[prevTab].getValue();
                }
                if (!codeMirrors[selectedTab]) {
                    codeMirrors[selectedTab] = CodeMirror(document.getElementById(selectedTab), {
                        mode: "javascript",
                        lineNumbers: true
                    });
                }
                codeMirrors[selectedTab].setValue(inputString[selectedTab]);
            }
            );

        </script>
</body>
</html>
