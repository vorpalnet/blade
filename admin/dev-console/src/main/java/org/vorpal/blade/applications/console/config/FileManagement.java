package org.vorpal.blade.applications.console.config;

import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.nio.file.Paths;

import javax.ws.rs.Consumes;
import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import org.vorpal.blade.applications.console.mxgraph.Formatter;

@Path("/microservices")
public class FileManagement {

	@GET
	@Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
	// @Path("/")
	public String listFiles() {
		String str = "";

		File f = new File("./config/custom/microservices");
		for (String filename : f.list()) {
			str += "<a href=\"#\" onclick=\"aliceUtils.loadFile(this);\">" + filename + "</a><br/>";
		}

		return str;
	}

	@POST
	@Path("/{filename}")
	@Consumes({ MediaType.APPLICATION_FORM_URLENCODED })
	public void saveFile(@PathParam("filename") String filename, @FormParam("xml") String xml) {
		String decodedXml;

		try {
			// decodedXml = URLDecoder.decode(xml, "UTF-8").replace("\n", "&#xa;");
			decodedXml = URLDecoder.decode(xml, "UTF-8");

//			System.out.println("filename: " + filename);
//			System.out.println(decodedXml);
//			System.out.println(Formatter.xmlPrettyPrint(decodedXml));

			Formatter.xmlSaveToFile("./config/custom/microservices/" + filename, decodedXml);

		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

	}

	@GET
	@Produces({ MediaType.APPLICATION_FORM_URLENCODED })
	@Path("/{filename}")
	public String openFile(@PathParam("filename") String filename) {

		String filePath = "./config/custom/microservices/" + filename;

		String content = "";
		String encodedXml = "";
		try {
			content = new String(Files.readAllBytes(Paths.get(filePath)));
//			encodedXml = "xml=" + URLEncoder.encode(content, "UTF-8").replace("&#xa;", "\n");
//			encodedXml = URLEncoder.encode(content, "UTF-8").replace("&#xa;", "\n");
//			encodedXml = URLEncoder.encode(content, "UTF-8");
			encodedXml = content.replace("\n", "");

		} catch (IOException e) {
			e.printStackTrace();
		}

		return encodedXml;
	}

}
