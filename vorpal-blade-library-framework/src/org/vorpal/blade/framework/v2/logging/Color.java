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

package org.vorpal.blade.framework.v2.logging;

/**
 * Utility class for wrapping strings with ANSI color codes.
 * Each method wraps the input string with the appropriate color code and automatically resets.
 */
public class Color {

	private Color() {
		// Utility class - prevent instantiation
	}

	public static String BLACK(String str) {
		if (str == null) return null;
		return ConsoleColors.BLACK + str + ConsoleColors.RESET;
	}

	public static String RED(String str) {
		if (str == null) return null;
		return ConsoleColors.RED + str + ConsoleColors.RESET;
	}

	public static String GREEN(String str) {
		if (str == null) return null;
		return ConsoleColors.GREEN + str + ConsoleColors.RESET;
	}

	public static String YELLOW(String str) {
		if (str == null) return null;
		return ConsoleColors.YELLOW + str + ConsoleColors.RESET;
	}

	public static String BLUE(String str) {
		if (str == null) return null;
		return ConsoleColors.BLUE + str + ConsoleColors.RESET;
	}

	public static String PURPLE(String str) {
		if (str == null) return null;
		return ConsoleColors.PURPLE + str + ConsoleColors.RESET;
	}

	public static String CYAN(String str) {
		if (str == null) return null;
		return ConsoleColors.CYAN + str + ConsoleColors.RESET;
	}

	public static String WHITE(String str) {
		if (str == null) return null;
		return ConsoleColors.WHITE + str + ConsoleColors.RESET;
	}

	public static String BLACK_BOLD(String str) {
		if (str == null) return null;
		return ConsoleColors.BLACK_BOLD + str + ConsoleColors.RESET;
	}

	public static String RED_BOLD(String str) {
		if (str == null) return null;
		return ConsoleColors.RED_BOLD + str + ConsoleColors.RESET;
	}

	public static String GREEN_BOLD(String str) {
		if (str == null) return null;
		return ConsoleColors.GREEN_BOLD + str + ConsoleColors.RESET;
	}

	public static String YELLOW_BOLD(String str) {
		if (str == null) return null;
		return ConsoleColors.YELLOW_BOLD + str + ConsoleColors.RESET;
	}

	public static String BLUE_BOLD(String str) {
		if (str == null) return null;
		return ConsoleColors.BLUE_BOLD + str + ConsoleColors.RESET;
	}

	public static String PURPLE_BOLD(String str) {
		if (str == null) return null;
		return ConsoleColors.PURPLE_BOLD + str + ConsoleColors.RESET;
	}

	public static String CYAN_BOLD(String str) {
		if (str == null) return null;
		return ConsoleColors.CYAN_BOLD + str + ConsoleColors.RESET;
	}

	public static String WHITE_BOLD(String str) {
		if (str == null) return null;
		return ConsoleColors.WHITE_BOLD + str + ConsoleColors.RESET;
	}

	public static String BLACK_UNDERLINED(String str) {
		if (str == null) return null;
		return ConsoleColors.BLACK_UNDERLINED + str + ConsoleColors.RESET;
	}

	public static String RED_UNDERLINED(String str) {
		if (str == null) return null;
		return ConsoleColors.RED_UNDERLINED + str + ConsoleColors.RESET;
	}

	public static String GREEN_UNDERLINED(String str) {
		if (str == null) return null;
		return ConsoleColors.GREEN_UNDERLINED + str + ConsoleColors.RESET;
	}

	public static String YELLOW_UNDERLINED(String str) {
		if (str == null) return null;
		return ConsoleColors.YELLOW_UNDERLINED + str + ConsoleColors.RESET;
	}

	public static String BLUE_UNDERLINED(String str) {
		if (str == null) return null;
		return ConsoleColors.BLUE_UNDERLINED + str + ConsoleColors.RESET;
	}

	public static String PURPLE_UNDERLINED(String str) {
		if (str == null) return null;
		return ConsoleColors.PURPLE_UNDERLINED + str + ConsoleColors.RESET;
	}

	public static String CYAN_UNDERLINED(String str) {
		if (str == null) return null;
		return ConsoleColors.CYAN_UNDERLINED + str + ConsoleColors.RESET;
	}

	public static String WHITE_UNDERLINED(String str) {
		if (str == null) return null;
		return ConsoleColors.WHITE_UNDERLINED + str + ConsoleColors.RESET;
	}

	public static String BLACK_BRIGHT(String str) {
		if (str == null) return null;
		return ConsoleColors.BLACK_BRIGHT + str + ConsoleColors.RESET;
	}

	public static String RED_BRIGHT(String str) {
		if (str == null) return null;
		return ConsoleColors.RED_BRIGHT + str + ConsoleColors.RESET;
	}

	public static String GREEN_BRIGHT(String str) {
		if (str == null) return null;
		return ConsoleColors.GREEN_BRIGHT + str + ConsoleColors.RESET;
	}

	public static String YELLOW_BRIGHT(String str) {
		if (str == null) return null;
		return ConsoleColors.YELLOW_BRIGHT + str + ConsoleColors.RESET;
	}

	public static String BLUE_BRIGHT(String str) {
		if (str == null) return null;
		return ConsoleColors.BLUE_BRIGHT + str + ConsoleColors.RESET;
	}

	public static String PURPLE_BRIGHT(String str) {
		if (str == null) return null;
		return ConsoleColors.PURPLE_BRIGHT + str + ConsoleColors.RESET;
	}

	public static String CYAN_BRIGHT(String str) {
		if (str == null) return null;
		return ConsoleColors.CYAN_BRIGHT + str + ConsoleColors.RESET;
	}

	public static String WHITE_BRIGHT(String str) {
		if (str == null) return null;
		return ConsoleColors.WHITE_BRIGHT + str + ConsoleColors.RESET;
	}

	public static String BLACK_BOLD_BRIGHT(String str) {
		if (str == null) return null;
		return ConsoleColors.BLACK_BOLD_BRIGHT + str + ConsoleColors.RESET;
	}

	public static String RED_BOLD_BRIGHT(String str) {
		if (str == null) return null;
		return ConsoleColors.RED_BOLD_BRIGHT + str + ConsoleColors.RESET;
	}

	public static String GREEN_BOLD_BRIGHT(String str) {
		if (str == null) return null;
		return ConsoleColors.GREEN_BOLD_BRIGHT + str + ConsoleColors.RESET;
	}

	public static String YELLOW_BOLD_BRIGHT(String str) {
		if (str == null) return null;
		return ConsoleColors.YELLOW_BOLD_BRIGHT + str + ConsoleColors.RESET;
	}

	public static String BLUE_BOLD_BRIGHT(String str) {
		if (str == null) return null;
		return ConsoleColors.BLUE_BOLD_BRIGHT + str + ConsoleColors.RESET;
	}

	public static String PURPLE_BOLD_BRIGHT(String str) {
		if (str == null) return null;
		return ConsoleColors.PURPLE_BOLD_BRIGHT + str + ConsoleColors.RESET;
	}

	public static String CYAN_BOLD_BRIGHT(String str) {
		if (str == null) return null;
		return ConsoleColors.CYAN_BOLD_BRIGHT + str + ConsoleColors.RESET;
	}

	public static String WHITE_BOLD_BRIGHT(String str) {
		if (str == null) return null;
		return ConsoleColors.WHITE_BOLD_BRIGHT + str + ConsoleColors.RESET;
	}

}
