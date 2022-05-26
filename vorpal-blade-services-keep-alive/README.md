# Vorpal:BLADE Keep-Alive

[Javadocs](https://vorpalnet.github.io/vorpal-blade-keep-alive/index.html)

It seems like such a simple concept, but preventing applications from timing out can be quite challenging.
By some unchangeable hard-coded default, the SipApplicationSession in OCCAS will time-out after 3 minutes. 
You want to set the SipApplicationSession.setExpires() to a value high enough that your application never
times-out, but low enough you don't run out of memory if endpoints stop communicating.

This keep-alive application should do the trick.

A couple of things to note...

Place "keep-alive" anywhere within the call path (via FSMAR).

If both SIP endpoints "allow" UPDATE, then keep-alive will send an UPDATE message to both parties.

If UPDATE is not allowed, then keep-alive will perform a SIP re-INVITE.

Keep-Alive will insert "Session-Expires" and "Min-SE" headers based on values in the config files.

Vorpal:BLADE applications will set the SipApplicationSession.setExpires() to the value of "Session-Expires" header
plus one extra minute for a little bit of wiggle room.

Example config file:

```
{
  "sessionExpires" : 1800,
  "minSE" : 90
}
```


