package com.termux.view.textselection;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class WordBoundaryTest {

    /** Cells of a row, with a wide character taking a second column. */
    private static int[] cells(String text, int columns) {
        int[] cells = new int[columns];
        java.util.Arrays.fill(cells, ' ');
        int column = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            cells[column++] = codePoint;
            if (codePoint >= 0xAC00) cells[column++] = WordBoundary.WIDE_CONTINUATION;
        }
        return cells;
    }

    private static void assertWord(String text, int column, int start, int end) {
        assertArrayEquals(text, new int[]{start, end}, WordBoundary.find(cells(text, 60), column));
    }

    @Test
    public void wordFromAnyColumn() {
        assertWord("apple banana", 2, 0, 4);
        assertWord("apple banana", 0, 0, 4);
        assertWord("apple banana", 4, 0, 4);
        assertWord("apple banana", 8, 6, 11);
    }

    @Test
    public void noWord() {
        assertNull(WordBoundary.find(cells("apple banana", 60), 5));
        assertNull(WordBoundary.find(cells("a,b", 60), 1));
        assertNull(WordBoundary.find(cells("apple", 60), 20));
        assertNull(WordBoundary.find(cells("apple", 60), -1));
        assertNull(WordBoundary.find(cells("apple", 60), 60));
    }

    @Test
    public void pathsAreOneWord() {
        for (String word : new String[]{"~/path/x", "foo/bar.txt", "a_b", "v1.2.3", "hello-world", "a+b"}) {
            assertWord(word, word.length() / 2, 0, word.length() - 1);
        }
    }

    @Test
    public void otherPunctuationSeparates() {
        assertWord("(tag)", 2, 1, 3);
        assertWord("\"tag\"", 2, 1, 3);
        assertWord("[tag]", 2, 1, 3);
        assertWord("key:value", 1, 0, 2);
        assertWord("key:value", 6, 4, 8);
        assertWord("user@host", 6, 5, 8);
        assertWord("foo,bar", 1, 0, 2);
        assertWord("x=1", 0, 0, 0);
    }

    @Test
    public void urlsAreOneWord() {
        String url = "https://x.io/a?b=1&c=2";
        assertWord(url, 2, 0, url.length() - 1);
        assertWord(url, url.length() - 1, 0, url.length() - 1);
        assertWord("see http://x.io/a?b ok", 10, 4, 18);
    }

    @Test
    public void notQuiteUrlsAreNot() {
        assertWord("https://", 2, 0, 4);
        assertWord("foo:bar", 1, 0, 2);
    }

    @Test
    public void wideCharactersCoverBothColumns() {
        String text = "안녕 세계";
        assertWord(text, 0, 0, 3);
        assertWord(text, 1, 0, 3);
        assertWord(text, 3, 0, 3);
        assertWord(text, 5, 5, 8);
        assertNull(WordBoundary.find(cells(text, 60), 4));
    }

    @Test
    public void mixedScriptsAreOneWord() {
        assertWord("한글abc", 5, 0, 6);
    }
}
