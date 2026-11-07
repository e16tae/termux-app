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
        assertArrayEquals(text, new int[]{start, end}, WordBoundary.find(cells(text, 40), column));
    }

    @Test
    public void wordFromAnyColumn() {
        assertWord("apple banana", 2, 0, 4);
        assertWord("apple banana", 0, 0, 4);
        assertWord("apple banana", 4, 0, 4);
        assertWord("apple banana", 8, 6, 11);
    }

    @Test
    public void separatorHasNoWord() {
        assertNull(WordBoundary.find(cells("apple banana", 40), 5));
        assertNull(WordBoundary.find(cells("a,b", 40), 1));
        assertNull(WordBoundary.find(cells("apple", 40), 20));
    }

    @Test
    public void outOfRangeHasNoWord() {
        assertNull(WordBoundary.find(cells("apple", 40), -1));
        assertNull(WordBoundary.find(cells("apple", 40), 40));
    }

    // Everything below was measured with a double click in Herdr 0.9.5.

    @Test
    public void bracketsAndQuotesAreLeftOut() {
        assertWord("(tag)", 2, 1, 3);
        assertWord("\"tag\"", 2, 1, 3);
        assertWord("'tag'", 2, 1, 3);
        assertWord("[tag]", 2, 1, 3);
        assertWord("<tag>", 2, 1, 3);
        assertWord("{tag}", 2, 1, 3);
        assertWord("`tag`", 2, 1, 3);
    }

    @Test
    public void listPunctuationSeparates() {
        assertWord("foo,bar", 1, 0, 2);
        assertWord("foo;bar", 5, 4, 6);
        assertWord("foo|bar", 1, 0, 2);
        assertWord("foo!bar", 1, 0, 2);
    }

    @Test
    public void pathLikePunctuationStaysInTheWord() {
        for (String word : new String[]{"foo/bar.txt:12", "x=1", "key:value", "~/path/x", "#123", "a_b", "v1.2.3",
            "user@host", "50%", "a&b", "a?b", "a*b", "a+b", "a$b", "hello-world"}) {
            assertWord(word, word.length() / 2, 0, word.length() - 1);
        }
    }

    @Test
    public void boxDrawingStaysInTheWord() {
        assertWord("│box│", 2, 0, 4);
    }

    @Test
    public void wideCharactersCoverBothColumns() {
        String text = "안녕 세계";
        assertWord(text, 0, 0, 3);
        assertWord(text, 1, 0, 3);
        assertWord(text, 3, 0, 3);
        assertWord(text, 5, 5, 8);
        assertNull(WordBoundary.find(cells(text, 40), 4));
    }

    @Test
    public void mixedScriptsAreOneWord() {
        String text = "한글abc";
        assertWord(text, 5, 0, 6);
    }
}
