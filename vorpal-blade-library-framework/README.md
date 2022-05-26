# Vorpal:BLADE Framework

[Javadocs](https://vorpalnet.github.io/blade/vorpal-blade-library-framework/index.html)

Welcome to the Vorpal:BLADE framework library.

Please note: Although The Vorpal:BLADE Framework attempts to support SIP Servlets JSR-359 APIs, it has only been tested
on the Oracle Communications Converged Application Server (OCCAS). You will find a lot of Oracle specific jargon in this
documentation. Be warned!

The purpose of this library is to simplify SIP Servlet application development
by adding additional features. They include:


1. [org.vorpal.blade.framework](https://github.com/vorpalnet/blade/tree/main/vorpal-blade-library-framework/src/org/vorpal/blade/framework)
1. [org.vorpal.blade.framework.b2bua](https://github.com/vorpalnet/blade/tree/main/vorpal-blade-library-framework/src/org/vorpal/blade/framework/b2bua)
1. [org.vorpal.blade.framework.callflow](https://github.com/vorpalnet/blade/tree/main/vorpal-blade-library-framework/src/org/vorpal/blade/framework/callflow/)
1. [org.vorpal.blade.framework.config](https://github.com/vorpalnet/blade/tree/main/vorpal-blade-library-framework/src/org/vorpal/blade/framework/config/)
1. [org.vorpal.blade.framework.logging](https://github.com/vorpalnet/blade/tree/main/vorpal-blade-library-framework/src/org/vorpal/blade/framework/logging/)
1. [org.vorpal.blade.framework.proxy](https://github.com/vorpalnet/blade/tree/main/vorpal-blade-library-framework/src/org/vorpal/blade/framework/proxy/)


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

