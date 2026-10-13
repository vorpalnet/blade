# Dashboard

One page for OCCAS and BLADE: live server health, live operations, and call
analytics with a report behind every number. It replaces what the WebLogic Console
showed before the Remote Console, and the call reports a BI tool would otherwise
need.

`blade-dashboard.war`, context root `/blade/dashboard`, deployed to the AdminServer.
It is a `proto/` application: built and shipped loose in `dist/proto/`, not bundled
in an EAR.

## What it shows

**The board** (`/blade/dashboard/`) fits one screen and never scrolls. A row of
headline numbers (servers running, calls now, calls, answered, median call, calls
flagged, queued now), then six panels: servers, traffic, operations, outcomes,
applications and call risk. Each panel switches between views with the tabs in its
header instead of stacking more charts below. A tile that needs attention is
outlined and says what is wrong in words.

Traffic's **Network** tab is a map of the sites in the settings' `sites` list: where this
domain's servers run (`cluster`), where calls arrive from (`inbound`) and where they are
sent (`downstream`), with a line for each route. A site and each line take the stoplight
colour of their health and a shape that says the same (circle working, diamond trouble,
square down, hollow no data):
- a downstream site from the balancer's OPTIONS pings to its endpoints, a gateway's failing
  trunk registration, and the legs its servers refused in the last `networkWindowMinutes`:
  equipment failing (408, 5xx) is amber and red at `networkFailingCalls`, configuration
  refused (404, 403, 488 and the like) amber, a callee's busy or decline shown uncoloured;
- an inbound site from when it last sent a call, with a warning after `quietMinutes`;
- this domain's servers from their live state;
- the connection pools whose database is at a site (`datasources`), read live;
- the services applications call there (REST lookups, token endpoints, directories),
  from each application's `Remotes` MBean, with circuit breakers that are open;
- the event bus, on the cluster site, as the JMS broker sees it.

Sites close enough to overlap share one mark; clicking it zooms in on every server there.
A balancer site with coordinates joins the map without being entered twice. Addresses no
site claims are listed under the map.

- One domain or all of them, chosen in the top bar. This domain's servers and
  operations are live: runtime MBeans and the event bus, every 5 seconds. Another
  domain's servers come from the minute samples its own dashboard writes to the
  shared analytics database, so a domain shows here only if it runs the dashboard.
- The analytics panels cover the last day, 7 days or 30 days, chosen in the top bar,
  from data at most five minutes old, so a wall of open browsers costs the database
  one query per report every five minutes.
- Thresholds and refresh intervals are in the dashboard's settings, in the
  Configurator.
- Below 1000 × 640 the board stacks into a scrolling page.
- Times are the viewer's: the browser's zone unless another is picked beside the
  clock. The data is UTC; days and hours are cut in the chosen zone.

**Who sees what.** Sign-in is the deployment's OpenID Connect provider when
`blade-oidc.properties` is deployed with the WAR, the WebLogic login otherwise. The grants in
the dashboard's settings then decide, per group, which domains' servers, which tenants' and
departments' calls, and whether individual calls and what was said are shown at all. A
department sees only its own parts of a call. Without grants, only the WebLogic admin roles
see anything. Each look at individual calls is recorded as a `dashboard.viewed` event.

**Reports** (`/blade/dashboard/report.html?r=<report>`): a panel's title opens its
full report, queried live, with a date range, a domain selector, an account search and an "Include
sample data" switch. The settings stay in the address bar, so a report can be
bookmarked or sent to a colleague.

| Report | Answers |
| --- | --- |
| Application metrics | every counter and timing each application keeps, as history past the redeploy that resets it |
| Data health | what each application reports and never sends, events the bus refused, which applications share a call, calls recorded twice |
| Call volume | calls per day and hour, hour × weekday, by application, engine and tenant, peak calls up at once |
| Call duration | average, median and longest; calls by length; duration per application |
| Call outcomes | started, answered, completed and lost calls; answer rate per application |
| Open sessions | sessions that never closed, whenever they started |
| Platform activity | application starts and stops, versions that handled calls, each server's SIP traffic, refusals, sessions, heap and threads minute by minute |
| Event activity | every recorded event, by type and application |
| Call risk | assessments, bands, triggering signals, what each signal contributed; agent verdicts, and the worst band against the verdict |
| High-risk calls | calls that reached WATCH or SUSPECT, worst first |
| Caller conversation | utterances, intents, and whether the system was addressed |

Rows written by the Analytics Console's sample-data generator (`cluster_name =
'sample'`) are left out unless a report asks for them.

## Running it

The analytics half needs the analytics database: the `BladeAnalytics` data source
targeted at the AdminServer, holding the reporting views from
`services/analytics/sql/<database>-analytics-views.sql`. Oracle, MySQL and SQL Server
all work: the reports are JPA queries over those views. Without the data source the
dashboard still runs; the analytics cards say why they are empty.

Settings (Configurator, `blade-dashboard.json`):

| Setting | Default | Meaning |
| --- | --- | --- |
| `analyticsDataSource` | `jdbc/BladeAnalytics` | JNDI name of the analytics data source |
| `historyDays` | `30` | days of history the overview cards cover |

Access needs one of the Admin, Operator, Deployer or Monitor roles, through the
portal login.

Charts are drawn with [D3](https://d3js.org), served from the WAR; see
`src/main/webapp/vendor/THIRD-PARTY.md`.
