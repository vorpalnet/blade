# Vorpal:BLADE

BLADE stands for Blended Layer Application Development Environment.

See the [Documentation](https://vorpalnet.github.io) website.

It's purpose is to simplify the development of audio/video streaming applications. It
attempts to follow SIP Servlet specifications, but currently only supports
Oracle Communications Converged Application Server (OCCAS).

What makes BLADE so great?

It comes with a framework library that features:
* Support for lambda expressions to simplify callflow design and readability
* Prebuilt callflows for common use cases, B2BUA & Proxy
* Application templates
* Support for dynamic configuration files
* Improved logging capabilities

It also comes with a carrier-grade Finite State Machine Application Router, the FSMAR.

It also features prebuilt applications to handle your most common needs:
* Access Control Lists
* Keep-Alive
* Load-Balancer
* Options
* Proxy-Registrar
* A Reductive Reasoning Router, R3
* Call Transfer
* Testing Tools
* Admin Console

#Compiling

##ANT

To build / compile Vorpal:BLADE, simply type 'ant'.

On your first attempt, the build will fail. However it will generate a properties file named: <hostname>.properties

Edit that file, not the original "build.properties" file.

##Eclipse Support

Vorpal:BLADE can also be imported into Eclipse. To get a clean environment, you need to perform a few steps:

1. Clone Vorpal:BLADE from Git: 
1. Import a Git project.


