package com.termux.view.textselection;

/**
 * Finds the word around a screen column, using the same separators as a double click in Herdr, so
 * that the handles appear where the selection the program made actually is.
 *
 * Holds no Android state so it can be unit tested on the JVM.
 */
final class WordBoundary {

    /** Marks the second column of a wide character in a row of cells. */
    static final int WIDE_CONTINUATION = -1;

    private WordBoundary() {}

    static boolean isSeparator(int codePoint) {
        switch (codePoint) {
            case '"': case '\'': case '`':
            case '(': case ')': case '[': case ']': case '{': case '}': case '<': case '>':
            case ',': case ';': case '|': case '!':
                return true;
            default:
                return codePoint != WIDE_CONTINUATION && (codePoint <= ' ' || Character.isWhitespace(codePoint));
        }
    }

    /** @return the first and last column of the word at {@code column}, or null if there is none. */
    static int[] find(int[] cells, int column) {
        if (column < 0 || column >= cells.length) return null;
        if (isSeparator(cells[column])) return null;
        int start = column;
        while (start > 0 && !isSeparator(cells[start - 1])) start--;
        int end = column;
        while (end + 1 < cells.length && !isSeparator(cells[end + 1])) end++;
        return new int[]{start, end};
    }
}
