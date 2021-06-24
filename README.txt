The RESTful web service API can be found at:

http://blade:7001/shuffle/resources/application.wadl?detail=true

The configuration file can be found at:

<domain>/config/custom/vorpal/shuffle.json


The default config file (shuffle.json) looks something like this:

{
  "uuidHeader" : "From",
  "uuidRegExPattern" : "(?<=\\:)(.*?)(?=\\@)",
  "endpoints" : {
    "carol" : [ "sip:carol@vorpal.net", "sip:carol@vorpal.net" ]
  }
}

The method 'blindTransfer' accepts a session key, which in this instance takes
the 'From' header and matches on the SIP user name (between the ':' and the '@' symbols).

It also accepts an 'endpoint', which in this case is 'carol', which maps to
a randomly selected SIP endpoint in the list. If there is no 'carol', then
it will attempt to use the 'endpoint' parameter as a SIP URI. So, you
could pass in 'sip:bob@vorpal.net' as the endpoint and the shuffle app will
try to transfer the call to that location.


Log files specific to this application can be found at:

<domain>/servers/AdminServer/logs/vorpal/shuffle.0.log



Notes for Jeff:

SDP Black Hole:

v=0
o=- 15474517 1 IN IP4 127.0.0.1
s=cpc_med
c=IN IP4 0.0.0.0
t=0 0
m=audio 23348 RTP/AVP 0
a=rtpmap:0 pcmu/8000
a=sendrecv

