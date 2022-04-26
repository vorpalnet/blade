# Router

Welcome to the Vorpal:BLADE Reductive Reasoning Router (R3).

The goal behind R3 is to build a universal router that can build translations maps based on any piece of data
within a SIP message without scripting (for now). It is called "reductive" because it works upon a simple
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
    "id" : "remote-ip",
    "attribute" : "Remote-IP",
    "pattern" : "^(.*)$",
    "expression" : "$1"
  } ]
```

The 'to-user' selector will look at the value of the 'To' header, parse it into groupings
and use the 2nd grouping which represents the 'user' part of the SIP address to define the map key.

The 'remote-ip' selector look at the "SipServletRequest.getRemoteAddress()" value and use the first and only grouping
to define the map key (which is the IP address).

## Example Map

Now that we have the 'to-user' selector, we can create a 'prefix' map to parse dialed numbers.

Consider this example:

```
 "maps" : [ {
    "type" : "address",
    "id" : "address-map-1",
    "description" : "Translations Map for Remote IP addresses",
    "selector" : "remote-ip",
    "map" : {
      "10.28.82.132" : {
        "description" : "CL2 STG OB",
        "requestUri" : "sip:10.173.165.142:5060"
      }
    }
  }, {      
    "type" : "prefix",
    "id" : "prefix-map-1",
    "description" : "Translations map for dialed number prefixes",
    "selector" : "to-user",
    "map" : {
      "19951" : {
        "description" : "CL2 DEV",
        "requestUri" : "sip:10.29.68.26:5060"
      },
      "19954" : {
        "description" : "CL2 STG",
        "requestUri" : "sip:10.29.82.110:5060"
      }
    }
  } ]      
```

From these two maps, you can see how:
* a call comes from "10.28.82.132", it will be routed to the "CL2 STG OB" server
* a dialed number looks like "1 (995) 1xxx-xxxx", it will be routed to the "CL2 DEV" server

## Reductive Maps

Now we have two types of maps, but how is that reductive? Instead of two completely different maps,
it is possible to reference one (or more) inside another.

Consider this variation:

```
  "maps" : [ {
    "type" : "address",
    "id" : "address-map-2",
    "description" : "Translations Map for Remote IP addresses",
    "selector" : "remote-ip",
    "map" : {
      "10.28.82.132" : {
        "description" : "CL2 STG OB",
        "requestUri" : "sip:10.173.165.142:5060"
      }
    }
  }, {
    "type" : "prefix",
    "id" : "prefix-map-2",
    "description" : "Translations map for dialed number prefixes",
    "selector" : "to-user",
    "map" : {
      "19974" : {
        "description" : "CL1 STG",
        "list" : [ "address-map-2" ],
      }
    }
  } ],
```

You'll notice that each 'translation' entry may contain a list to another map. In this case 
if the call dialed number starts with "19974" _and_ originated from the "10.28.82.132", the it will
be routed to the "CL2 STG OB" server. Being a list, if the first map's selector does not find a match,
the R3 router will try each subsequent map in the list until success.

Try hard to avoid circular references!

If there is no success, no match is made -- or -- by defining the requestUri in the "19974" translation
entry, you can define a default address. Example:

```
      "19974" : {
        "description" : "CL1 STG",
        "list" : [ "address-map-2" ],
        "requestUri" : "sip:10.204.67.59:5060" 
      }
```

Or, if you want to get really fancy, you can modify the requestUri before
searching the additional maps in the list by using the selector's groupings. Consider
this example:

```
      "19974" : {
        "description" : "CL1 STG",
        "list" : [ "address-map-2" ],
        "requestUri" : "sip:$1@10.204.67.59:5060" 
      }
```

Can you spot the difference? The requestUri has "$1@" added to it to create an address similar to:

```
sip:1997451234@10.204.67.59:5060
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

In this particular case, the default route uses the Vorpal:BLADE test UAS server to
respond back with a 404 "not found" status code.

## Additional Thoughts...

So far, we've only discussed 'address' and 'prefix' maps. You can also use the three different
String maps





