package org.vorpal.blade.applications.console.config;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.vorpal.blade.applications.console.config.test.ConfigHelper;

@WebServlet("/saveData")
public class SaveDataServlet extends HttpServlet {

	public void doPost(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {
		res.setContentType("text/html");// setting the content type

		String app = req.getParameter("app");
		String configType = req.getParameter("configType");
		String data = req.getParameter("data");

		System.out.println("app = " + app);
		System.out.println("configType = " + configType);
		System.out.println("data = " + data);

		ConfigHelper configHelper = new ConfigHelper(app);

		Path path = configHelper.getPath(configType);
		configHelper.saveFileLocally(configType, data);

		PrintWriter pw = res.getWriter();// get the stream to write the data
		pw.println("<html><body>");
		pw.println("</body></html>");
		pw.close();// closing the stream
	}
}