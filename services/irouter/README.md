# BLADE Intelligent Router (irouter)

The Intelligent Router is a B2BUA-based SIP routing service built on the BLADE v3 configuration framework. It routes inbound SIP calls by extracting keys from SIP headers, looking them up in local translation tables, and optionally querying external systems (REST APIs, JDBC databases, LDAP directories) for dynamic routing decisions.

## Why B2BUA?

Unlike the proxy-based `proxy-router`, the irouter operates as a Back-to-Back User Agent. This keeps the irouter in the dialog path for the full call lifetime, enabling:

- Full header control on both call legs (add, modify, remove)
- SDP manipulation (codec filtering, hold, recording integration)
- Mid-call event handling (re-INVITE, UPDATE, INFO)
- Session attribute access throughout the call lifetime
- Integration with external systems before and during routing

## Routing Flow

When an INVITE arrives, the irouter follows this resolution pipeline:

```
1. Selectors     → Extract routing key + session attributes from SIP headers
2. Local Plan    → Search hash/prefix translation tables (fast, in-memory)
3. Resolvers     → Query REST/JDBC/LDAP if no local match (external call)
4. Default Route → Final fallback
```

### Step 1: Selectors

Selectors extract named values from SIP messages using regular expressions with named capturing groups. The first selector that returns a key provides the **routing key**. All selectors' named groups are collected as **session attributes** and stored on the `SipApplicationSession`.

```json
{
  "selectors": [
    {
      "id": "to-user",
      "attribute": "To",
      "pattern": "(?:sips?):(?:(?<user>.*)@)*(?<host>[^:;>]*).*",
      "expression": "${user}"
    }
  ]
}
```

Supported attributes: `To`, `From`, `Request-URI`, `Content`, `Remote-IP`, or any SIP header name.

### Step 2: Local Plan

The plan is an ordered list of translation tables searched in sequence. Two table types are supported:

- **hash** — exact-match lookup (e.g. route by dialed number)
- **prefix** — longest-prefix match using a trie (e.g. route by area code)

```json
{
  "plan": [
    {
      "type": "hash",
      "id": "routes",
      "translations": {
        "sales": {
          "id": "sales",
          "treatment": {
            "requestUri": "sip:sales@queue.example.com"
          }
        }
      }
    },
    {
      "type": "prefix",
      "id": "area-codes",
      "translations": {
        "1800": {
          "id": "1800",
          "treatment": {
            "requestUri": "sip:tollfree@carrier.example.com"
          }
        },
        "1": {
          "id": "1",
          "treatment": {
            "requestUri": "sip:domestic@carrier.example.com"
          }
        }
      }
    }
  ]
}
```

### Step 3: Resolvers

Resolvers query external systems when no local table matches. Three types are available:

| Type | System | Connection | Template |
|------|--------|------------|----------|
| `rest` | HTTP/REST API | URL + basic auth or bearer token | `_templates/*.txt` |
| `jdbc` | Database | WebLogic JNDI DataSource | `_templates/*.sql` |
| `ldap` | Directory (Active Directory, OpenLDAP) | URL + bind DN/password | `_templates/*.ldap` |

All resolvers follow the same pattern:

1. Session attributes from Step 1 populate `${var}` placeholders in URLs, queries, and templates
2. The external system returns data (JSON, SQL result set, LDAP entry)
3. The response is converted to a JSON object
4. A `responseSelector` extracts the routing decision via JsonPath

```json
{
  "resolvers": [
    {
      "type": "rest",
      "id": "customer-api",
      "url": "https://api.example.com/route/${user}",
      "method": "POST",
      "bodyTemplate": "customer-lookup.txt",
      "bearerToken": "your-api-token",
      "timeoutSeconds": 3,
      "responseSelector": {
        "id": "route-dest",
        "attribute": "$.route.destination",
        "pattern": "(?<uri>.*)",
        "expression": "${uri}"
      }
    },
    {
      "type": "jdbc",
      "id": "customer-db",
      "dataSource": "jdbc/CustomerDS",
      "queryTemplate": "customer-route.sql",
      "responseSelector": {
        "id": "dest-uri",
        "attribute": "$.destination_uri",
        "pattern": "(?<uri>.*)",
        "expression": "${uri}"
      }
    },
    {
      "type": "ldap",
      "id": "active-directory",
      "ldapUrl": "ldap://ad.corp.example.com:389",
      "bindDn": "cn=blade-svc,ou=ServiceAccounts,dc=corp,dc=example,dc=com",
      "bindPassword": "secret",
      "searchTemplate": "agent-lookup.ldap",
      "responseSelector": {
        "id": "dest-uri",
        "attribute": "$.destinationURI",
        "pattern": "(?<uri>.*)",
        "expression": "${uri}"
      }
    }
  ]
}
```

### Step 4: Default Route

If nothing matches, the default route is used:

```json
{
  "defaultRoute": {
    "id": "default",
    "treatment": {
      "requestUri": "sip:operator@pbx.example.com"
    }
  }
}
```

## Template Files

External request templates live in `<domain>/config/custom/vorpal/_templates/`. This keeps complex query bodies out of the JSON config file and avoids the problem of embedding JSON-with-placeholders inside JSON.

### REST Template (`_templates/customer-lookup.txt`)

Uses HTTP message format — headers above a blank line, JSON body below:

```
Content-Type: application/json
X-Correlation-ID: ${key}
X-Source-App: irouter

{
  "query": {
    "calledNumber": "${user}",
    "callingNumber": "${callingNumber}",
    "sourceHost": "${host}"
  },
  "options": {
    "includeFailover": true,
    "maxResults": 1
  }
}
```

### SQL Template (`_templates/customer-route.sql`)

Plain SQL with `${var}` placeholders:

```sql
SELECT destination_uri, priority, carrier
FROM routing_table
WHERE called_number = '${user}'
  AND active = 1
ORDER BY priority
FETCH FIRST 1 ROW ONLY
```

### LDAP Template (`_templates/agent-lookup.ldap`)

Search parameters above a blank line, LDAP filter below:

```
base: ou=Users,dc=corp,dc=example,dc=com
scope: SUBTREE
attributes: destinationURI,department,priority,displayName

(&(objectClass=user)(telephoneNumber=${user}))
```

## Treatment Type

The irouter's treatment payload (`RoutingTreatment`) defines:

- `requestUri` — destination SIP URI for the outbound INVITE
- `headers` — optional map of headers to add/set on the outbound INVITE

```json
{
  "requestUri": "sip:support@queue.example.com",
  "headers": {
    "X-Priority": "high",
    "X-Customer-ID": "ACME-001"
  }
}
```

## Configuration Files

| File | Location |
|------|----------|
| Config | `<domain>/config/custom/vorpal/irouter.json` |
| Schema | `<domain>/config/custom/vorpal/_schemas/irouter.jschema` |
| Sample | `<domain>/config/custom/vorpal/_samples/irouter.json.SAMPLE` |
| Templates | `<domain>/config/custom/vorpal/_templates/` |

## Deployment

Deploy `irouter.war` to the OCCAS cluster. The WAR depends on the `vorpal-blade` shared library. Context root: `/irouter`.

## Javadocs

- [IRouterServlet](https://vorpal.net/javadocs/blade/irouter) — B2BUA servlet and routing flow
- [RouterConfiguration](https://vorpal.net/javadocs/blade/framework) — Generic routing config with selectors, plan, and resolvers
- [Selector](https://vorpal.net/javadocs/blade/framework) — Pattern-based key extraction from SIP, HTTP, JSON, and LDAP
- [RestResolver](https://vorpal.net/javadocs/blade/framework) — HTTP/REST API resolver with template support
- [JdbcResolver](https://vorpal.net/javadocs/blade/framework) — JDBC resolver using WebLogic DataSources
- [LdapResolver](https://vorpal.net/javadocs/blade/framework) — LDAP/Active Directory resolver
