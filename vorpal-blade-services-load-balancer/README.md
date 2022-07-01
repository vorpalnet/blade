# Vorpal:BLADE

Design considerations...

1. Use R3 to match on selectors, to establish a single endpoint.
2. Load-Balancer uses hash map to find a proxy rule.
3. A proxy rule consists of parallel / serial proxy tiers


```
ProxyRule proxy(SipServletRequest request){
	gather proxy rule based on request.getUser() + "@" + request.getHost();

	return new proxyRule
}
```

This requires modification of the BLADE Proxy classes.

On success / failure:

void proxyResponse( SipServletResponse response, ProxyRule proxyRule){

---

Notes: 

1) Randomize Serial Proxy


}