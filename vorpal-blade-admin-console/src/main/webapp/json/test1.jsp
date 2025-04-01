<%@ page import="org.vorpal.blade.applications.console.config.test.*"%>
<%@ page import="java.util.*"%>
<%@ page import="java.nio.file.*"%>
<%

System.out.println("Test #2");

String exception = "No Exceptions.";
String app = request.getParameter("app");
String configType = request.getParameter("configType");
System.out.println("app=" + app +", configType="+ configType);

String jsonData = request.getParameter("jsonData");

ConfigHelper cfgHelper = new ConfigHelper(app, configType);
cfgHelper.getSettings();

String jsonSchema = cfgHelper.loadFile("SCHEMA");
String jsonSample = cfgHelper.loadFile("SAMPLE");

if (jsonData != null && jsonData.length() > 0) {
	System.out.println("Saving submitted form data locally..." + jsonData.length());
	System.out.println(jsonData);
	cfgHelper.saveFileLocally(configType, jsonData);
} else {

	System.out.println("Loading json data for type " + configType);
	jsonData = cfgHelper.loadFile(configType);

	if (jsonData == null || jsonData.length() == 0) {
		System.out.println("No json data found...");
		jsonData = "{}";
	} else {
		System.out.println("Loading json data for type " + configType);
		System.out.println("bytes: " + jsonData.length());
	}
}

cfgHelper.closeSettings();

Set<String> dirContents = cfgHelper.listFilesUsingFilesList("config/custom/vorpal/");
%>
<!DOCTYPE html>
<html>
<head>
<meta name=viewport content='width=560'>

<link rel="stylesheet" href='lib/bootstrap-3.4.1-dist/css/bootstrap.min.css' />
<link rel="stylesheet" href="lib/codemirror/codemirror.css">
<link rel="stylesheet" href="lib/bootstrap-select-v1.13.14/css/bootstrap-select.min.css">
<link rel="stylesheet" href="lib/octicons/octicons.css">
<link rel="stylesheet" href='lib/brutusin-json-forms.min.css' />

<style>
.CodeMirror {
	height: 400px;
}
</style>

<script src='lib/jquery-1.11.3.min.js'></script>
<script src='lib/bootstrap-3.4.1-dist/js/bootstrap.min.js'></script>
<script src="lib/codemirror/codemirror.js"></script>
<script src="lib/codemirror/codemirror-javascript.js"></script>
<script src="lib/markdown.min.js"></script>
<script src="lib/bootstrap-select-v1.13.14/js/bootstrap-select.min.js"></script>
<script src="lib/bootstrap-select-v1.13.14/js/i18n/defaults-en_US.min.js"></script>

<!-- script src="./lib/brutusin-json-forms.min.js"></script -->
<script src="./lib/brutusin-json-forms.js"></script>
<script src="./lib/brutusin-json-forms-bootstrap.min.js"></script>



<script lang="javascript">

function saveData(app, configType, data){
	try {
      // put the JSON 'data' in a hidden form field called 'jsonData'.
 	  $("#jsonData").val(data);
 	  
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

        var schema = <%=jsonSchema%>;
		var jsonData = <%=jsonData%>;
		var jsonSample = <%=jsonSample%>;

        
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
                    jsonData,
                    jsonSample,
                    "Domain-wide configuration (typical)"],
                ["Cluster",
                    schema,
                    jsonData,
                    jsonSample,
                    "Cluster-specific configuration"],
                ["Server",
                    schema,
                    jsonData,
                    jsonSample,
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
                input.sample = demos[selectedExampleIndex][3];
                inputString.schema = JSON.stringify(input.schema, null, 2);
                inputString.data = input.data ? JSON.stringify(input.data, null, 2) : "";
                inputString.sample = input.sample ? JSON.stringify(input.sample, null, 2) : "";
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
                var sample;
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
			<img alt="vorpal-logo" src="./vorpal-logo-small.png" style="padding: 5px 5px 5px 5px; border: 0; height: 80px;" />
			<code>BLADE</code>
			Configurator
		</h1>

		<blockquote>
			<p>
				<b>JSON Schema to HTML form generator</b>, supporting dynamic subschemas (on the fly resolution). Extensible and customizable library with zero
				dependencies.
			</p>
			<p>
				Source code and documentation available at <a href="https://github.com/vorpalnet/blade"><span class="octicon octicon-logo-github"></span></a>
			</p>
		</blockquote>
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
                                        document.write("<option " + (selectedDemo === i ? "selected=true" : "") + ">" + demos[i][0] + "</option>");
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
								<div role="tabpanel" class="tab-pane" id="form"></div>
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


	<script lang="javascript">
            route();
            codeMirrors["schema"] = CodeMirror(document.getElementById("schema"), {
                value: JSON.stringify(demos[selectedDemo][1], null, 4),
                mode: "javascript",
                lineNumbers: true
            });

            codeMirrors["data"] = CodeMirror(document.getElementById("data"), {
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

                // jwm
                console.log("selectedTab: "+selectedTab);


                
            }
            );

        </script>
</body>
</html>
