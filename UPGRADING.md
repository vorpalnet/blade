# Upgrading BLADE on a running domain

`upgrade.sh` takes a domain from the BLADE it runs to the BLADE checked out
beside it, in one command:

```
git pull
./upgrade.sh <env>
```

It is one of five verbs: `install.sh` (the server), `build.sh` (the artifacts),
`deploy.sh` (the push), `patch.sh` (the OCCAS binaries) and this one. Run it on
the admin host, where `install.sh` and `deploy.sh` run.

## It upgrades what is there, and adds nothing

An application is redeployed only when it is already deployed and this build
holds a changed copy of it, on the targets it has now and in the shape it has
now: an application deployed as a loose WAR stays a loose WAR, and a tier
deployed as its EAR stays an EAR. Nothing new is installed, nothing is
undeployed, and nothing is restarted.

"Changed" is decided by content, not by bytes. Two builds of the same code
differ in every timestamp inside the archive, so `upgrade.sh` compares a hash
of each entry's name and content, nested jars included, against the copy the
domain deployed. An unchanged application is left alone. That matters beyond
speed: every redeploy loads an application's classes again, and an engine's
metaspace grows until it restarts.

Every step can be run again. A run that stops halfway is finished by running
it again.

## The steps

| step | what it does |
|------|--------------|
| 1. preflight | finds the profile, the domain and the checkout; warns on uncommitted changes |
| 2. build | `./build.sh` (`--prod` for a release build; `--skip-build` to use `dist/` as it is) |
| 3. resources | reconciles the JMS event bus and the analytics data source (`install.sh`'s `jms` and `datasource`) |
| 4. database | brings the analytics database current; see below |
| 5. library | deploys `blade-shared` when this build's version is not deployed, beside the earlier one |
| 6. approuter | replaces `blade-fsmar.jar` when it differs from the one installed |
| 7. apps | redeploys every deployed application this build has a changed copy of |
| 8. report | what changed, what was left alone, and what waits for a restart |

A new application router is read at server start, so step 6 ends with the
engine restart it needs listed, not done. The earlier `blade-shared` version
stays deployed until you undeploy it; applications pick up the newest on their
next deploy.

## The analytics database

Step 4 connects with the domain's own `jdbc/BladeAnalytics` data source, so it
upgrades the database the applications write to, with no second copy of the
URL or password. It works on Oracle, MySQL and SQL Server:

- **An empty database** gets the whole schema
  (`services/analytics/sql/<Dialect>-database-schema.sql`).
- **An existing one** gets each change it has not had, in number order, from
  `services/analytics/sql/migrations/`. A table `blade_schema_migrations` in the
  database records what has been applied. A database built by hand before that
  table existed is recognized: a change already in place is recorded, not run
  again.
- **The reporting views** are replaced whenever their script changed.

A domain with no analytics data source skips the step.

### Before it changes anything

The step looks first. When the database needs a change, it lists the changes
and warns before making them: they go to the live database with the data
source's login, `upgrade.sh` has no undo, so take a backup first, and a new
column or index on a large table can hold up writes for a while (events wait on
the JMS topic meanwhile). Then it asks. Answer no and the run stops with nothing
changed and nothing deployed.

With no terminal to ask, as in a scheduled job, the run stops there unless it
was started with `--yes`.

### When a DBA makes every change

Applying the changes from `upgrade.sh` is optional. Where the application's
database login may not change the schema, or every change goes through a DBA,
write the step out instead:

```
./upgrade.sh <env> --sql upgrade-2026-10.sql
```

The file holds exactly what this database needs, in its own syntax (`GO`
batches for `sqlcmd` or SSMS on SQL Server, `/` after a PL/SQL block for SQL*Plus
on Oracle), including the rows that record it. Nothing is changed and nothing is
deployed. Once the DBA has run it, run `./upgrade.sh <env>` again: it finds the
database current and carries on.

## Looking before leaping

```
./upgrade.sh <env> --dry-run
```

walks every step and says what it would do, the database changes included,
without changing anything. It does not build, so it judges the build already
in `dist/`.

## Adding a change to the analytics tables

A developer who changes the tables writes it three times, once per database:
`migrations/<NNN>-<name>.Oracle.sql`, `.MySQL.sql` and `.MSSQL.sql`, with the
next number, and makes the same change in each `<Dialect>-database-schema.sql`,
so an empty database built from it already has it. The analytics tests refuse
a number that is missing for one database. The javadoc of `SchemaUpgrade` has
the rules for `applied-if`, the header that lets an older database be
recognized.
