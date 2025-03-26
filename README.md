# Vorpal:BLADE

**B**lended **L**ayer **A**pplication **D**evelopment **E**nvironment

tl;dr...
1) install Java 11 (from Oracle)
2) download & install OCCAS
2.1) install weblogic patches
2.2) install occas patches
3) type 'ant'. it will fail, but give you instructions on how to fix the configuration file
4) install the shared libraries on OCCAS (it's required for all apps)
5) install the blade console on OCCAS (it will update apps with configuration changes)
6) install (and configure) the fsmar, it will allow you to string apps together during a callflow
7) install the apps you want. there are many to choose from. transfer and proxy-router are good starting points
8) nothing will work, everything will fail. check the logs in <domain>/servers/engine(?)/logs/vorpal/<app>.log

If this sounds insane, yes, it is!
You can find more instructions at https://vorpal.net

BLADE is a development framework and collection of pre-built
applications build on the Java EE JSR-359 (SIP Servlet) specification.

What makes BLADE so great?

It comes with a framework library that features:
* Support for lambda expressions to simplify callflow design and readability
* Prebuilt callflows for common use cases, B2BUA & Proxy
* Application templates
* Support for dynamic configuration files
* Improved logging capabilities

It also comes with a carrier-grade Finite State Machine Application Router, the FSMAR.

It also features prebuilt applications to handle your most common needs:

| Project | Description |
| ----------- | ----------- |
| Blade Console | Configure application through and Admin Console |
| Blade Framework | A collection of Java libraries that simplify the creation of SIP Servlets beyond what's provided in JSR-359. |
| FSMAR | Finite State Machine Application Router; Chain apps together to build sophisticated services |
| Proxy-Balancer | A simple load balancer |
| Proxy-Registrar | A small, elegant SIP proxy-registrar |
| R3 Router | The Reductive Reasoning Router; Supports various search algorithms for optimal performance |
| ACL | Access Control List; Allow or deny calls through the system |
| CRUD | Create, Read, Update & Delete; Transform any SIP message |
| Keep-Alive | Prevent sessions from timing out |
| Options | Control the behavior of options |
| Presence | Maintains state of user endpoints |
| Queue | Maintains state of user endpoints |
| Transfer | Implements REFER for transfer applications |
| Test B2BUA | An example B2BUA application |
| Test UAC | A REST operated User Agent Client |
| Test UAS | A test User Agent Server; Controllable through SIP Request-URI parameters |



# Compiling

## ANT

To build / compile Vorpal:BLADE, simply type 'ant'.

On your first attempt, the build will fail. However it will generate a properties file named: HOSTNAME.properties (where HOSTNAME is the hostname of your computer).

Edit that file, not the original "build.properties" file.

## Eclipse Support

Vorpal:BLADE can also be imported into Eclipse. To get a clean environment, you need to perform a few steps:

1. Clone Vorpal:BLADE from Git: 
1. Import a Git project.


