package org.vorpal.blade.framework.v3.jdbc;

import java.nio.file.Files;
import java.nio.file.Path;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/// A WebLogic domain's JDBC data source as its own files describe it: the URL,
/// the user, and the password it keeps encrypted.
///
/// [SchemaUpgrade] connects with this, so the database it upgrades is the one
/// the domain's applications write to, and no second copy of the URL or the
/// password exists to drift from it. The password is decrypted with the
/// domain's own encryption service (`security/SerializedSystemIni.dat`), which
/// is on the classpath wherever `weblogic.jar` is: on the admin host, run by an
/// operator who can already read the domain.
public final class DomainDataSource {

	public final String name;
	public final String url;
	public final String user;
	private final String encryptedPassword;

	private DomainDataSource(String name, String url, String user, String encryptedPassword) {
		this.name = name;
		this.url = url;
		this.user = user;
		this.encryptedPassword = encryptedPassword;
	}

	/// The data source bound at this JNDI name, or null when the domain has none.
	public static DomainDataSource find(Path domain, String jndiName) throws Exception {
		Path config = domain.resolve("config");
		Document domainXml = parse(config.resolve("config.xml"));
		NodeList resources = domainXml.getElementsByTagName("jdbc-system-resource");
		for (int i = 0; i < resources.getLength(); i++) {
			Element r = (Element) resources.item(i);
			String file = text(r, "descriptor-file-name");
			if (file == null || !Files.exists(config.resolve(file))) {
				continue;
			}
			Document ds = parse(config.resolve(file));
			NodeList jndi = ds.getElementsByTagName("jndi-name");
			for (int j = 0; j < jndi.getLength(); j++) {
				if (jndiName.equals(jndi.item(j).getTextContent().trim())) {
					Element root = ds.getDocumentElement();
					return new DomainDataSource(text(root, "name"), text(root, "url"), property(ds, "user"),
							text(root, "password-encrypted"));
				}
			}
		}
		return null;
	}

	/// The password in the clear, decrypted with the domain's encryption service.
	public String password(Path domain) throws Exception {
		if (encryptedPassword == null || !encryptedPassword.startsWith("{")) {
			return encryptedPassword;
		}
		Class<?> ini = Class.forName("weblogic.security.internal.SerializedSystemIni");
		Class<?> service = Class.forName("weblogic.security.internal.encryption.EncryptionService");
		Class<?> clearOrEncrypted = Class.forName("weblogic.security.internal.encryption.ClearOrEncryptedService");
		Object es = null;
		for (Path where : new Path[] { domain, domain.resolve("security") }) {
			try {
				es = ini.getMethod("getEncryptionService", String.class).invoke(null, where.toAbsolutePath().toString());
				if (es != null) {
					break;
				}
			} catch (java.lang.reflect.InvocationTargetException notHere) {
				// the other spelling of the directory
			}
		}
		if (es == null) {
			throw new IllegalStateException("cannot open the domain's encryption service under " + domain);
		}
		Object decrypter = clearOrEncrypted.getConstructor(service).newInstance(es);
		return (String) clearOrEncrypted.getMethod("decrypt", String.class).invoke(decrypter, encryptedPassword);
	}

	/// Register the drivers WebLogic ships that do not register themselves.
	public static void loadDrivers() {
		for (String driver : new String[] { "oracle.jdbc.OracleDriver", "com.mysql.cj.jdbc.Driver",
				"weblogic.jdbc.sqlserver.SQLServerDriver", "com.microsoft.sqlserver.jdbc.SQLServerDriver" }) {
			try {
				Class.forName(driver);
			} catch (Throwable notOnThisClasspath) {
				// another database's driver
			}
		}
	}

	private static String property(Document ds, String name) {
		NodeList props = ds.getElementsByTagName("property");
		for (int i = 0; i < props.getLength(); i++) {
			Element p = (Element) props.item(i);
			if (name.equals(text(p, "name"))) {
				return text(p, "value");
			}
		}
		return null;
	}

	/// The text of the first descendant with this tag.
	private static String text(Element e, String tag) {
		NodeList n = e.getElementsByTagName(tag);
		return n.getLength() == 0 ? null : n.item(0).getTextContent().trim();
	}

	private static Document parse(Path file) throws Exception {
		DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
		f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
		return f.newDocumentBuilder().parse(file.toFile());
	}
}
