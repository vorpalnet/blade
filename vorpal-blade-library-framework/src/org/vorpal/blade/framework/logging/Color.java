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

package org.vorpal.blade.framework.logging;

public class Color {

	public static final String RESET = "\033[0m"; // Text Reset

	public static String BLACK(String str) {
		return "\033[0;30m" + str + RESET;
	}

	public static String RED(String str) {
		return "\033[0;31m" + str + RESET;
	}

	public static String GREEN(String str) {
		return "\033[0;32m" + str + RESET;
	}

	public static String YELLOW(String str) {
		return "\033[0;33m" + str + RESET;
	}

	public static String BLUE(String str) {
		return "\033[0;34m" + str + RESET;
	}

	public static String PURPLE(String str) {
		return "\033[0;35m" + str + RESET;
	}

	public static String CYAN(String str) {
		return "\033[0;36m" + str + RESET;
	}

	public static String WHITE(String str) {
		return "\033[0;37m" + str + RESET;
	}

	public static String BLACK_BOLD(String str) {
		return "\033[1;30m" + str + RESET;
	}

	public static String RED_BOLD(String str) {
		return "\033[1;31m" + str + RESET;
	}

	public static String GREEN_BOLD(String str) {
		return "\033[1;32m" + str + RESET;
	}

	public static String YELLOW_BOLD(String str) {
		return "\033[1;33m" + str + RESET;
	}

	public static String BLUE_BOLD(String str) {
		return "\033[1;34m" + str + RESET;
	}

	public static String PURPLE_BOLD(String str) {
		return "\033[1;35m" + str + RESET;
	}

	public static String CYAN_BOLD(String str) {
		return "\033[1;36m" + str + RESET;
	}

	public static String WHITE_BOLD(String str) {
		return "\033[1;37m" + str + RESET;
	}

	public static String BLACK_UNDERLINED(String str) {
		return "\033[4;30m" + str + RESET;
	}

	public static String RED_UNDERLINED(String str) {
		return "\033[4;31m" + str + RESET;
	}

	public static String GREEN_UNDERLINED(String str) {
		return "\033[4;32m" + str + RESET;
	}

	public static String YELLOW_UNDERLINED(String str) {
		return "\033[4;33m" + str + RESET;
	}

	public static String BLUE_UNDERLINED(String str) {
		return "\033[4;34m" + str + RESET;
	}

	public static String PURPLE_UNDERLINED(String str) {
		return "\033[4;35m" + str + RESET;
	}

	public static String CYAN_UNDERLINED(String str) {
		return "\033[4;36m" + str + RESET;
	}

	public static String WHITE_UNDERLINED(String str) {
		return "\033[4;37m" + str + RESET;
	}

	public static String BLACK_BRIGHT(String str) {
		return "\033[0;90m" + str + RESET;
	}

	public static String RED_BRIGHT(String str) {
		return "\033[0;91m" + str + RESET;
	}

	public static String GREEN_BRIGHT(String str) {
		return "\033[0;92m" + str + RESET;
	}

	public static String YELLOW_BRIGHT(String str) {
		return "\033[0;93m" + str + RESET;
	}

	public static String BLUE_BRIGHT(String str) {
		return "\033[0;94m" + str + RESET;
	}

	public static String PURPLE_BRIGHT(String str) {
		return "\033[0;95m" + str + RESET;
	}

	public static String CYAN_BRIGHT(String str) {
		return "\033[0;96m" + str + RESET;
	}

	public static String WHITE_BRIGHT(String str) {
		return "\033[0;97m" + str + RESET;
	}

	public static String BLACK_BOLD_BRIGHT(String str) {
		return "\033[1;90m" + str + RESET;
	}

	public static String RED_BOLD_BRIGHT(String str) {
		return "\033[1;91m" + str + RESET;
	}

	public static String GREEN_BOLD_BRIGHT(String str) {
		return "\033[1;92m" + str + RESET;
	}

	public static String YELLOW_BOLD_BRIGHT(String str) {
		return "\033[1;93m" + str + RESET;
	}

	public static String BLUE_BOLD_BRIGHT(String str) {
		return "\033[1;94m" + str + RESET;
	}

	public static String PURPLE_BOLD_BRIGHT(String str) {
		return "\033[1;95m" + str + RESET;
	}

	public static String CYAN_BOLD_BRIGHT(String str) {
		return "\033[1;96m" + str + RESET;
	}

	public static String WHITE_BOLD_BRIGHT(String str) {
		return "\033[1;97m" + str + RESET;
	}

}
