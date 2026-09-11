package io.mindspice.lyra.editor;

import java.util.*;
import java.util.regex.*;

/** Pure editing operations; offsets are UTF-16, matching the compiler and JavaFX. */
public final class TextEdits {
    private TextEdits() { }
    public record Match(int start, int end) { }
    public static List<Match> matches(String text, String query, boolean regex, boolean matchCase) {
        if (query.isEmpty()) return List.of();
        Pattern pattern = Pattern.compile(regex ? query : Pattern.quote(query), matchCase ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        List<Match> matches = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find() && matches.size() < 10000) matches.add(new Match(matcher.start(), matcher.end()));
        return List.copyOf(matches);
    }
    public static String replaceAll(String text, List<Match> matches, String replacement) {
        StringBuilder result = new StringBuilder();
        int cursor = 0;
        for (Match match : matches) {
            result.append(text, cursor, match.start()).append(replacement);
            cursor = match.end();
        }
        return result.append(text, cursor, text.length()).toString();
    }
    public static String toggleComment(String text) {
        List<String> lines = Arrays.asList(text.split("\n", -1));
        boolean uncomment = lines.stream().filter(line -> !line.isBlank()).allMatch(line -> line.stripLeading().startsWith("//"));
        return String.join("\n", lines.stream().map(line -> {
            if (line.isBlank()) return line;
            int prefix = line.length() - line.stripLeading().length();
            if (uncomment) {
                int end = prefix + 2;
                if (end < line.length() && line.charAt(end) == ' ') end++;
                return line.substring(0, prefix) + line.substring(end);
            }
            return line.substring(0, prefix) + "// " + line.substring(prefix);
        }).toList());
    }
    public static int lineAt(String text, int offset) {
        int line = 1;
        for (int i = 0; i < Math.min(offset, text.length()); i++) if (text.charAt(i) == '\n') line++;
        return line;
    }
    public static int offsetAt(String text, int line) {
        int position = 0;
        for (int current = 1; current < line; current++) {
            int next = text.indexOf('\n', position);
            if (next < 0) return text.length();
            position = next + 1;
        }
        return position;
    }
    public static int wordStart(String text, int caret) {
        int start = Math.min(caret, text.length());
        while (start > 0 && word(text.charAt(start - 1))) start--;
        return start;
    }
    public static String wordAt(String text, int caret) {
        int start = wordStart(text, caret), end = Math.min(caret, text.length());
        while (end < text.length() && word(text.charAt(end))) end++;
        return text.substring(start, end);
    }
    private static boolean word(char c) { return Character.isJavaIdentifierPart(c) || c == '?'; }
    public static int rebaseLine(String before, String after, int line) {
        String[] oldLines = before.split("\n", -1), newLines = after.split("\n", -1);
        int index = line - 1;
        if (index < 0 || index >= oldLines.length) return -1;
        int prefix = 0, shared = Math.min(oldLines.length, newLines.length);
        while (prefix < shared && oldLines[prefix].equals(newLines[prefix])) prefix++;
        int oldEnd = oldLines.length, newEnd = newLines.length;
        while (oldEnd > prefix && newEnd > prefix && oldLines[oldEnd - 1].equals(newLines[newEnd - 1])) { oldEnd--; newEnd--; }
        if (index < prefix) return line;
        if (index >= oldEnd) return line + newEnd - oldEnd;
        return index < newEnd ? line : -1;
    }
}
