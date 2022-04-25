Welcome to the Reductive Reasoning Router (R3).

This is a work in progress...

These are some design notes for the Vorpal:BLADE Router.

How's this for a name?
R3, the...
(R)eductive
(R)easoning
(R)outer

Some Inspiration:
https://docs.microsoft.com/en-us/microsoftteams/direct-routing-translate-numbers
https://www.devglan.com/corejava/pros-and-cons-collection-java
https://service.snom.com/display/wiki/Dial+Plan+-+Regular+Expressions
https://wiki.freepbx.org/display/SBC/Regular+Expressions+in+the+Dial+Plan
https://voipmagazine.wordpress.com/2015/01/18/regular-expressions-for-sip-trunk/

Possible terms for class files:

From 5-tuple:
* source & port
* destination & port
* protocol

Other common terms:
* selector
* rule
* element
* method
* attribute
* operator
* expression
* chain
* endpoint
* translation
* route
* path
* action

* name "some header value"
* pattern "^(\d{10})$"
* translation "+1$1"

Purpose: Design a universal router that can route any (call) from A(lice) to B(ob) based on
any piece of data (quickly).

#Considerations:

All configuration data is pre-loaded. (There will be no remote method invocations to slow things down.)

It's not a simple 1:1 mapping. Some translations require two or more pieces of information, like the 'user' of the 'To' header and the 'Remote-IP' address of the caller.

There are hundreds or pieces of information in a regular SIP INVITE to decode. The configuration must be flexible enough to interpret them all.

The configuration has to be human-readable when saved as JSON.

#Data Structures

Depending upon the type of data, the translation could be stored in either:
* Prefix Trie (good for dialed number plans)
* Tree Map (good for account IDs)
* IPAddress Map (good for IP Addresses, both IPV4 & IPV6)

#Design Concept

In order to map multiple inputs to a final output, a data structure that loops back on itself, like a linked list is required.

Starting from the most common translation mapping use case, an iterator progresses through the linked data structures until it finds the final condition.

First attempt...

```
Config:
  List<Selector>:
    * Attribute (To)
    * Pattern "^(\d{10})$"
    * Expression "+1$1"
    * Map<Expression, Translation>
      * route "null"
      * List<Selector>
        ...
                     
```

The config contains a List of Selectors.
Each Selector specifies an AVP and a corresponding Map of Translations.
A Translation contains a route _or_ another list of Selectors.

So, a 'lookup' method, specifying only a SipServletRequest object should be able to traverse the data structure looking for matches until it finally finds a valid route.

It's important to note that the order of entries in the lists is important, and should be ordered from most common to least-common to improve performance.

Well, there's something wrong with it. How will the JSON parser know which type of Map to use?

In classic object oriented design, there's no need to specify. To properly parse this, we need to specify the type and write special parsers. Ugh!

Let's give it a try... No, needs more work.

```

selectors : [ {
	"Remote-IP"


{
  "routes" : [ {
  	"description" : "STG CL1 ATT IB",
  	"uas" : "10.173.101.120",
    "pattern" : ".*",
    "addresses" : [ "10.173.165.70:5060" ]
  },{
  	"description" : "STG CL1 VZB IB",
  	"uas" : "10.173.101.121",
    "pattern" : ".*",
    "addresses" : [ "10.173.165.69:5060" ]
  },{
  	"description" : "STG CL2 ATT IB",
  	"uas" : "10.173.101.86",
    "pattern" : ".*",
    "addresses" : [ "10.173.165.128:5060" ]
  },{
  	"description" : "STG CL2 VZB IB",
  	"uas" : "10.173.101.87",
    "pattern" : ".*",
    "addresses" : [ "10.173.165.127:5060" ]
  },{
  	"description" : "On Net TFN Test",
  	"uas" : "10.87.152.172|10.87.152.173|10.204.67.59|10.204.67.60",
    "pattern" : "1990.*",
    "addresses" : [ "10.204.67.59:5060","10.204.67.60:5060" ]
  },{
  	"description" : "CL2 DEV",
  	"uas" : ".*",
    "pattern" : "19951.*",
    "addresses" : [ "10.29.68.26:5060" ]
  },{
  	"description" : "CL1 Dev2",
  	"uas" : ".*",
    "pattern" : "19971.*",
    "addresses" : [ "10.86.34.184:5060" ]
  },{
  	"description" : "CL2 STG",
  	"uas" : ".*",
    "pattern" : "19954.*",
    "addresses" : [ "10.29.82.110:5060","10.29.194.20:5060" ]
  },{
  	"description" : "CL1 STG",
  	"uas" : ".*",
    "pattern" : "19974.*",
    "addresses" : [ "10.204.67.59:5060","10.204.67.60:5060" ]
  },{
  	"description" : "CL1 DEV 2 OB",
  	"uas" : "10.86.34.184",
    "pattern" : ".*",
    "addresses" : [ "10.173.165.150:5060" ]
  },{
  	"description" : "CL2 DEV OB",
  	"uas" : "10.28.194.166|10.29.68.26",
    "pattern" : ".*",
    "addresses" : [ "10.173.165.140:5060" ]
  },{
  	"description" : "CL1 STG OB",
  	"uas" : "10.87.152.172|10.87.152.173|10.204.67.59|10.204.67.60",
    "pattern" : ".*",
    "addresses" : [ "10.173.165.152:5060" ]
  },{
  	"description" : "CL2 STG OB",
  	"uas" : "10.28.82.132|10.28.201.244|10.29.82.110|10.29.194.20",
    "pattern" : ".*",
    "addresses" : [ "10.173.165.142:5060" ]
  } ]
}
		


```


Trying again...

```
  	"description" : "On Net TFN Test",
  	"uas" : "10.87.152.172|10.87.152.173|10.204.67.59|10.204.67.60",
    "pattern" : "1990.*",
    "addresses" : [ "10.204.67.59:5060","10.204.67.60:5060" ]


user(1990) -> remoteIP(10.87.152.172) -> sip:10.204.67.59:5060

selector
  * To
  * user
  * Map< "1990", Translation


```









