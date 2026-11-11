package org.vorpal.blade.applications.console.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;

/// The page's `IDENTITY_CLASSES` must name exactly the framework classes Jackson
/// reads with `@JsonIdentityInfo`, declared or inherited. Too few and a broken
/// id goes live unchecked; too many and a good file (two v2 `AttributeSelector`s
/// sharing an id) is refused on Save.
class ConfiguratorIdentityTest {

	@Test
	void thePageListsEveryIdentityClass() throws Exception {
		assertEquals(frameworkIdentityClasses(), pageIdentityClasses());
	}

	private static TreeSet<String> pageIdentityClasses() throws IOException {
		String js = new String(Files.readAllBytes(Paths.get("src/main/webapp/configurator.js")), StandardCharsets.UTF_8);
		Matcher block = Pattern.compile("const IDENTITY_CLASSES = new Set\\(\\[(.*?)\\]\\.map\\(n => '([^']+)' \\+ n\\)\\)",
				Pattern.DOTALL).matcher(js);
		assertTrue(block.find(), "IDENTITY_CLASSES not found in configurator.js");
		TreeSet<String> names = new TreeSet<>();
		Matcher name = Pattern.compile("'([A-Za-z0-9]+)'").matcher(block.group(1));
		while (name.find()) names.add(block.group(2) + name.group(1));
		return names;
	}

	private static TreeSet<String> frameworkIdentityClasses() throws IOException, URISyntaxException {
		Path where = Paths.get(org.vorpal.blade.framework.v2.config.Selector.class
				.getProtectionDomain().getCodeSource().getLocation().toURI());
		List<String> classes = new ArrayList<>();
		if (Files.isDirectory(where)) {
			try (Stream<Path> files = Files.walk(where)) {
				files.map(p -> where.relativize(p).toString().replace('\\', '/')).forEach(classes::add);
			}
		} else {
			try (JarFile jar = new JarFile(where.toFile())) {
				jar.stream().forEach(e -> classes.add(e.getName()));
			}
		}
		TreeSet<String> identity = new TreeSet<>();
		ClassLoader loader = ConfiguratorIdentityTest.class.getClassLoader();
		for (String entry : classes) {
			if (!entry.startsWith("org/vorpal/blade/framework/") || !entry.endsWith(".class") || entry.contains("$")) continue;
			String cls = entry.substring(0, entry.length() - 6).replace('/', '.');
			try {
				for (Class<?> c = Class.forName(cls, false, loader); c != null; c = c.getSuperclass()) {
					if (c.isAnnotationPresent(JsonIdentityInfo.class)) { identity.add(cls); break; }
				}
			} catch (Throwable unloadable) {
				// a class needing the container is no config class
			}
		}
		return identity;
	}
}
