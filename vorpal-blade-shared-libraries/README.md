# Vorpal:BLADE

WebLogic Shared Libraries

This project builds a WebLogic shared library, which contains all the JAR files needed
for the "vorpal-blade-library-framework" project.

To download the latest libraries from Maven Central, type:

```
mvn package
```

To build the WebLogic shared libary, type:

```
ant
```

To use this shared library, deploy it to the WebLogic cluster and include the following
lines in the "weblogic.xml" file of each application that uses the Vorpal:BLADE framework:

```
	<wls:library-ref>
		<wls:library-name>vorpal-blade</wls:library-name>
		<wls:specification-version>2.0</wls:specification-version>
		<wls:exact-match>false</wls:exact-match>
	</wls:library-ref>
```






