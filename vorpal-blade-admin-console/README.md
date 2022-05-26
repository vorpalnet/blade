# Vorpal:BLADE Admin-Console

[Javadocs](https://vorpalnet.github.io/vorpal-blade-admin-console/index.html)

The BLADE Admin Console is a work in progress...

Please deploy the Admin Console (ServletContext name "blade") to the Admin server only. It is not designed to run in a cluster.

The Admin Console is required for the BLADE Framework Config functionality to detect changes to configuration
files and push that information to applications in the cluster.

Additional planned features include:
* generic GUI for editing JSON configuration files
* versioning / history for rolling back configuration files
* drag 'n drop GUI for managing the FSMAR
* advanced logging capabilities
* CDR generation utilities for advanced billing
* Database support

Consider the Admin Console to be beta software until the release of BLADE 3.0.

# Login

Use the same username / password as WebLogic.
