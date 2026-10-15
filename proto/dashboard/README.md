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
applications and calls. Each panel switches between views with the tabs in its
header instead of stacking more charts below. A tile that needs attention is
outlined and says what is wrong in words.

**Detail grows left to right, and a click narrows what is to its right.** The left
column says whether things work (Servers; how calls ended), the middle what they are
doing (Traffic; which applications), the right what went wrong (Operations; the calls
themselves). Click a server and Traffic and Operations show only that server. Click an
hour on a Traffic chart, or a cell of the weekday grid, and Operations shows only that
time. Click Failed in Outcomes and Applications and Calls show only the failed calls;
click an application and Calls shows only its calls, each one opening in Trace. Applications'
Causes tab groups the bad calls by what the flight recorder found (whose fault, what went wrong, and
which server or application); click a cause and Calls shows only its calls. A
narrowed panel says so in its header, with a ✕ to undo it. The headline numbers never
narrow. The choices are in the page's address, so Back undoes a step and a copied link
opens the same view.

**Live, a number of days, or a custom range.** Live, the default, is the last hour by the
minute. A custom range takes a start and an end, read in the board's time zone, and can
take a while over a long span. Charts cut the range into minutes, quarter-hours, hours
or days by its length. Every panel shows the range, Servers included: over a past range
it shows each server's last state, its peak heap and CPU, its restarts and its most
stuck threads, from the minute samples. A view that can only show now (the connection
pools, the network map, the live event feed) says "live" in its header. Operations in
the past comes from the database: the issues open at some time in the range (down to
back, raised to cleared), each queue's busy minutes, and every operations event.

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

- One domain or all of them, chosen by clicking a domain's heading in Servers. This
  domain's servers and operations are live: runtime MBeans and the event bus, every 5
  seconds. Another
  domain's servers come from the minute samples its own dashboard writes to the
  shared analytics database, so a domain shows here only if it runs the dashboard.
- Reports over days are at most five minutes old, so a wall of open browsers costs the
  database one query per report every five minutes; Live's are at most 15 seconds old.
- Thresholds and refresh intervals are in the dashboard's settings, in the
  Configurator.
- Below 1000 × 640 the board stacks into a scrolling page.
- Times are the viewer's: the browser's zone unless another is picked beside the
  clock. The data is UTC; days and hours are cut in the chosen zone.

**Every problem opens the app that fixes it.** A dropped call opens Trace at that call; a
configuration change, the Configurator at its application; a remote cut off or a trunk failing,
Logs on that server at that minute with the name searched for; a server's name, its server log;
its stuck threads, the container's stuck-thread warnings (the ↗ beside a server's name opens
its log; the name itself narrows the board); the failed and dropped calls, their traces in
Trace. Each app says what it opens in an `opens.json` beside its pages, and the
board finds the apps from the portal's card list: an app not deployed gives no link, and a new
app that declares what it opens is linked without a change here. Links open in one window per
app. An app that can show itself embedded (Logs, the Configurator) also gets a **peek** button
that opens it in a drawer beside the board. An application can raise a problem of its own with
the page that fixes it (`Problems.raise` in the framework); it shows under Issues until cleared.

**What changed.** Operations has a Changes tab, newest first: one row a day, or one a minute
over a day or less, saying which servers and applications restarted and which configuration
files were pushed or saved. Hover a row for every change with its time. Restarts come from the
minute samples; configuration changes from `v_config_changes`, and the operations history from
`v_ops_events`. Both need the analytics service on the framework that persists those events.

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
