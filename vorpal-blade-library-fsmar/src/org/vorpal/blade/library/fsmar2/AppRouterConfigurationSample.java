/**
 *  MIT License
 *  
 *  Copyright (c) 2013, 2022 Vorpal Networks, LLC
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

package org.vorpal.blade.library.fsmar2;

import java.io.Serializable;

import org.vorpal.blade.framework.logging.LogParameters.LoggingLevel;
import org.vorpal.blade.framework.logging.LogParametersDefault;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

//public class Configuration extends HashMap<String, State> implements Serializable {
public class AppRouterConfigurationSample extends AppRouterConfiguration implements Serializable {

	private static final long serialVersionUID = -7178380203877638527L;

	/**
	 * Creates a default configuration used to generate the FSMAR2.SAMPLE file.
	 */
	public AppRouterConfigurationSample() {
		this.logging = new LogParametersDefault();
		this.logging.setLoggingLevel(LoggingLevel.WARNING);
		
		

		this.setDefaultApplication("b2bua");

		this.getPrevious("null").getTrigger("REGISTER").createTransition("proxy-registrar");
		this.getPrevious("null").getTrigger("SUBSCRIBE").createTransition("presence");
		this.getPrevious("null").getTrigger("PUBLISH").createTransition("presence");
		this.getPrevious("null").getTrigger("OPTIONS").createTransition("options");
		this.getPrevious("null").getTrigger("INVITE").createTransition("keep-alive").setId("INV-1")
				.setOriginating("From");

		Transition t1 = this.getPrevious("keep-alive").getTrigger("INVITE").createTransition("b2bua").setId("INV-2");
		t1.addComparison("Directive", "equals", "CONTINUE");
		t1.addComparison("Region", "equals", "ORIGINATING");
		t1.addComparison("Region-Label", "equals", "ORIGINATING");
		t1.addComparison("Request-URI", "uri", "^(sips?):([^@]+)(?:@(.+))?$");
		t1.addComparison("From", "address", "^.*<(sips?):([^@]+)(?:@(.+))?>.*$");
		t1.addComparison("To", "user", "bob");
		t1.addComparison("To", "host", "vorpal.net");
		t1.addComparison("To", "equals", "<sip:bob@vorpal.net>");
		t1.addComparison("Allow", "contains", "INV");
		t1.addComparison("Allow", "includes", "INVITE");
		t1.addComparison("Session-Expires", "value", "3600");
		t1.addComparison("Session-Expires", "refresher", "uac");
		t1.setOriginating("From");
		t1.setRoute(new String[] { "sip:proxy1", "sip:proxy2" });

		this.getPrevious("keep-alive").getTrigger("INVITE").createTransition("b2bua").setId("INV-3");
		this.getPrevious("b2bua").getTrigger("INVITE").createTransition("proxy-registrar");

	}

	public static void main(String[] args) throws JsonProcessingException {

		AppRouterConfigurationSample configuration = new AppRouterConfigurationSample();

		ObjectMapper mapper = new ObjectMapper();
		mapper.setSerializationInclusion(Include.NON_NULL);
		mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

		String output = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(configuration);

		System.out.println(output);

	}

}
