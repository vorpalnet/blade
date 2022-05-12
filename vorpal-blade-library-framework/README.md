# Vorpal:BLADE Framework


## Installation

To use the Vorpal:BLADE Framework, deploy the shared libraries "vorpal-blade-shared-libraries-2.x.x.war" to the cluster.

# Download

Use git clone to download BLADE.






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