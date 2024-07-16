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


