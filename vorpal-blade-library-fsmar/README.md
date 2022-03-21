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
* scheme   -- either 'sip' or 'sips'
* equals   -- case sensitive comparison of the _full line_ of the first header found
* matches  -- regular expression match of the _full line_ of the first header found
* contains -- partial string match of the _full line_ of the first header found
* includes -- regular expression match for any of the values found in a comma delimited header
* value    -- case sensitive match of any 'value' of a header (without parameters / tags)
* ???      -- anything else is treated as a parameter. i.e.: __Expires: 3600;refresher=uac__ ('refresher' in this case)

By default, if no condition is defined, it is considered a match. Multiple conditions are ANDed together. If you're looking for OR capabilities, consider creating two different transitions.

# Defining Actions

The FSMAR can only take just a few actions once a condition is matched. They include:




# Examples

Condider the following SIP packet:

```
INVITE sip:bob@vorpal.org SIP/2.0
Content-Type: application/sdp
To: "Bob" <sip:bob@vorpal.org>;loc=wonderland
Via: SIP/2.0/TCP 192.168.1.206:5060;wlsscid=1a25;branch=z9b;wlsssid=12dnl1j
Min-SE: 90
Allow: INVITE, CANCEL, ACK, BYE, UPDATE
Allow: INFO, PRACK
Call-ID: wlss-19b2247c-c038baf4182b6d4aaa762b897434deb7@192.168.1.206
From: <sip:alice@vorpal.org>loc=wonderland;tag=f234ee12
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

Here's a simple example of routing a call from the outside world, to an application named "b2bua", then to an application named "proxy-registrar", finally to the outside world again. Note: A message coming from outside the system is said to be originating from the 'null' state. A message leaving the system needs no documentation. The FSMAR will route the call to the outside world when there are no matching transitions found in the configuration.

```
{
  "null" : {
    "INVITE" : {
      "b2bua" : [ ]
    }
  },
  "b2bua" : {
    "INVITE" : {
      "proxy-registrar" : [ ]
    }
  }
}
```

Consider the __Request-URI__. This example uses the 'uri' operator to perform a regular expression match.

```json
{
  "app1" : {
    "INVITE" : {
      "final" : [ {
        "condition" : {
          "Request-URI" : [ {
            "uri" : "^.*sip[s]:alice@vorpal.org.*$"
          } ]
        }
      } ]
    }
  }
}
```

Consider the ____ header. Here's a example that uses 'address' to match a regular expression against the From header. It's slightly different from 'uri' in that it can match against the display name and any trailing parameters.

```json
{
  "ex1" : {
    "INVITE" : {
      "app1" : [ {
        "condition" : {
          "To" : [ {
            "address" : "^.*<sip[s]:alice@vorpal.org>.*loc=wonderland.*$"
          } ]
        }
      } ]
    }
  }
}
```

Consider the _From' header. Here's an example that uses 'user', 'host', and 'loc' to match against the address header. It's a little easer to read than using regular expressions.

```json
{
  "ex3" : {
    "INVITE" : {
      "app3" : [ {
        "condition" : {
          "From" : [ { "user" : "alice" },
                     { "host" : "vorpal.org" }, 
                     { "loc" : "wonderland"  } ]
        }
      } ]
    }
  }
}
```

Consider the mysterious header "X-Version-Number". Here's an example that uses 'equals' to do an exact match on the value "2.1.3".

```json
{
  "ex4" : {
    "INVITE" : {
      "app4" : [ {
        "condition" : {
          "X-Version-Number" : [ {
            "equals" : "2.1.3"
          } ]
        }
      } ]
    }
  }
}
```


Consider the same header. This example uses 'matches' to perform a regular expression match on the value "2.1".

```json
{
  "ex5" : {
    "INVITE" : {
      "app5" : [ {
        "condition" : {
          "X-Version-Number" : [ {
            "matches" : "^2\\.1.*$"
          } ]
        }
      } ]
    }
  }
}
```

Consider the header __Allow__. This example uses 'contains' to match against the value 'UPDATE'. Note: The operator 'contains' is only applied to the first header found. It would not match against the values "INFO" or "PRACK".

```json
{
  "app5" : {
    "INVITE" : {
      "final" : [ {
        "condition" : {
          "Allow" : [ {
            "contains" : "UPDATE"
          } ]
        }
      } ]
    }
  }
}
```

Consider the same __Allow__ headers. This example uses 'includes' to match against the value 'PRACK'.

```json
{
  "app7" : {
    "INVITE" : {
      "app8" : [ {
        "condition" : {
          "Allow" : [ {
            "includes" : "PRACK"
          } ]
        }
      } ]
    }
  }
}
```

Consider the __Session-Expires__ header. This example uses 'value' to match against '3600' and 'refresher' to match against 'uac'.

```json
{
  "ex7" : {
    "INVITE" : {
      "app7" : [ {
        "condition" : {
          "Session-Expires" : [ {
            "value" : "3600"
          }, {
            "refresher" : "uac"
          } ]
        }
      } ]
    }
  }
}
```
Here's an example that sets the routing region to 'originating' and the subscriber URI to the value of the 'From' header.

```
{
  "app8" : {
    "INVITE" : {
      "app9" : [ {
        "action" : {
          "originating" : "From"
        }
      } ]
    }
  }
}
```

Here's an example of an action that sets the routing region to 'terminating' and the subscriber URI to the value of the 'To' header.

```
{
  "app10" : {
    "INVITE" : {
      "app11" : [ {
        "action" : {
          "terminating" : "To"
        }
      } ]
    }
  }
}
```

Here's an example that performs the "route" action.

```
  "app11" : {
    "INVITE" : {
      "app12" : [ {
        "action" : {
          "route" : [ "sip:proxy1", "sip:proxy2" ]
        }
      } ]
    }
  }
```
Here's an example that performs the "route back" action.

```
{
  "app12" : {
    "INVITE" : {
      "app13" : [ {
        "action" : {
          "route_back" : [ "sip:proxy1", "sip:proxy2" ]
        }
      } ]
    }
  }
}
```
Here's an example that performs the "route final" action.

```
{
  "app13" : {
    "INVITE" : {
      "app14" : [ {
        "action" : {
          "route_back" : [ "sip:proxy1", "sip:proxy2" ]
        }
      } ]
    }
  }
}
```


#Don't Overthink It

The FSMAR can only route the first message in a dialog. For instance, it can route INVITE message through multiple applications, but it cannot tamper with the flow of the subsequent in-dialog messages like "200 OK", ACK, INFO, or BYE. Those message follow the course through the system naturally, without any interference by the FSMAR.

Also, the FSMAR cannot make modification to a SIP message. If that's your goal, you'll want to write a SIP Servlet application. Check out the BLADE framework's B2BUA or PROXY APIs for instructions on how to write your own application.

# Seamless Upgrades and Versioned Applications

The FSMAR keeps track of applications as they are deployed and undeployed in the application server. New calls are routed to the updated applications while old calls continue to travel through the existing applications until they complete naturally. The FSMAR also supports dynamic configuration changes so you never need to reboot a server or drop a call to make updates. The FSMAR maintains a copy of the configuration for each call so changes to the config won't break any calls in progress.

Notes: Please remember to update the 'Weblogic-Application-Version' entry in the application's META-INF/MANIFEST.MF file for seamless upgrades to work.



