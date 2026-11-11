/// Pushing `_templates/` files to every server, as configuration files are
/// pushed. [TemplatesControl] is the engine side, in every BLADE application;
/// the Configurator pushes a saved template to one per server and skips a
/// server already holding the same bytes (a shared disk). Connectors read
/// templates through `TemplateCache`, so a pushed template is used within a
/// second, without a reload.
package org.vorpal.blade.framework.v3.configuration.templates;
