# Vorpal:BLADE Proxy-Registrar


```
{
  "logging" : {
    "useParentLogging" : false,
    "directory" : "./servers/${weblogic.Name}/logs/vorpal",
    "fileSize" : "100MiB",
    "fileCount" : 25,
    "appendFile" : true,
    "loggingLevel" : "FINER",
    "sequenceDiagramLoggingLevel" : "FINE",
    "configurationLoggingLevel" : "FINE",
    "fileName" : "${sip.application.name}.%g.log"
  },
  "defaultAllow" : [ "OPTIONS", "SUBSCRIBE", "NOTIFY", "INVITE", "ACK", "CANCEL", "BYE", "REFER", "INFO" ],
  "proxyOnUnregistered" : true,
  "addToPath" : false,
  "noCancel" : false,
  "parallel" : true,
  "recordRoute" : false,
  "recurse" : false,
  "supervised" : true
}


  "recordRoute" : false,
  "supervised" : false
Allows you to see INVITE only

  "recordRoute" : false,
  "supervised" : true
Allows you to see INVITE, 180, & 200

  "recordRoute" : true,
  "supervised" : false
 Allows you to see INVITE, ACK, & BYE


  "recordRoute" : true,
  "supervised" : true
Allows you to see 180, 200, ACK & BYE

```

