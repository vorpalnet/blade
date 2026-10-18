# Call Catalog

`blade-catalog.war` makes recorded calls searchable. When a conversation
closes, the catalog reads its manifest and transcript back from the recording
archive and writes rows a reviewer can search: who called whom and when, the
call's attributes, every utterance (redacted), and labels. The recordings
review application's search runs on these rows.

The archive is the record and the catalog is a cache of it. Nothing is written
here that cannot be derived again by reading the archive, so a lost or damaged
catalog is rebuilt, not restored.

## How it is fed

The catalog holds a durable subscription, `blade-catalog`, to the
`conversation.closed` event on the BLADE event bus. Durable means a catalog that
was down while calls closed catches up when it returns. Each event is indexed by
replacing the conversation's rows, so a redelivered event changes nothing.

On start it can also re-index the last few days straight from the archive
(`rebuildDays`), which is how a new or emptied catalog is filled.

## What it needs

- **A recording archive reader.** The public WAR reads the archive only through
  the framework's archive interfaces and ships no reader of its own; a
  deployment build adds one for its storage. Without one the catalog starts
  idle: it logs a warning, does not subscribe, and indexes nothing, so the
  events wait on the bus instead of being consumed for nothing.
- **The analytics database** through `jdbc/BladeAnalytics`, targeted at the
  engines and the AdminServer (the review application reads the same tables
  from the AdminServer). Oracle, MySQL and SQL Server all work. Its tables,
  `BLADE_CONVERSATION`, `BLADE_CONVERSATION_ATTR`, `BLADE_UTTERANCE`,
  `BLADE_PROTECTED` and `BLADE_LABEL`, are part of the analytics schema, so
  `./upgrade.sh <env>` makes them. The catalog itself makes only the optional
  full-text index.
- **Events turned on** in its configuration, `blade-catalog.json`.

Deploy it to the engine cluster. Context root `/blade/catalog`; it serves no
pages.

## Settings

Edited in the Configurator under `blade-catalog`:

| setting | what it does |
|---------|--------------|
| `dataSource` | the JNDI name it writes to; default `jdbc/BladeAnalytics` |
| `fullText` | index utterance text for word search: Oracle Text on Oracle, FULLTEXT on MySQL; SQL Server searches by scan |
| `protectedValueKey` | key for the hash of each protected value the redactor found (card, member id, phone), so a reviewer with the right permission can search for an exact value the catalog never stores; empty turns it off |
| `rebuildDays` | on start, re-index this many days from the archive, in the background |

## Status

An incubator application (`proto/`). It runs on the Ashburn rig behind the
recordings review application. Not built yet: a retention reaper for its rows,
and the machine writer of labels (today only the agent console writes them).
