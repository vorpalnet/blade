package org.vorpal.blade.framework.v3.configuration;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletException;
import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipServletContextEvent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.victools.jsonschema.generator.CustomDefinition;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;

/// v3 configuration manager — a thin, friendlier face over the v2
/// [org.vorpal.blade.framework.v2.config.SettingsManager], adding three
/// ergonomic enhancements while inheriting all of v2's load/merge/JMX/schema
/// machinery through the existing seams (`build`, `configureMapper`,
/// `createSettings`).
///
/// ## 1. No `Class<T>` token
/// v2 forces the redundant
/// `new SettingsManager<Foo>(event, Foo.class, new FooSample())`. v3 recovers
/// `T` reflectively (super-type-token), so the config class is named exactly
/// once — in the `extends` clause. This requires a subclass that binds a
/// concrete type (the pattern already used everywhere, e.g.
/// `class FooManager extends SettingsManager<FooConfig>`), or an anonymous
/// `new SettingsManager<FooConfig>(event){}`. A raw instantiation that leaves
/// `T` unresolvable fails fast with a clear message.
///
/// ## 2. Sample via a hook, not a constructor argument
/// Override [#sample] to supply the first-run seed config instead of threading
/// a `sample` argument through the constructor. Returning `null` (the default)
/// seeds from the loaded/empty config, exactly as v2 does.
///
/// ## 3. Config files are decoupled from the app
/// A config file has a **name of its own**. It *happens* to default to the
/// application name, but any number of additional config files may be managed
/// under arbitrary names — each gets its own `.jschema`, sample, JMX MBean and
/// reload lifecycle (the Configurator and the JMX APIs operate on *config
/// files*, not on the app). Use the `(event, configName)` constructors to
/// manage a named config distinct from the app's own.
///
/// Typical use:
/// ```java
/// public class FooManager extends org.vorpal.blade.framework.v3.configuration.SettingsManager<FooConfig> {
///     public FooManager(SipServletContextEvent event) throws ServletException, IOException { super(event); }
///     @Override protected FooConfig sample() { return new FooConfigSample(); }
/// }
/// // ...
/// settings = new FooManager(event);                 // config file == app name
/// extra    = new FooManager(event, "routing-rules"); // a second, independently-named config file
/// ```
///
/// ## Optional: [#onRefresh] — react to every config (re)load
/// Override [#onRefresh] when a config file needs manipulation before it is
/// usable — e.g. upgrading older config-file versions to the current shape. It
/// runs on the initial load **and on every subsequent change** pushed through
/// the Configurator/JMX, with [org.vorpal.blade.framework.v2.config.SettingsManager#getCurrent]
/// already returning the new config. (This replaces v2's misleadingly-named
/// `initialize`, which fired on every reload despite the name — the reason
/// almost no one used it. `onRefresh` is the v2 hook, renamed and made
/// discoverable; leaving it un-overridden is fine.)
public class SettingsManager<T> extends org.vorpal.blade.framework.v2.config.SettingsManager<T> {

	/// No-arg constructor for subclasses that set their own fields before
	/// driving [org.vorpal.blade.framework.v2.config.SettingsManager#build]
	/// themselves.
	public SettingsManager() {
		super();
	}

	/// Manage the application's own config file (config name == application name).
	public SettingsManager(SipServletContextEvent event) throws ServletException, IOException {
		super();
		initContext(event.getServletContext());
		this.sample = sample();
		this.build(applicationName, resolveConfigClass(), null);
	}

	/// Manage the application's own config file (config name == application name).
	public SettingsManager(ServletContextEvent event) throws ServletException, IOException {
		super();
		initContext(event.getServletContext());
		this.sample = sample();
		this.build(applicationName, resolveConfigClass(), null);
	}

	/// Manage a config file under an explicit name, decoupled from the app —
	/// the basis for multiple config files per application.
	public SettingsManager(SipServletContextEvent event, String configName) throws ServletException, IOException {
		super();
		initContext(event.getServletContext());
		this.sample = sample();
		this.build(configName, resolveConfigClass(), null);
	}

	/// Manage a config file under an explicit name, decoupled from the app —
	/// the basis for multiple config files per application.
	public SettingsManager(ServletContextEvent event, String configName) throws ServletException, IOException {
		super();
		initContext(event.getServletContext());
		this.sample = sample();
		this.build(configName, resolveConfigClass(), null);
	}

	/// Registers the v2 (de)serializers (via `super`) plus the v3 [Resolvable]
	/// types — so any config field typed `ResolvableSipUri` / `ResolvableSipAddress`
	/// / `ResolvableHttpAddress` round-trips as a plain string and resolves its
	/// `${var}` template at runtime. v2 never names these types; they enter only
	/// through this v3 override.
	@Override
	protected void configureMapper(ObjectMapper mapper) {
		super.configureMapper(mapper);

		SimpleModule module = new SimpleModule();
		module.addSerializer(new ResolvableSerializer()); // base type covers all subclasses
		module.addDeserializer(ResolvableSipUri.class,
				new ResolvableDeserializer<>(ResolvableSipUri.class, ResolvableSipUri::new));
		module.addDeserializer(ResolvableSipAddress.class,
				new ResolvableDeserializer<>(ResolvableSipAddress.class, ResolvableSipAddress::new));
		module.addDeserializer(ResolvableHttpAddress.class,
				new ResolvableDeserializer<>(ResolvableHttpAddress.class, ResolvableHttpAddress::new));
		mapper.registerModule(module);
	}

	/// Renders v3 [Resolvable] config fields as plain string inputs in the
	/// Configurator. Without this, victools would introspect the concrete
	/// `Resolvable` subclass and emit an object sub-form (its `template`
	/// property), mismatching the plain-string JSON on disk. Each subclass maps
	/// to `{"type":"string","format":...}`. v2 never sees these types.
	@Override
	protected void configureSchema(SchemaGeneratorConfigBuilder configBuilder) {
		configBuilder.forTypesInGeneral().withCustomDefinitionProvider((javaType, context) -> {
			Class<?> erased = javaType.getErasedType();
			if (!Resolvable.class.isAssignableFrom(erased) || erased == Resolvable.class) {
				return null;
			}
			ObjectNode node = context.getGeneratorConfig().createObjectNode();
			node.put("type", "string");
			node.put("format", schemaFormat(erased));
			return new CustomDefinition(node, CustomDefinition.DefinitionType.INLINE,
					CustomDefinition.AttributeInclusion.NO);
		});
	}

	private static String schemaFormat(Class<?> resolvableType) {
		if (resolvableType == ResolvableSipUri.class) {
			return "sip-uri";
		}
		if (resolvableType == ResolvableSipAddress.class) {
			return "sip-address";
		}
		if (resolvableType == ResolvableHttpAddress.class) {
			return "uri";
		}
		return "string";
	}

	/// First-run seed config. Override to supply a populated example; the
	/// default returns `null`, which seeds from the loaded/empty config.
	protected T sample() {
		return null;
	}

	/// Called on every config (re)load — initial load and every change pushed
	/// through the Configurator/JMX. Override only when the config file needs
	/// manipulation before use (e.g. upgrading an older config-file version to
	/// the current shape). [org.vorpal.blade.framework.v2.config.SettingsManager#getCurrent]
	/// already returns the new config when this runs. Default is a no-op.
	///
	/// @param config the freshly-loaded configuration
	protected void onRefresh(T config) throws ServletParseException {
		// no-op; override to manipulate config after (re)load
	}

	/// Routes the v2 reload callback (the misleadingly-named `initialize`,
	/// invoked from `Settings.reload()` on every load) to the clearer
	/// [#onRefresh] hook. Override `onRefresh`, not this.
	@Override
	public void initialize(T config) throws ServletParseException {
		onRefresh(config);
	}

	/// The identity of the config file this manager owns — independent of the
	/// application. Equals the application name unless an explicit `configName`
	/// was supplied.
	public String getConfigName() {
		return this.servletContextName;
	}

	/// Resolves the concrete `Class<T>` from the runtime type hierarchy
	/// (super-type-token), removing the need to pass a `Class<T>` argument.
	/// Walks superclasses looking for the first parameterization bound to a
	/// concrete class; throws if `T` cannot be determined.
	@SuppressWarnings("unchecked")
	protected Class<T> resolveConfigClass() {
		Class<?> node = getClass();
		while (node != null && node != Object.class) {
			Type generic = node.getGenericSuperclass();
			if (generic instanceof ParameterizedType) {
				Type arg = ((ParameterizedType) generic).getActualTypeArguments()[0];
				if (arg instanceof Class) {
					return (Class<T>) arg;
				}
			}
			node = node.getSuperclass();
		}
		throw new IllegalStateException("Cannot resolve the configuration type for " + getClass().getName()
				+ ". Subclass with a concrete type — e.g. "
				+ "`class FooManager extends org.vorpal.blade.framework.v3.configuration.SettingsManager<FooConfig>` — "
				+ "or instantiate as `new SettingsManager<FooConfig>(event){}`.");
	}
}
