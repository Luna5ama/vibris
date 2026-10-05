package dev.vibris.mod;

import net.irisshaders.iris.shaderpack.include.ShaderSourceMap;

public final class ShaderMainSource {
	private ShaderMainSource() {}

	public static String resolve(String fallback, String transformedSource) {
		if (transformedSource == null) {
			return fallback;
		}

		// JCPP and TransformPatcher preserve physical include origins through #line directives plus this source map.
		// Attribute timing to the file containing the active main function, not merely the small wrapper .csh file.
		ShaderSourceMap sourceMap = ShaderSourceMap.parse(transformedSource);
		int sourceId = findMainSourceId(sourceMap.sourceWithoutMetadata());
		if (sourceId < 0) {
			return fallback;
		}

		String sourcePath = sourceMap.sourcePaths().get(sourceId);
		if (sourcePath == null || sourcePath.isBlank()) {
			return fallback;
		}
		return sourcePath;
	}

	private static int findMainSourceId(String source) {
		boolean lineComment = false;
		boolean blockComment = false;
		boolean lineStart = true;
		boolean previousWordCharacter = false;
		int sourceId = -1;
		for (int i = 0; i < source.length(); i++) {
			char current = source.charAt(i);
			char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
			if (lineComment) {
				if (current == '\n' || current == '\r') {
					lineComment = false;
					lineStart = true;
				}
				previousWordCharacter = false;
			} else if (blockComment) {
				if (current == '*' && next == '/') {
					i++;
					blockComment = false;
				} else if (current == '\n' || current == '\r') {
					lineStart = true;
				}
				previousWordCharacter = false;
			} else if (current == '/' && next == '/') {
				i++;
				lineComment = true;
				previousWordCharacter = false;
			} else if (current == '/' && next == '*') {
				i++;
				blockComment = true;
				previousWordCharacter = false;
			} else if (current == '\n' || current == '\r') {
				lineStart = true;
				previousWordCharacter = false;
			} else if (isHorizontalWhitespace(current)) {
				previousWordCharacter = false;
			} else {
				if (lineStart && current == '#') {
					int directiveSourceId = parseLineDirectiveSourceId(source, i);
					if (directiveSourceId >= 0) {
						sourceId = directiveSourceId;
					}
				}
				if (!previousWordCharacter && current == 'v' && matchesMainFunction(source, i)) {
					return sourceId;
				}
				lineStart = false;
				previousWordCharacter = isWordCharacter(current);
			}
		}
		return -1;
	}

	private static boolean matchesMainFunction(String source, int offset) {
		if (!source.regionMatches(offset, "void", 0, 4)) {
			return false;
		}
		offset += 4;
		if (offset >= source.length()
			|| !Character.isWhitespace(source.charAt(offset)) && !startsComment(source, offset)) {
			return false;
		}
		offset = skipMaskedWhitespace(source, offset);
		if (!source.regionMatches(offset, "main", 0, 4)) {
			return false;
		}
		offset = skipMaskedWhitespace(source, offset + 4);
		return offset < source.length() && source.charAt(offset) == '(';
	}

	private static int skipMaskedWhitespace(String source, int offset) {
		while (offset < source.length()) {
			if (Character.isWhitespace(source.charAt(offset))) {
				offset++;
			} else if (offset + 1 < source.length() && source.charAt(offset) == '/' && source.charAt(offset + 1) == '/') {
				offset += 2;
				while (offset < source.length() && source.charAt(offset) != '\n' && source.charAt(offset) != '\r') {
					offset++;
				}
			} else if (offset + 1 < source.length() && source.charAt(offset) == '/' && source.charAt(offset + 1) == '*') {
				int commentEnd = source.indexOf("*/", offset + 2);
				offset = commentEnd < 0 ? source.length() : commentEnd + 2;
			} else {
				break;
			}
		}
		return offset;
	}

	private static boolean startsComment(String source, int offset) {
		return offset + 1 < source.length() && source.charAt(offset) == '/'
			&& (source.charAt(offset + 1) == '/' || source.charAt(offset + 1) == '*');
	}

	private static int parseLineDirectiveSourceId(String source, int offset) {
		offset = skipHorizontalWhitespace(source, offset + 1);
		if (!source.regionMatches(offset, "line", 0, 4)) {
			return -1;
		}
		offset += 4;
		int lineStart = skipHorizontalWhitespace(source, offset);
		if (lineStart == offset) {
			return -1;
		}
		int lineEnd = skipDigits(source, lineStart);
		if (lineEnd == lineStart) {
			return -1;
		}
		int sourceStart = skipHorizontalWhitespace(source, lineEnd);
		if (sourceStart == lineEnd) {
			return -1;
		}
		int sourceEnd = skipDigits(source, sourceStart);
		if (sourceEnd == sourceStart) {
			return -1;
		}
		int remainder = skipHorizontalWhitespace(source, sourceEnd);
		if (remainder < source.length() && source.charAt(remainder) != '\n' && source.charAt(remainder) != '\r'
			&& !startsComment(source, remainder)) {
			return -1;
		}
		return Integer.parseInt(source, sourceStart, sourceEnd, 10);
	}

	private static int skipHorizontalWhitespace(String source, int offset) {
		while (offset < source.length() && isHorizontalWhitespace(source.charAt(offset))) {
			offset++;
		}
		return offset;
	}

	private static int skipDigits(String source, int offset) {
		while (offset < source.length()) {
			char character = source.charAt(offset);
			if (character < '0' || character > '9') {
				break;
			}
			offset++;
		}
		return offset;
	}

	private static boolean isHorizontalWhitespace(char character) {
		return character == ' ' || character == '\t' || character == '\u00A0' || character == '\u1680'
			|| character == '\u180E' || character >= '\u2000' && character <= '\u200A'
			|| character == '\u202F' || character == '\u205F' || character == '\u3000';
	}

	private static boolean isWordCharacter(char character) {
		return Character.isLetterOrDigit(character) || character == '_';
	}
}
