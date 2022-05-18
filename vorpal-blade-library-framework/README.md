# Vorpal:BLADE Framework

See Javadocs: [org.vorpal.blade.framework](https://vorpalnet.github.io/blade/vorpal-blade-library-framework/index.html)

Welcome to the Vorpal:BLADE framework library.

Please note: Although The Vorpal:BLADE Framework attempts to support SIP Servlets JSR-359 APIs, it has only been tested
on the Oracle Communications Converged Application Server (OCCAS). You will find a lot of Oracle specific jargon in this
documentation. Be warned!

The purpose of this library is to simplify SIP Servlet application development
by adding additional features. They include:

<table>
  <tr><th>Package</th><th>Description</th></tr>
  
  <tr>
  <td>[org.vorpal.blade.framework](./tree/main/vorpal-blade-library-framework/src/org/vorpal/blade/framework)</td>
  <td>Common base class functionality found in AsyncSipServlet.</td>
  </tr>
  
  <tr>
  <td>[org.vorpal.blade.framework.b2bua](tree/main/vorpal-blade-library-framework/src/org/vorpal/blade/framework/b2bua)</td>
  <td>Create simple B2BUA applications with minimal effort.</td>
  </tr>
  
  <tr>
  <td>[org.vorpal.blade.framework.callflow](tree/main/vorpal-blade-library-framework/src/org/vorpal/blade/framework/callflow)</td>
  <td>Utilize Java Lambda Expressions to develop complicated callflows that are simple and easy to read.</td>
  </tr>

  <tr>
  <td>[org.vorpal.blade.framework.config](tree/main/vorpal-blade-library-framework/src/org/vorpal/blade/framework/config)</td>
  <td>Use the SettingsManager Generics class to dynamically read and write configuration files.</td>
  </tr>

  <tr>
  <td>[org.vorpal.blade.framework.logging](tree/main/vorpal-blade-library-framework/src/org/vorpal/blade/framework/logging)</td>
  <td>Creates a custom "java.util.logging" logger with specific features for tracing SIP callflows.</td>
  </tr>

  <tr>
  <td>[org.vorpal.blade.framework.proxy](tree/main/vorpal-blade-library-framework/src/org/vorpal/blade/framework/proxy)</td>
  <td>A new proxy framework for developing least-cost or minimum-delay routing applications.</td>
  </tr>
  
</table>


# Download

Use git clone to download BLADE.

## Installation

To use the Vorpal:BLADE Framework, deploy the shared libraries "vorpal-blade-shared-libraries-2.x.x.war" to the cluster.







## Development

To use the framework in your SIP Servlet application, add the following text to your "weblogic.xml" file.

```
	<wls:library-ref>
		<wls:library-name>vorpal-blade</wls:library-name>
		<wls:specification-version>2.0</wls:specification-version>
		<wls:exact-match>false</wls:exact-match>
	</wls:library-ref>
```

## Eclipse

To compile against the framework in Eclipse, import the "vorpal-blade-library-framework" 
and "vorpal-blade-shared-libraries" projects from your cloned GIT repository.