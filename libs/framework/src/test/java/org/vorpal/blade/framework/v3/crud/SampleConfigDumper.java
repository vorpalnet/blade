package org.vorpal.blade.framework.v3.crud;

import java.io.File;

import com.fasterxml.jackson.databind.ObjectMapper;

/// Generates `services/crud/config/crud.SAMPLE` from [CrudConfigurationSample].
/// Run after editing the sample to keep the checked-in config file in sync
/// with the Java source. Unlike [ExamplesSmokeTest] this writes to disk;
/// it's not a test.
public final class SampleConfigDumper {
	public static void main(String[] args) throws Exception {
		String path = (args.length > 0) ? args[0] : "config/crud.SAMPLE";
		File out = new File(path);
		File parent = out.getParentFile();
		if (parent != null) parent.mkdirs();

		ObjectMapper mapper = new ObjectMapper();
		mapper.writerWithDefaultPrettyPrinter()
				.writeValue(out, new CrudConfigurationSample());
		System.out.println("wrote " + out.getAbsolutePath());
	}
}
