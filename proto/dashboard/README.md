# Dashboard

One page for OCCAS and BLADE: live cluster health, live operations, and call
analytics with a report behind every number. It replaces what the WebLogic Console
showed before the Remote Console, and the call reports a BI tool would otherwise
need.

`blade-dashboard.war`, context root `/blade/dashboard`, deployed to the AdminServer.
It is a `proto/` application: built and shipped loose in `dist/proto/`, not bundled
in an EAR.

## What it shows

**Overview** (`/blade/dashboard/`)

- **Cluster health**: every server's state, heap, threads, JDBC pools and SIP
  sessions, read from the runtime MBeans every 5 seconds, with a last-hour trend
  per cluster.
- **Operations**: endpoints down, trunks failing, queue depth and recent operations
  events, from the event bus every 5 seconds.
- **Call analytics**: one card per report with its headline number and a trend.
  Cards read data at most five minutes old, so a wall of open browsers costs the
  database one query per report every five minutes.

**Reports** (`/blade/dashboard/report.html?r=<report>`): click a card for the full
report, queried live, with a date range, a cluster selector and an "Include sample
data" switch. The settings stay in the address bar, so a report can be bookmarked or
sent to a colleague.

| Report | Answers |
| --- | --- |
| Call volume | calls per day and hour, hour × weekday, by application, engine and tenant, peak calls up at once |
| Call duration | average, median and longest; calls by length; duration per application |
| Call outcomes | started, answered, completed and lost calls; answer rate per application |
| Open sessions | sessions that never closed, whenever they started |
| Platform activity | application starts and stops, versions that handled calls |
| Event activity | every recorded event, by type and application |
| Call risk | assessments, bands, triggering signals, what each signal contributed |
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
