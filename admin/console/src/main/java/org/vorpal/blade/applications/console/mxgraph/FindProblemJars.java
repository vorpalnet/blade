package org.vorpal.blade.applications.console.mxgraph;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;

public class FindProblemJars {

	public FindProblemJars() {
		try {
			Enumeration e = getClass().getClassLoader().getResources("org/w3c/dom/Document.class");

			Collections.list(e).forEach(System.out::println);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

	}

	public static void main(String[] args) {
		// TODO Auto-generated method stub

		new FindProblemJars();

	}

}
