package org.vorpal.blade.services.proxy.block;

import java.io.IOException;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletException;
import javax.servlet.sip.ServletParseException;

import org.vorpal.blade.framework.v2.config.SettingsManager;
import org.vorpal.blade.services.proxy.block.optimized.OptimizedBlockConfig;
import org.vorpal.blade.services.proxy.block.optimized.OptimizedTranslation;
import org.vorpal.blade.services.proxy.block.simple.SimpleBlockConfig;
import org.vorpal.blade.services.proxy.block.simple.SimpleBlockConfigSample;
import org.vorpal.blade.services.proxy.block.simple.SimpleTranslation;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

public class BlockSettingsManager extends SettingsManager<SimpleBlockConfig> {

	public OptimizedBlockConfig optimizedConfig;

	public BlockSettingsManager(ServletContextEvent event, SimpleBlockConfig sampleConfig)
			throws ServletException, IOException {
		super(event, SimpleBlockConfig.class, sampleConfig);
	}

	public OptimizedBlockConfig getOptimizedConfig() {
		return optimizedConfig;
	}

	public void setOptimizedConfig(OptimizedBlockConfig optimizedConfig) {
		this.optimizedConfig = optimizedConfig;
	}

	public static OptimizedBlockConfig initializeBlock(SimpleBlockConfig simpleConfig) {
		OptimizedBlockConfig tmpOptimizedConfig = new OptimizedBlockConfig();

		tmpOptimizedConfig.setFromSelector(simpleConfig.getFromSelector());
		tmpOptimizedConfig.setToSelector(simpleConfig.getToSelector());
		tmpOptimizedConfig.setRuriSelector(simpleConfig.getRuriSelector());
		tmpOptimizedConfig.setDefaultRoute(new OptimizedTranslation(simpleConfig.getDefaultRoute()));

		for (SimpleTranslation simpleTranslation : simpleConfig.getCallingNumbers()) {
			tmpOptimizedConfig.callingNumbers.put(simpleTranslation.id, new OptimizedTranslation(simpleTranslation));
		}

		return tmpOptimizedConfig;
	}

	@Override
	public void initialize(SimpleBlockConfig simpleConfig) throws ServletParseException {

		this.optimizedConfig = initializeBlock(simpleConfig);

	}

	public static void main(String[] args) throws JsonProcessingException {

		SimpleBlockConfig sbc = new SimpleBlockConfigSample();
		OptimizedBlockConfig obc = initializeBlock(sbc);

		ObjectMapper mapper = new ObjectMapper();
		mapper.setSerializationInclusion(Include.NON_NULL);
		mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
		String output = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(obc);

		System.out.println("Printing OptimizedBlockConfig...");
		System.out.println(output);

		// TODO Auto-generated method stub

	}

}
