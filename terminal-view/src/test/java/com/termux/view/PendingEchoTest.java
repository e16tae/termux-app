package com.termux.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PendingEchoTest {

    private static final int COLUMNS = 40;

    private static PendingEcho pending(String text, int row, int column) {
        PendingEcho p = new PendingEcho();
        text.codePoints().forEach(cp -> p.add(cp, row, column, COLUMNS));
        return p;
    }

    @Test
    public void endColumnCountsWideCharacters() {
        PendingEcho p = pending("가나 ", 3, 10);
        assertEquals(10, p.getColumn());
        assertEquals(15, p.getEndColumn());
    }

    @Test
    public void anchorStaysWhileOlderInputIsPending() {
        PendingEcho p = new PendingEcho();
        p.add('가', 3, 10, COLUMNS);
        // The cursor has not moved yet, and a later cursor must not move the anchor.
        p.add('나', 3, 99, COLUMNS);
        assertEquals(10, p.getColumn());
        assertEquals("가나", p.getText());
    }

    @Test
    public void unchangedCursorKeepsText() {
        PendingEcho p = pending("가", 3, 10);
        p.reconcile(3, 10);
        assertEquals("가", p.getText());
    }

    @Test
    public void partialEchoDropsOnlyEchoedText() {
        PendingEcho p = pending("가나", 3, 10);
        p.reconcile(3, 12);
        assertEquals("나", p.getText());
        assertEquals(12, p.getColumn());
        assertEquals(14, p.getEndColumn());
    }

    @Test
    public void fullEchoEmpties() {
        PendingEcho p = pending("가나", 3, 10);
        p.reconcile(3, 14);
        assertTrue(p.isEmpty());
    }

    @Test
    public void addAfterFullEchoUsesNewCursor() {
        PendingEcho p = pending("가", 3, 10);
        p.reconcile(3, 12);
        p.add('나', 3, 12, COLUMNS);
        assertEquals(12, p.getColumn());
        assertEquals(14, p.getEndColumn());
    }

    @Test
    public void cursorInsideWideCharacterDropsEverything() {
        PendingEcho p = pending("가나", 3, 10);
        p.reconcile(3, 11);
        assertTrue(p.isEmpty());
    }

    @Test
    public void cursorPastPendingDropsEverything() {
        PendingEcho p = pending("가", 3, 10);
        p.reconcile(3, 13);
        assertTrue(p.isEmpty());
    }

    @Test
    public void cursorMovingBackDropsEverything() {
        PendingEcho p = pending("가", 3, 10);
        p.reconcile(3, 9);
        assertTrue(p.isEmpty());
    }

    @Test
    public void cursorChangingRowDropsEverything() {
        PendingEcho p = pending("가", 3, 10);
        p.reconcile(4, 10);
        assertTrue(p.isEmpty());
    }

    @Test
    public void wouldWrapDropsEverything() {
        PendingEcho p = new PendingEcho();
        assertTrue(p.add('가', 3, 36, COLUMNS));
        assertFalse(p.add('나', 3, 36, COLUMNS));
        assertTrue(p.isEmpty());
    }

    @Test
    public void zeroWidthDropsEverything() {
        PendingEcho p = pending("가", 3, 10);
        assertFalse(p.add(0x0301, 3, 10, COLUMNS));
        assertTrue(p.isEmpty());
    }

    @Test
    public void supplementaryCharactersAreCountedOnce() {
        PendingEcho p = pending("😀가", 3, 10);
        p.reconcile(3, 12);
        assertEquals("가", p.getText());
    }
}
