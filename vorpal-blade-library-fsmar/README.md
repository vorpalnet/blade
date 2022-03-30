# Vorpal:BLADE FSMAR 2.0

Welcome to the one and only...  

__F__inite  
__S__tate  
__M__achine  
__A__pplication  
__R__outer  

## The What Now?

The FSMAR uses state memory and pattern matching to route traffic between applications in a _SIP Servlet Application Server_. Think of it as a router for audio/video streaming microservices. This allows developers to write tiny snippets of code (SIP Servlets) and string them together in an intelligent manner. The FSMAR knows who you are, where you've been and where you're going.

#What's new in 2.0?

Key features include:
* JSON formatted config file with schema
* Ability to update config files dynamically
* Improved pattern matching
* Supports seamless upgrades through versioned applications
* Dedicated log file(s)


#How does it work?

1. Drop 'fsmar.jar' in your domain's "approuter" directory.
1. Update the OCCAS admin console to use 'fsmar.jar'.
1. Restart one (or all) of the engine tier servers.
1. Check the ./config/custom/vorpal folder for FSMAR.SAMPLE
1. Rename FSMAR.SAMPLE to FSMAR.json end edit it to fit your needs.

Please note: OCCAS 7.1 or older version require the 'fsmar.jar' file to also be in the leading CLASSPATH in addition to being in the 'approuter' directory. (This is due to version mismatches with the required Jackson JSON libraries.)

#Tutorial

The FSMAR is a finite state machine. A finite state machine consists of a number of 'states' which represent individual SIP Servlet applications. As messages flow through the system, they take the _action_ of _transitioning_ between _states_ when _triggered_ by a matching _condition_.

Here's a helpful breakdown:
* __Previous State__ -- the origin of the message ('null' if it originated from an external system)
* __Trigger__ -- a SIP message like INVITE, REGISTER, etc
* __Condition__ -- a set of matching patterns, like a Java RegEx expression applied to a particular header
* __Action__ -- additional steps to make during the transition, like defining the region and subscriber URI
* __Next State__ -- the destination application of the message

Below are some examples on how to configure this.

# Conditional Pattern Matching

* address  -- regular expression match on a SIP address i.e.: "Bob" <sip:bob@vorpal.org>
* uri      -- regular expression 'match' on a SIP URI, i.e.: sip@bob.vorpal.org
* user     -- user part of a SIP URI, case sensitive, i.e.: bob
* host     -- host (domain) part of a SIP URI, case insensitive, i.e.: vorpal.org
* port     -- the port number of a SIP URI (matches 5060 if no port exists)
* equals   -- case sensitive comparison of the _full line_ of the first header found
* matches  -- regular expression match of the _full line_ of the first header found
* contains -- partial string match of the value of all headers by the specified name
* includes -- an exact string match for values found in a comma delimited header
* value    -- case sensitive match of any 'value' of a header (without parameters / tags)
* ???      -- anything else is treated as a parameter. i.e.: __Expires: 3600;refresher=uac__ ('refresher' in this case)

By default, if no condition is defined, it is considered a match. Multiple conditions are ANDed together. If you're looking for OR capabilities, consider creating two different transitions.

# Defining Actions

The FSMAR can only take just a few actions once a condition is matched. They include setting the region and subscriber URI as well as defining any routes to be pushed.


# Examples

Condider the following SIP packet:

```
INVITE sip:bob@vorpal.org SIP/2.0
Content-Type: application/sdp
To: "Bob" <sip:bob@vorpal.org>;loc=wonderland
Via: SIP/2.0/TCP 192.168.1.206:5060;wlsscid=1a25;branch=z9b;wlsssid=12dnl1j
Min-SE: 90
Allow: INFO, CANCEL, ACK, BYE, UPDATE
Allow: PRACK, INVITE
Call-ID: wlss-19b2247c-c038baf4182b6d4aaa762b897434deb7@192.168.1.206
From: "Alice" <sip:alice@vorpal.org>loc=wonderland;tag=f234ee12
Max-Forwards: 70
Contact: <sip:buyer@192.168.1.206:5060;transport=tcp;wlsscid=1a25;ob;sipappsessionid=6ccj>
X-Version-Number: 2.1.3
Session-Expires: 3600;refresher=uac
CSeq: 1 INVITE
Content-Length: 4060
Route: <sip:192.168.1.202:5060;lr;transport=tcp>
Supported: 100rel, timer

...
```


Here's a snippet of the sample config file "fsmar2.SAMPLE" that gets created when you start the application server:

```json
{
  "previous" : {
    "keep-alive" : {
      "triggers" : {
        "INVITE" : {
          "transitions" : [ {
            "id" : "INV-2",
            "next" : "b2bua",
            "condition" : {
              "Session-Expires" : [ { "value" : "3600"}, { "refresher" : "uac"} ],
              "Region" : [ { "equals" : "ORIGINATING" } ],
              "Request-URI" : [ { "uri" : "^(sips?):([^@]+)(?:@(.+))?$" } ],
              "From" : [ { "address" : "^.*<(sips?):([^@]+)(?:@(.+))?>.*$" } ],
              "To" : [ { "user" : "bob" }, { "host" : "vorpal.net" }, { "equals" : "<sip:bob@vorpal.net>" } ],
              "Directive" : [ { "equals" : "CONTINUE" } ],
              "Region-Label" : [ { "equals" : "ORIGINATING" } ],
              "Allow" : [ { "contains" : "INV" }, { "includes" : "INVITE" } ] },
            "action" : {
              "originating" : "From",
              "route" : [ "sip:proxy1", "sip:proxy2" ] }
          }, {
            "id" : "INV-3",
            "next" : "b2bua"
          } ]
        }
      }
    },
  }
}
```

What does it mean? Let's take it line-by-line...

```
{
  "previous" : {
    "keep-alive" : {
      "triggers" : {
        "INVITE" : {
          "transitions" : [ {
```

The config file consists of a map of 'previous' states with 'triggers' (SIP methods) that may have a list of 'transitions'. In this case we are looking at INVITE messages being sent from an application called "keep-alive". So far, so good?

```
          "transitions" : [ {
            "id" : "INV-2",
            "next" : "b2bua",
            "condition" : {
```

Now, we have a 'transition' called "INV-2" that will move the state from "keep-alive" to an application called "b2bua". The 'id' of the transition is optional and only serves to help with logging. Now we're getting to the juicy part. Consider this complicated 'condition':

```
            "condition" : {
              "Session-Expires" : [ { "value" : "3600"}, { "refresher" : "uac"} ],
              "Region" : [ { "equals" : "ORIGINATING" } ],
              "Request-URI" : [ { "uri" : "^(sips?):([^@]+)(?:@(.+))?$" } ],
              "From" : [ { "address" : "^.*<(sips?):([^@]+)(?:@(.+))?>.*$" } ],
              "To" : [ { "user" : "bob" }, { "host" : "vorpal.net" }, { "equals" : "<sip:bob@vorpal.net>" } ],
              "Directive" : [ { "equals" : "CONTINUE" } ],
              "Region-Label" : [ { "equals" : "ORIGINATING" } ],
              "Allow" : [ { "contains" : "INV" }, { "includes" : "INVITE" } ] },
```

A 'condition' is made up of a list of comparisons. If they're all true, the condition matches and the transition will take place, meaning the FSMAR will send the INVITE from the "keep-alive" application to the "b2bua" application.

Let's take more careful look at each one.

## Parameterable Headers

```
              "Session-Expires" : [ { "value" : "3600"}, { "refresher" : "uac"} ],
```

In this case, we have a "comparison list" with two comparisons to be made on the "Session-Expires" header. From the sample INVITE packet, the header looks like: __Session-Expires: 3600;refresher=uac__

This is considered a 'parameterable' header. The 'value' of the header is '3600' and the parameter 'refresher' is 'uac'. Why would anyone bother to match on this? There's no good reason except to demonstrate how pattern matching on parameterable headers works.

## Region

```
              "Region" : [ { "equals" : "ORIGINATING" } ],
```

In this example, Region is not a real header name, but it refers to the value in defined by: SipServletRequest.getRegion().getType().toString();

In this example, if the value 'equals' the string 'ORIGINATING', the pattern matches.

### Request-URI

```
              "Request-URI" : [ { "uri" : "^(sips?):([^@]+)(?:@(.+))?$" } ],
```


Once again, "Request-URI" is not a real header name, but it refers to the value defined by: SipServletRequest.getRequestURI();

The 'uri' operator employs a Java Regular Expression 'match'. For the "Request-URI", you are not limited to the 'uri' operator. You can use any operator you like.

## From

```
              "From" : [ { "address" : "^.*<(sips?):([^@]+)(?:@(.+))?>.*$" } ],
```

Here is a slight variation on the 'uri' operator, called 'address'. It is defined by: SipServletRequest.getAddressHeader("From");

There's a subtle difference between 'uri' and 'address'.

Consider the example SIP address: __From: "Alice" <sip:alice@vorpal.org>loc=wonderland;tag=f234ee12__

If you were to use the 'uri' operator, the value would be: __alice@vorpal.org__

So, address gives you the ability to apply a Java Regular Expression to the whole string.

## To

```
              "To" : [ { "user" : "bob" }, { "host" : "vorpal.net" }, { "equals" : "<sip:bob@vorpal.net>" } ],
```

Here's another example of matching against and address header. In this case, the 'user' is bob, the 'host' is "vorpal.net" and just for giggles, the entire string must be an exact (ignore case) match to "<sip:bob@vorpal.net>". Why do an exact match? No good reason, except to show how the 'equals' operator works.

## Directive

```
              "Directive" : [ { "equals" : "CONTINUE" } ],
```

The name "Directive" is not a normal header, but instead refers to the value defined by: SipServletRequest.getRoutingDirective().toString();

## Region-Label

```
              "Region-Label" : [ { "equals" : "ORIGINATING" } ],
```

The name "Region-Label" is not a normal header, but instead refers to the value defined by: SipServletRequest.getRegion().getLabel().toString();

Isn't that the same as "Region"? Almost... According to the SIP Servlet specs, application developers can define their own unique 'labels' to help clarify specifics around the region type.

## Allow

```
              "Allow" : [ { "contains" : "INV" }, { "includes" : "INVITE" } ] },
```

Let's consider the sample SIP packet again:

```
Allow: INFO, CANCEL, ACK, BYE, UPDATE
Allow: PRACK, INVITE
```

Here we have two similar operators 'contains' and 'includes'. The 'contains' operator will look through every header value and apply the Java String's 'contains' method. The 'includes' operator will do the same, except using the 'equalsIgnoreCase' method.

# Action

Actions are the functions the FSMAR can apply to the SIP message.

```
            "action" : {
              "originating" : "From",
              "route" : [ "sip:proxy1", "sip:proxy2" ] }
```

In this case, the FSMAR is setting the region to "ORIGINATING" and the subscriber URI to the value of the 'From' header.

An alternate example would be to set the region to "TERMINATING" and the subscriber URI to the value of the 'To' header like so:

```
              "terminating" : "To",
```

Failure to specify 'originating' or 'terminating' sets the region to "NEUTRAL" and the subscriber URI to 'null'.


```
              "route" : [ "sip:proxy1", "sip:proxy2" ] }
```

Finally, the 'route' keyword pushes two SIP endpoints onto the route stack. Keywords 'route_back' and 'route_final' may be used as well. See the SIP Servlet specs for a full definition for each purpose. They're similar, but slightly different.

Failure to specify 'route', 'route_back' or 'route_final' results in a SipRouteModifier of "NO_ROUTE".

#Don't Overthink It

The FSMAR can only route the first message in a dialog. For instance, it can route INVITE message through multiple applications, but it cannot tamper with the flow of the subsequent in-dialog messages like "200 OK", ACK, INFO, or BYE. Those message follow the course through the system naturally, without any interference by the FSMAR.

Also, the FSMAR cannot make modification to a SIP message. If that's your goal, you'll want to write a SIP Servlet application. Check out the BLADE framework's B2BUA or PROXY APIs for instructions on how to write your own application.

# Seamless Upgrades and Versioned Applications

The FSMAR keeps track of applications as they are deployed and undeployed in the application server. New calls are routed to the updated applications while old calls continue to travel through the existing applications until they complete naturally. The FSMAR also supports dynamic configuration changes so you never need to reboot a server or drop a call to make updates. The FSMAR maintains a copy of the configuration for each call so changes to the config won't break any calls in progress.

Notes: Please remember to update the 'Weblogic-Application-Version' entry in the application's META-INF/MANIFEST.MF file for seamless upgrades to work.



