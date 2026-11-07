package com.termux.view.textselection;

/**
 * Finds the word around a screen column, so that the handles appear where the selection the program
 * made is expected to be. This follows the iTerm2 word boundaries that Claude Code documents for a
 * double click, with whole URLs. Other programs can differ, such as Herdr on a shell pane, which
 * keeps more punctuation in a word.
 *
 * Holds no Android state so it can be unit tested on the JVM.
 */
final class WordBoundary {

    /** Marks the second column of a wide character in a row of cells. */
    static final int WIDE_CONTINUATION = -1;

    private static final String WORD_PUNCTUATION = "/-+\\~_.";

    private WordBoundary() {}

    private static boolean isWordCharacter(int[] cells, int column) {
        int codePoint = cells[column];
        if (codePoint == WIDE_CONTINUATION) {
            if (column == 0) return false;
            codePoint = cells[column - 1];
        }
        return Character.isLetterOrDigit(codePoint) || WORD_PUNCTUATION.indexOf(codePoint) >= 0;
    }

    private static boolean isBlank(int[] cells, int column) {
        final int codePoint = cells[column];
        return codePoint != WIDE_CONTINUATION && (codePoint <= ' ' || Character.isWhitespace(codePoint));
    }

    /** @return the first and last column of the word at {@code column}, or null if there is none. */
    static int[] find(int[] cells, int column) {
        if (column < 0 || column >= cells.length) return null;
        final int[] url = findUrl(cells, column);
        if (url != null) return url;
        if (!isWordCharacter(cells, column)) return null;
        int start = column;
        while (start > 0 && isWordCharacter(cells, start - 1)) start--;
        int end = column;
        while (end + 1 < cells.length && isWordCharacter(cells, end + 1)) end++;
        return new int[]{start, end};
    }

    /** A URL is selected whole, up to the blanks around it. */
    private static int[] findUrl(int[] cells, int column) {
        if (isBlank(cells, column)) return null;
        int start = column;
        while (start > 0 && !isBlank(cells, start - 1)) start--;
        int end = column;
        while (end + 1 < cells.length && !isBlank(cells, end + 1)) end++;
        for (String scheme : new String[]{"http://", "https://"}) {
            if (end - start + 1 > scheme.length() && startsWith(cells, start, scheme)) return new int[]{start, end};
        }
        return null;
    }

    private static boolean startsWith(int[] cells, int start, String text) {
        for (int i = 0; i < text.length(); i++) {
            if (cells[start + i] != text.charAt(i)) return false;
        }
        return true;
    }
}
