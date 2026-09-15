# Proxy-Router

Welcome to the BLADE R3, the Reductive Reasoning Router.

The goal behind R3 is to build a universal router that can build translations maps based on any piece of data
within a SIP message without scripting. It is called "reductive" because it works upon a simple
concept of defining translation maps within translation maps, each operating on a single piece of information
until the final route is chosen.

## Translation Maps

The key to understanding R3 is to understand the concept of translation maps.

In R3, there are 5 types of translation maps, each extended from the abstract "TranslationsMap" class.

```
@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
		@JsonSubTypes.Type(value = ConfigAddressMap.class, name = "address"),
		@JsonSubTypes.Type(value = ConfigPrefixMap.class, name = "prefix"),
		@JsonSubTypes.Type(value = ConfigHashMap.class, name = "hash"),
		@JsonSubTypes.Type(value = ConfigLinkedHashMap.class, name = "linked"),
		@JsonSubTypes.Type(value = ConfigTreeMap.class, name = "tree")
})
```

Translation Maps:

* address - operates on IP addresses, both IPv4 & IPv6.
* prefix  - operates on dialed number prefixes
* hash    - operates on string values using HashMap (fastest, assuming no collisions)
* linked  - operates on string values using LinkedHashMap (maintains insertion order)
* tree    - operates on string values using TreeMap (sorts on ascending order for speed)

## Selectors

How do translation maps work? To understand that is to understand _selectors_.

A _selector_ uses regular expressions to determine which piece of data within the SIP message determine the 'key' to the map.

A _selector_ is defined by the following pieces of information:

```
	private String id; // optional for JSON references
	private String description; // optional for human readable descriptions
	private String attribute; // location of the key data, like in the 'To' header
	private Pattern pattern; // regular expression using capturing groups to parse the key data
	private String expression; // replacement pattern, like $1 to format the key data
```

Consider these two examples in the JSON config file.

```
  "selectors" : [ {
    "id" : "to-user",
    "attribute" : "To",
    "pattern" : "^(sips?):([^@]+)(?:@(.+))?$",
    "expression" : "$2"
  }, {
    "id" : "origin-ip",
    "attribute" : "Origin-IP",
    "pattern" : "^(.*)$",
    "expression" : "$1"
  } ]
```

The 'to-user' selector will look at the value of the 'To' header, parse it into groupings
and use the 2nd grouping which represents the 'user' part of the SIP address to define the map key.

The 'origin-ip' selector look at the "SipServletRequest.getRemoteAddress()" value and use the first and only grouping
to define the map key (which is the IP address).

## Example Map

Now that we have the 'to-user' selector, we can create a 'prefix' map to parse dialed numbers.

Consider this example:

```
 "maps" : [ {
    "type" : "address",
    "id" : "address-map-1",
    "description" : "Translations Map for Remote IP addresses",
    "selector" : "origin-ip",
    "map" : {
      "198.51.100.10" : {
        "description" : "staging_2 outbound",
        "requestUri" : "sip:192.0.2.42:5060"
      }
    }
  }, {      
    "type" : "prefix",
    "id" : "prefix-map-1",
    "description" : "Translations map for dialed number prefixes",
    "selector" : "to-user",
    "map" : {
      "19951" : {
        "description" : "dev_2",
        "requestUri" : "sip:198.51.100.13:5060"
      },
      "19954" : {
        "description" : "staging_2",
        "requestUri" : "sip:198.51.100.14:5060"
      }
    }
  } ]      
```

From these two maps, you can see how:
* a call comes from "198.51.100.10", it will be routed to the "staging_2 outbound" server
* a dialed number looks like "1 (995) 1xxx-xxxx", it will be routed to the "dev_2" server

## Reductive Maps

Now we have two types of maps, but how is that reductive? Instead of two completely different maps,
it is possible to reference one (or more) inside another.

Consider this variation:

```
  "maps" : [ {
    "type" : "address",
    "id" : "address-map-2",
    "description" : "Translations Map for Remote IP addresses",
    "selector" : "origin-ip",
    "map" : {
      "198.51.100.10" : {
        "description" : "staging_2 outbound",
        "requestUri" : "sip:192.0.2.42:5060"
      }
    }
  }, {
    "type" : "prefix",
    "id" : "prefix-map-2",
    "description" : "Translations map for dialed number prefixes",
    "selector" : "to-user",
    "map" : {
      "19974" : {
        "description" : "staging_1",
        "list" : [ "address-map-2" ],
      }
    }
  } ],
```

You'll notice that each 'translation' entry may contain a list to another map. In this case 
if the call dialed number starts with "19974" _and_ originated from the "198.51.100.10", the it will
be routed to the "staging_2 outbound" server. Being a list, if the first map's selector does not find a match,
the R3 router will try each subsequent map in the list until success.

Try hard to avoid circular references!

If there is no success, no match is made -- or -- by defining the requestUri in the "19974" translation
entry, you can define a default address. Example:

```
      "19974" : {
        "description" : "staging_1",
        "list" : [ "address-map-2" ],
        "requestUri" : "sip:198.51.100.30:5060" 
      }
```

Or, if you want to get really fancy, you can modify the requestUri before
searching the additional maps in the list by using the selector's groupings. Consider
this example:

```
      "19974" : {
        "description" : "staging_1",
        "list" : [ "address-map-2" ],
        "requestUri" : "sip:$1@198.51.100.30:5060" 
      }
```

Can you spot the difference? The requestUri has "$1@" added to it to create an address similar to:

```
sip:1997451234@198.51.100.30:5060
```

How cool is that?


## Putting It All Together

This is all good and fine, there's one piece missing... We need to define some type of "dialing plan"
in order for the R3 router to know which map to start searching and when to end.

It is trivial to simply put everything in order:

```
  "plan" : [ "address-map-1", "prefix-map-1" ],
  "defaultRoute" : {
    "id" : "default",
    "description" : "If no translation found, apply default route.",
    "requestUri" : "sip:uas;status=404"
  }
```

In this case, the R3 router will search "address-map-1" for a match. Failing that, it will search "prefix-map-1".
Finally, failing to find any matches, it will use the default route. 

In this particular case, the default route uses the BLADE test UAS server to
respond back with a 404 "not found" status code.

## Additional Thoughts...

So far, we've only discussed 'address' and 'prefix' maps. You can also use the three different
types of string maps to operate on other types of data in the SIP INVITE message.


# Sample Configuration File

A sample configuration file is saved as R3.SAMPLE.

```
{
  "selectors" : [ {
    "id" : "to-user",
    "attribute" : "To",
    "pattern" : "^(sips?):([^@]+)(?:@(.+))?$",
    "expression" : "$2"
  }, {
    "id" : "origin-ip",
    "attribute" : "Origin-IP",
    "pattern" : "^(.*)$",
    "expression" : "$1"
  } ],
  "maps" : [ {
    "type" : "address",
    "id" : "address-map-1",
    "description" : "Translations Map for Remote IP addresses",
    "selector" : "origin-ip",
    "map" : {
      "198.51.100.10" : {
        "description" : "staging_2 outbound",
        "requestUri" : "sip:192.0.2.42:5060"
      },
      "198.51.100.11" : {
        "description" : "dev_2 outbound",
        "requestUri" : "sip:192.0.2.40:5060"
      },
      "198.51.100.12" : {
        "description" : "staging_2 outbound",
        "requestUri" : "sip:192.0.2.42:5060"
      },
      "198.51.100.13" : {
        "description" : "dev_2 outbound",
        "requestUri" : "sip:192.0.2.40:5060"
      },
      "198.51.100.14" : {
        "description" : "staging_2 outbound",
        "requestUri" : "sip:192.0.2.42:5060"
      },
      "198.51.100.15" : {
        "description" : "staging_2 outbound",
        "requestUri" : "sip:192.0.2.42:5060"
      },
      "198.51.100.16" : {
        "description" : "staging_1 outbound",
        "requestUri" : "sip:192.0.2.52:5060"
      },
      "198.51.100.17" : {
        "description" : "staging_1 outbound",
        "requestUri" : "sip:192.0.2.52:5060"
      },
      "198.51.100.20" : {
        "id" : "trunk_a_inbound_2",
        "requestUri" : "sip:192.0.2.28:5060"
      },
      "198.51.100.21" : {
        "id" : "trunk_b_inbound_2",
        "requestUri" : "sip:192.0.2.27:5060"
      },
      "198.51.100.22" : {
        "id" : "trunk_a_inbound_1",
        "requestUri" : "sip:192.0.2.70:5060"
      },
      "198.51.100.23" : {
        "id" : "trunk_b_inbound_1",
        "requestUri" : "sip:192.0.2.69:5060"
      },
      "198.51.100.30" : {
        "description" : "staging_1 outbound",
        "requestUri" : "sip:192.0.2.52:5060"
      },
      "198.51.100.31" : {
        "description" : "staging_1 outbound",
        "requestUri" : "sip:192.0.2.52:5060"
      },
      "127.0.0.1" : {
        "requestUri" : "sip:localhost:5060"
      }
    }
  }, {
    "type" : "prefix",
    "id" : "prefix-map-1",
    "description" : "Translations map for dialed number prefixes",
    "selector" : "to-user",
    "map" : {
      "19951" : {
        "description" : "dev_2",
        "requestUri" : "sip:198.51.100.13:5060"
      },
      "19954" : {
        "description" : "staging_2",
        "requestUri" : "sip:198.51.100.14:5060"
      },
      "19971" : {
        "description" : "dev_1b",
        "requestUri" : "sip:198.51.100.40:5060"
      },
      "19974" : {
        "description" : "staging_1",
        "list" : [ "address-map-1" ],
        "requestUri" : "sip:198.51.100.30:5060"
      }
    }
  } ],
  "plan" : [ "address-map-1", "prefix-map-1" ],
  "defaultRoute" : {
    "id" : "default",
    "description" : "If no translation found, apply default route.",
    "requestUri" : "sip:uas;status=404"
  }
}
```





