package net.phpdr.flume;

import java.io.PrintWriter;
import java.io.StringWriter;

public class Util {
	public static String e2s(Throwable e) {
		StringWriter sw = new StringWriter();
		e.printStackTrace(new PrintWriter(sw));
		return sw.toString();
	}
}
