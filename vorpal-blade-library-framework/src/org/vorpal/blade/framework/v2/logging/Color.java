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

import org.vorpal.blade.framework.v2.config.SettingsManager;

/**
 * Utility class for wrapping strings with ANSI color codes. Each method wraps
 * the input string with the appropriate color code and automatically resets.
 */
public class Color {
	
	// Reset
	public static final String RESET = "\033[0m"; // Text Reset

	// Regular Colors
	public static final String BLACK = "\033[0;30m"; // BLACK
	public static final String RED = "\033[0;31m"; // RED
	public static final String GREEN = "\033[0;32m"; // GREEN
	public static final String YELLOW = "\033[0;33m"; // YELLOW
	public static final String BLUE = "\033[0;34m"; // BLUE
	public static final String PURPLE = "\033[0;35m"; // PURPLE
	public static final String CYAN = "\033[0;36m"; // CYAN
	public static final String WHITE = "\033[0;37m"; // WHITE

	// Bold
	public static final String BLACK_BOLD = "\033[1;30m"; // BLACK
	public static final String RED_BOLD = "\033[1;31m"; // RED
	public static final String GREEN_BOLD = "\033[1;32m"; // GREEN
	public static final String YELLOW_BOLD = "\033[1;33m"; // YELLOW
	public static final String BLUE_BOLD = "\033[1;34m"; // BLUE
	public static final String PURPLE_BOLD = "\033[1;35m"; // PURPLE
	public static final String CYAN_BOLD = "\033[1;36m"; // CYAN
	public static final String WHITE_BOLD = "\033[1;37m"; // WHITE

	// Underline
	public static final String BLACK_UNDERLINED = "\033[4;30m"; // BLACK
	public static final String RED_UNDERLINED = "\033[4;31m"; // RED
	public static final String GREEN_UNDERLINED = "\033[4;32m"; // GREEN
	public static final String YELLOW_UNDERLINED = "\033[4;33m"; // YELLOW
	public static final String BLUE_UNDERLINED = "\033[4;34m"; // BLUE
	public static final String PURPLE_UNDERLINED = "\033[4;35m"; // PURPLE
	public static final String CYAN_UNDERLINED = "\033[4;36m"; // CYAN
	public static final String WHITE_UNDERLINED = "\033[4;37m"; // WHITE

	// Background
	public static final String BLACK_BACKGROUND = "\033[40m"; // BLACK
	public static final String RED_BACKGROUND = "\033[41m"; // RED
	public static final String GREEN_BACKGROUND = "\033[42m"; // GREEN
	public static final String YELLOW_BACKGROUND = "\033[43m"; // YELLOW
	public static final String BLUE_BACKGROUND = "\033[44m"; // BLUE
	public static final String PURPLE_BACKGROUND = "\033[45m"; // PURPLE
	public static final String CYAN_BACKGROUND = "\033[46m"; // CYAN
	public static final String WHITE_BACKGROUND = "\033[47m"; // WHITE

	// High Intensity
	public static final String BLACK_BRIGHT = "\033[0;90m"; // BLACK
	public static final String RED_BRIGHT = "\033[0;91m"; // RED
	public static final String GREEN_BRIGHT = "\033[0;92m"; // GREEN
	public static final String YELLOW_BRIGHT = "\033[0;93m"; // YELLOW
	public static final String BLUE_BRIGHT = "\033[0;94m"; // BLUE
	public static final String PURPLE_BRIGHT = "\033[0;95m"; // PURPLE
	public static final String CYAN_BRIGHT = "\033[0;96m"; // CYAN
	public static final String WHITE_BRIGHT = "\033[0;97m"; // WHITE

	// Bold High Intensity
	public static final String BLACK_BOLD_BRIGHT = "\033[1;90m"; // BLACK
	public static final String RED_BOLD_BRIGHT = "\033[1;91m"; // RED
	public static final String GREEN_BOLD_BRIGHT = "\033[1;92m"; // GREEN
	public static final String YELLOW_BOLD_BRIGHT = "\033[1;93m";// YELLOW
	public static final String BLUE_BOLD_BRIGHT = "\033[1;94m"; // BLUE
	public static final String PURPLE_BOLD_BRIGHT = "\033[1;95m";// PURPLE
	public static final String CYAN_BOLD_BRIGHT = "\033[1;96m"; // CYAN
	public static final String WHITE_BOLD_BRIGHT = "\033[1;97m"; // WHITE

	// High Intensity backgrounds
	public static final String BLACK_BACKGROUND_BRIGHT = "\033[0;100m";// BLACK
	public static final String RED_BACKGROUND_BRIGHT = "\033[0;101m";// RED
	public static final String GREEN_BACKGROUND_BRIGHT = "\033[0;102m";// GREEN
	public static final String YELLOW_BACKGROUND_BRIGHT = "\033[0;103m";// YELLOW
	public static final String BLUE_BACKGROUND_BRIGHT = "\033[0;104m";// BLUE
	public static final String PURPLE_BACKGROUND_BRIGHT = "\033[0;105m"; // PURPLE
	public static final String CYAN_BACKGROUND_BRIGHT = "\033[0;106m"; // CYAN
	public static final String WHITE_BACKGROUND_BRIGHT = "\033[0;107m"; // WHITE

	

	private Color() {
		// Utility class - prevent instantiation
	}

	public static String BLACK(String str) {
		if (str != null && Boolean.TRUE.equals(SettingsManager.getLogParameters().colorsEnabled)) {
			str = BLACK + str + RESET;
		}

		return str;
	}

	public static String RED(String str) {
		if (str != null && Boolean.TRUE.equals(SettingsManager.getLogParameters().colorsEnabled)) {
			str = RED + str + RESET;
		}
		return str;
	}

	public static String GREEN(String str) {
		if (str != null && Boolean.TRUE.equals(SettingsManager.getLogParameters().colorsEnabled)) {
			str = GREEN + str + RESET;
		}
		return str;
	}

	public static String YELLOW(String str) {
		if (str != null && Boolean.TRUE.equals(SettingsManager.getLogParameters().colorsEnabled)) {
			str = YELLOW + str + RESET;
		}
		return str;
	}

	public static String BLUE(String str) {
		if (str != null && Boolean.TRUE.equals(SettingsManager.getLogParameters().colorsEnabled)) {
			str = BLUE + str + RESET;
		}
		return str;
	}

	public static String PURPLE(String str) {
		if (str != null && Boolean.TRUE.equals(SettingsManager.getLogParameters().colorsEnabled)) {
			str = PURPLE + str + RESET;
		}
		return str;
	}

	public static String CYAN(String str) {
		if (str != null && Boolean.TRUE.equals(SettingsManager.getLogParameters().colorsEnabled)) {
			str = CYAN + str + RESET;
		}
		return str;
	}

	public static String WHITE(String str) {
		if (str != null && Boolean.TRUE.equals(SettingsManager.getLogParameters().colorsEnabled)) {
			str = WHITE + str + RESET;
		}
		return str;
	}

	public static String BLACK_BOLD(String str) {
		if (str != null && Boolean.TRUE.equals(SettingsManager.getLogParameters().colorsEnabled)) {
			str = BLACK_BOLD + str + RESET;
		}
		return str;
	}

	public static String RED_BOLD(String str) {
		if (str != null && Boolean.TRUE.equals(SettingsManager.getLogParameters().colorsEnabled)) {
			str = RED_BOLD + str + RESET;
		}
		return str;
	}

	public static String GREEN_BOLD(String str) {
		if (str != null && Boolean.TRUE.equals(SettingsManager.getLogParameters().colorsEnabled)) {
			str = GREEN_BOLD + str + RESET;
		}
		return str;
	}

	public static String YELLOW_BOLD(String str) {
		if (str != null && Boolean.TRUE.equals(SettingsManager.getLogParameters().colorsEnabled)) {
			str = YELLOW_BOLD + str + RESET;
		}
		return str;
	}

	public static String BLUE_BOLD(String str) {
		if (str != null && Boolean.TRUE.equals(SettingsManager.getLogParameters().colorsEnabled)) {
			str = BLUE_BOLD + str + RESET;
		}
		return str;
	}

	public static String PURPLE_BOLD(String str) {
		if (str != null && Boolean.TRUE.equals(SettingsManager.getLogParameters().colorsEnabled)) {
			str = PURPLE_BOLD + str + RESET;
		}
		return str;
	}

	public static String CYAN_BOLD(String str) {
		if (str != null && Boolean.TRUE.equals(SettingsManager.getLogParameters().colorsEnabled)) {
			str = CYAN_BOLD + str + RESET;
		}
		return str;
	}

	public static String WHITE_BOLD(String str) {
		if (str != null && Boolean.TRUE.equals(SettingsManager.getLogParameters().colorsEnabled)) {
			str = WHITE_BOLD + str + RESET;
		}
		return str;
	}

	public static String BLACK_UNDERLINED(String str) {
		if (str != null && Boolean.TRUE.equals(SettingsManager.getLogParameters().colorsEnabled)) {
			str = BLACK_UNDERLINED + str + RESET;
		}
		return str;
	}

	public static String RED_UNDERLINED(String str) {
		if (str != null && Boolean.TRUE.equals(SettingsManager.getLogParameters().colorsEnabled)) {
			str = RED_UNDERLINED + str + RESET;
		}
		return str;
	}

	public static String GREEN_UNDERLINED(String str) {
		if (str != null && Boolean.TRUE.equals(SettingsManager.getLogParameters().colorsEnabled)) {
			str = GREEN_UNDERLINED + str + RESET;
		}
		return str;
	}

	public static String YELLOW_UNDERLINED(String str) {
		if (str != null && Boolean.TRUE.equals(SettingsManager.getLogParameters().colorsEnabled)) {
			str = YELLOW_UNDERLINED + str + RESET;
		}
		return str;
	}

	public static String BLUE_UNDERLINED(String str) {
		if (str != null && Boolean.TRUE.equals(SettingsManager.getLogParameters().colorsEnabled)) {
			str = BLUE_UNDERLINED + str + RESET;
		}
		return str;
	}

	public static String PURPLE_UNDERLINED(String str) {
		if (str != null && Boolean.TRUE.equals(SettingsManager.getLogParameters().colorsEnabled)) {
			str = PURPLE_UNDERLINED + str + RESET;
		}
		return str;
	}

	public static String CYAN_UNDERLINED(String str) {
		if (str != null && Boolean.TRUE.equals(SettingsManager.getLogParameters().colorsEnabled)) {
			str = CYAN_UNDERLINED + str + RESET;
		}
		return str;
	}

	public static String WHITE_UNDERLINED(String str) {
		if (str != null && Boolean.TRUE.equals(SettingsManager.getLogParameters().colorsEnabled)) {
			str = WHITE_UNDERLINED + str + RESET;
		}
		return str;
	}

	public static String BLACK_BRIGHT(String str) {
		if (str != null && Boolean.TRUE.equals(SettingsManager.getLogParameters().colorsEnabled)) {
			str = BLACK_BRIGHT + str + RESET;
		}
		return str;
	}

	public static String RED_BRIGHT(String str) {
		if (str != null && Boolean.TRUE.equals(SettingsManager.getLogParameters().colorsEnabled)) {
			str = RED_BRIGHT + str + RESET;
		}
		return str;
	}

	public static String GREEN_BRIGHT(String str) {
		if (str != null && Boolean.TRUE.equals(SettingsManager.getLogParameters().colorsEnabled)) {
			str = GREEN_BRIGHT + str + RESET;
		}
		return str;
	}

	public static String YELLOW_BRIGHT(String str) {
		if (str != null && Boolean.TRUE.equals(SettingsManager.getLogParameters().colorsEnabled)) {
			str = YELLOW_BRIGHT + str + RESET;
		}
		return str;
	}

	public static String BLUE_BRIGHT(String str) {
		if (str != null && Boolean.TRUE.equals(SettingsManager.getLogParameters().colorsEnabled)) {
			str = BLUE_BRIGHT + str + RESET;
		}
		return str;
	}

	public static String PURPLE_BRIGHT(String str) {
		if (str != null && Boolean.TRUE.equals(SettingsManager.getLogParameters().colorsEnabled)) {
			str = PURPLE_BRIGHT + str + RESET;
		}
		return str;
	}

	public static String CYAN_BRIGHT(String str) {
		if (str != null && Boolean.TRUE.equals(SettingsManager.getLogParameters().colorsEnabled)) {
			str = CYAN_BRIGHT + str + RESET;
		}
		return str;
	}

	public static String WHITE_BRIGHT(String str) {
		if (str != null && Boolean.TRUE.equals(SettingsManager.getLogParameters().colorsEnabled)) {
			str = WHITE_BRIGHT + str + RESET;
		}
		return str;
	}

	public static String BLACK_BOLD_BRIGHT(String str) {
		if (str != null && Boolean.TRUE.equals(SettingsManager.getLogParameters().colorsEnabled)) {
			str = BLACK_BOLD_BRIGHT + str + RESET;
		}
		return str;
	}

	public static String RED_BOLD_BRIGHT(String str) {
		if (str != null && Boolean.TRUE.equals(SettingsManager.getLogParameters().colorsEnabled)) {
			str = RED_BOLD_BRIGHT + str + RESET;
		}
		return str;
	}

	public static String GREEN_BOLD_BRIGHT(String str) {
		if (str != null && Boolean.TRUE.equals(SettingsManager.getLogParameters().colorsEnabled)) {
			str = GREEN_BOLD_BRIGHT + str + RESET;
		}
		return str;
	}

	public static String YELLOW_BOLD_BRIGHT(String str) {
		if (str != null && Boolean.TRUE.equals(SettingsManager.getLogParameters().colorsEnabled)) {
			str = YELLOW_BOLD_BRIGHT + str + RESET;
		}
		return str;
	}

	public static String BLUE_BOLD_BRIGHT(String str) {
		if (str != null && Boolean.TRUE.equals(SettingsManager.getLogParameters().colorsEnabled)) {
			str = BLUE_BOLD_BRIGHT + str + RESET;
		}
		return str;
	}

	public static String PURPLE_BOLD_BRIGHT(String str) {
		if (str != null && Boolean.TRUE.equals(SettingsManager.getLogParameters().colorsEnabled)) {
			str = PURPLE_BOLD_BRIGHT + str + RESET;
		}
		return str;
	}

	public static String CYAN_BOLD_BRIGHT(String str) {
		if (str != null && Boolean.TRUE.equals(SettingsManager.getLogParameters().colorsEnabled)) {
			str = CYAN_BOLD_BRIGHT + str + RESET;
		}
		return str;
	}

	public static String WHITE_BOLD_BRIGHT(String str) {
		if (str != null && Boolean.TRUE.equals(SettingsManager.getLogParameters().colorsEnabled)) {
			str = WHITE_BOLD_BRIGHT + str + RESET;
		}
		return str;
	}

}
