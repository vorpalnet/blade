/**
 *  MIT License
 *  
 *  Copyright (c) 2021 Vorpal Networks, LLC
 *  
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *  
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *  
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */
package org.vorpal.blade.framework.v2.config;

import javax.management.MXBean;

/**
 * @author Jeff McDonald
 *
 */
/**
 * JMX MXBean interface for managing configuration settings at runtime.
 */
@MXBean
public interface SettingsMXBean {

	public long getLastModified(String configType);

	public void openForWrite(String configType);

	public void openForRead(String configType);

	public String read();

	public void write(String line);

	public void close();

	public void reload();

	/// Returns the current in-memory configuration as a JSON string,
	/// or null if it hasn't been loaded yet. Side-effect-free — does not
	/// touch the file system, doesn't change MBean state, safe to call
	/// concurrently with reads/writes. Use this when you only need to
	/// inspect the current settings (e.g. the BLADE Admin Portal reading
	/// each app's administrator notes or other live values).
	public String getCurrentJson();

	/// Returns this app's generated JSON Schema as a string, or null if it
	/// can't be produced. The schema is static (derived from the config class,
	/// not the data), so this is side-effect-free and safe to call anytime.
	/// The BLADE Admin Portal reads the schema-root `title` / `x-tagline` /
	/// `description` (emitted from [SchemaAbout]) to build each launcher card —
	/// developer-owned identity that an operator config save can't blank.
	public String getSchemaJson();
}
