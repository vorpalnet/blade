# Admin Configurator Module

The admin configurator module provides configuration management capabilities for the Vorpal Blade platform. This module enables dynamic configuration of services, console applications, and framework components through web-based interfaces and programmatic APIs.

## Overview

The configurator module serves as the central configuration hub for the Vorpal Blade ecosystem, offering:

- Web-based configuration interfaces for administrative tasks
- Dynamic service configuration management
- Framework-level configuration support
- Integration with console applications
- Test utilities for configuration validation

## Command-Line Validation & REST API

The Configurator exposes a REST API that validates every app's JSON config
against its schema and, optionally, deploys it to the cluster (propagate via
JMX + reload). It is the same check the browser Editor runs before Publish,
available for CI pipelines and scripted rollouts.

Endpoints live under the context root `/blade/configurator/api/v1/`:

| Method & path | Does |
|---|---|
| `GET  /api/v1/validate` | Validate all discovered apps against their `.jschema` |
| `GET  /api/v1/validate/{app}` | Validate one app |
| `POST /api/v1/deploy` | Validate all; if all pass, propagate via JMX and reload |
| `POST /api/v1/deploy/{app}` | Validate, then deploy one app |
| `POST /api/v1/publish/{app}` | Push one app to the cluster and reload (no validate gate) |

**Authentication is HTTP Basic, not the login form.** The browser Editor uses
FORM login + the shared admin-tier session cookie. Command-line clients can't
drive a form, so `/api/v1/*` is carved out of the FORM security-constraint and
authenticated by an in-WAR `BasicAuthFilter`, which validates the
`Authorization: Basic` header against the same WebLogic realm and requires one
of the admin roles (`Admin`/`Operator`/`Deployer`/`Monitor`). A request without
valid credentials gets `401`, not the login page. This is the sanctioned
exception to the admin-tier "never declare BASIC" rule — the WAR still declares
FORM; only the API path is filtered.

```bash
curl -u 'weblogic:secret' \
     http://localhost:7001/blade/configurator/api/v1/validate
```

### `blade-validate.sh`

A wrapper over the API, in this module's root:

```bash
blade-validate.sh --password secret                        # validate all apps
blade-validate.sh --app proxy-registrar --password secret  # validate one app
blade-validate.sh --deploy --password secret               # validate + deploy via JMX
```

Flags: `--deploy`, `--app <name>`, `--host <host:port>` (default `localhost:7001`),
`--user <user>` (default `weblogic`), `--password <pass>` (required),
`--context <path>` (default `blade/configurator`). The script exits non-zero
when any result reports `"valid": false` or `"deployed": false`, so it gates a
CI step directly. Single-quote passwords containing `!`, `$`, etc.

See the [Configurator Handbook → Command-Line Validation](src/main/webapp/docs/cli.html)
for the full reference.

## Controlling Form Layout

The editor generates a form directly from each app's JSON Schema — every
property becomes a labeled field. A config-class author shapes that form with
annotations from `org.vorpal.blade.framework.v2.config`; the
`SettingsManager` schema generator emits them as `x-*` hints the editor reads.
Nothing here is hard-coded per schema — it is all driven by these annotations.

**Default:** a field that is not placed in any group renders on its own
full-width row, stacked top-to-bottom. You only annotate to deviate from that.

### Field-level: `@FormLayout`

Placed on a field or (by convention) its getter, next to
`@JsonPropertyDescription`:

| Flag | Effect |
|---|---|
| `multiline = true` | render a `<textarea>` (implies wide) |
| `password = true`  | masked `<input type="password">` |
| `readOnly = true`  | disabled input — visible but not editable |
| `regexTest = true` | adds a "test" button opening the regex-tester modal |
| `wide = true`      | force a full row — **only meaningful inside a `@FormLayoutGroup`** (see below) |

> **`wide` gotcha:** an ungrouped field is *already* full-width, so `wide=true`
> does nothing there. It only matters for a field inside a group row, where it
> breaks that one field out to its own full row.

### Class-level: `@FormLayoutGroup`

Repeatable, class-level. Two mutually-exclusive forms:

**Flat row** — fields flow left-to-right as compact tiles:

```java
@FormLayoutGroup({ "loggingLevel", "sequenceDiagramLoggingLevel",
                   "configurationLoggingLevel", "analyticsLoggingLevel" })
```

**Row of columns** — set `columns` instead of `value`. Each `@FormLayoutColumn`
is a vertical stack of fields; the columns sit side-by-side, giving a grid of
independently-stacked tiles on one row:

```java
@FormLayoutGroup(columns = {
    @FormLayoutColumn({ "fileSize", "fileCount" }),
    @FormLayoutColumn({ "useParentLogging", "appendFile", "colorsEnabled" })
})
```

renders as:

```text
[ fileSize  ] [ useParentLogging ]
[ fileCount ] [ appendFile       ]
              [ colorsEnabled    ]
```

`value` and `columns` are mutually exclusive (when both are set, `columns`
wins). They emit different schema keys — `x-form-groups` for flat rows,
`x-form-columns` for column rows — so adding a column group never disturbs how
existing flat groups render.

### Class-level: `@FormSection`

A titled, bordered sub-container. Fields listed in a section render inside its
box; fields in no section render at the top level. Groups still apply *inside*
a section — a section field that is also in a `@FormLayoutGroup` renders that
group's row (flat or columns) within the section body.

```java
@FormSection(title = "Connection",     fields = { "url", "method" })
@FormSection(title = "Authentication", fields = { "authentication" })
```

### Ordering

Within a group/columns, fields render in the order listed in the annotation.
Group placement and ungrouped-field order follow the schema's property order,
which is alphabetical unless the class carries `@JsonPropertyOrder` — add that
to put fields (and therefore the rows they anchor) in a deliberate order.

### Schema keys (for reference)

| Annotation | Emitted schema key | Shape |
|---|---|---|
| `@FormLayoutGroup(value=…)` | `x-form-groups` | `[ ["a","b"], … ]` |
| `@FormLayoutGroup(columns=…)` | `x-form-columns` | `[ { "columns": [ ["a","b"], … ] } ]` |
| `@FormSection` | `x-form-sections` | `[ { "title": "…", "fields": ["a"] } ]` |
| `@FormLayout` (per field) | `x-wide` / `format` / `x-readonly` / `x-regex-test` | on the field's own schema node |

See the framework javadocs for `FormLayout`, `FormLayoutGroup`,
`FormLayoutColumn`, and `FormSection` for the authoritative reference.

## Architecture

This module is part of the admin subsystem and works closely with the console module to provide comprehensive administrative capabilities. It bridges the gap between the framework's configuration requirements and the various services that depend on runtime configuration changes.

## Packages

### [`org.vorpal.blade.applications.console.config`](#orgvorpalbladeapplicationsconsoleconfig)

Core configuration management for console applications. Provides APIs for reading, writing, and validating configuration parameters for administrative interfaces.

### [`org.vorpal.blade.applications.console.config.test`](#orgvorpalbladeapplicationsconsoleconfigtest)

Testing utilities and mock implementations for configuration management. Contains unit test helpers and configuration validation tools for console applications.

### [`org.vorpal.blade.applications.console.webapp`](#orgvorpalbladeapplicationsconsolewebapp)

Web application components for configuration management interfaces. Implements servlets, JSP support, and web-based configuration forms for administrative users.

### [`org.vorpal.blade.framework.v2.config`](#orgvorpalbladeframeworkv2config)

Framework-level configuration management for Vorpal Blade v2. Handles core framework configuration, service bootstrapping, and runtime configuration updates.

## Dependencies

### Core Dependencies

- **org.vorpal.blade:vorpal-blade-library-framework** - Core framework library providing base configuration interfaces and utilities
- **org.apache.taglibs:taglibs-standard-impl** - JSP Standard Tag Library implementation for web interface rendering
- **xalan:xalan** - XSLT processor for XML configuration transformations and template processing

## Related Modules

### Core Framework
- [libs/framework](../libs/framework) - Base framework components and interfaces
- [libs/shared/bin](../libs/shared/bin) - Shared binary utilities and helper classes
- [libs/fsmar](../libs/fsmar) - File system management and resource handling

### Administrative Components
- [admin/console](../admin/console) - Administrative console interface and management tools

### Configurable Services
- [services/acl](../services/acl) - Access control list management service
- [services/analytics](../services/analytics) - Analytics and reporting service
- [services/hold](../services/hold) - Call hold management service
- [services/options](../services/options) - SIP OPTIONS handling service
- [services/presence](../services/presence) - Presence and availability service
- [services/proxy-balancer](../services/proxy-balancer) - Load balancing proxy service
- [services/proxy-block](../services/proxy-block) - Request blocking proxy service
- [services/proxy-registrar](../services/proxy-registrar) - SIP registrar proxy service
- [services/proxy-router](../services/proxy-router) - SIP routing proxy service
- [services/queue](../services/queue) - Message queuing service
- [services/tpcc](../services/tpcc) - Third-party call control service
- [services/transfer](../services/transfer) - Call transfer management service

## Integration Guide

### Configuration Management

1. **Service Configuration**: Use the framework v2 config package to manage service-level configurations
2. **Web Interface**: Leverage the console webapp package for browser-based configuration management
3. **Testing**: Utilize the test package utilities for validating configuration changes

### Web Application Integration

The webapp package provides servlet-based configuration interfaces that integrate with the main admin console. JSP pages use the Apache Taglibs for dynamic content rendering and form processing.

### Framework Integration

The module integrates with the core framework through the v2 config package, enabling runtime configuration updates without service restarts. Configuration changes are propagated to dependent services through the framework's event system.

## Build Requirements

- Maven 3.x
- Java 8 or higher
- Access to Vorpal Blade framework libraries

## Usage

This module is typically deployed as part of the larger Vorpal Blade administrative suite. It should be configured alongside the admin console module and requires proper integration with the target services that need configuration management capabilities.