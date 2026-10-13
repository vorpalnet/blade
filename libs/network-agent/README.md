# BLADE network agent

A Java agent that shows the dashboard the network calls an application's own code makes, whichever
library it makes them with: the REST client a developer picked, an object-storage SDK, a WebSocket.
BLADE's own connectors already report their calls; this covers everything else.

It adds a report to the JDK's network methods: every TCP connect (`java.net.Socket`, NIO
`SocketChannel`, NIO.2 `AsynchronousSocketChannel`), which catches "connection refused" and "timed
out" from any library, and every exchange through the JDK's two HTTP clients (`HttpURLConnection`,
`java.net.http.HttpClient`), which gives the status. Each call is credited to the application whose
code made it; the container's own connections (the SIP transport, JMS, JDBC pools) are left out.
Vorpal code that no application owns, such as the media driver's control channel to the media
server, is credited to `platform`, once per server. The results join the framework's `Remotes` MBean, so they
appear on the dashboard's network map and under Operations, "Wrong now".

## Running it

Put the JAR in the domain on the admin host, where `install.sh` copies it to every engine host it
provisions:

```
./deploy.sh <env> blade-agent.jar --agent        # to <domain>/agent/blade-agent.jar
```

Then add it to the managed servers' start arguments (the engine template, and engine0 if it has its
own) and restart them:

```
-javaagent:/opt/oracle/domains/<domain>/agent/blade-agent.jar
```

A server with the agent logs `BLADE agent: recording the connections and HTTP calls applications
make` at start. Without the flag nothing changes: applications run as before and report only what
the framework records. The JAR must exist on the host before the flag is added, or the JVM will not
start: an engine host provisioned before the JAR was placed needs re-provisioning first.
