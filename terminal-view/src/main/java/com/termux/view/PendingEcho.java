package com.termux.view;

import com.termux.terminal.WcWidth;

/**
 * Text committed by the IME that the remote side has not echoed back yet.
 *
 * Without this, a committed syllable disappears until the echo arrives and the next composing
 * syllable is drawn on top of its cell at the stale cursor, then jumps right when the echo arrives.
 * Over ssh that is visible on every syllable. The view keeps drawing this text until the terminal
 * cursor shows that the echo has arrived.
 *
 * Holds no Android state so it can be unit tested on the JVM.
 */
final class PendingEcho {

    private final StringBuilder mText = new StringBuilder();
    private int mRow;
    private int mColumn;
    private int mWidth;

    boolean isEmpty() {
        return mText.length() == 0;
    }

    String getText() {
        return mText.toString();
    }

    int getRow() {
        return mRow;
    }

    /** The column the pending text starts at. */
    int getColumn() {
        return mColumn;
    }

    /** The column just after the pending text. */
    int getEndColumn() {
        return mColumn + mWidth;
    }

    void clear() {
        mText.setLength(0);
        mWidth = 0;
    }

    /**
     * Track a code point sent to the terminal. Gives up (and clears) for anything that would wrap
     * or has no width of its own, so the view falls back to following the real cursor.
     *
     * @return whether the code point is now pending
     */
    boolean add(int codePoint, int cursorRow, int cursorColumn, int columns) {
        final int width = WcWidth.width(codePoint);
        if (width <= 0) {
            clear();
            return false;
        }
        if (isEmpty()) {
            mRow = cursorRow;
            mColumn = cursorColumn;
        }
        if (mColumn + mWidth + width >= columns) {
            clear();
            return false;
        }
        mText.appendCodePoint(codePoint);
        mWidth += width;
        return true;
    }

    /**
     * Drop the text the terminal cursor has moved past. Any cursor position that cannot be explained
     * by echoing the pending text means something else happened, so everything is dropped.
     */
    void reconcile(int cursorRow, int cursorColumn) {
        if (isEmpty() || (cursorRow == mRow && cursorColumn == mColumn)) return;
        final int advanced = cursorColumn - mColumn;
        if (cursorRow != mRow || advanced < 0) {
            clear();
            return;
        }
        int width = 0;
        int index = 0;
        while (index < mText.length() && width < advanced) {
            final int codePoint = mText.codePointAt(index);
            width += WcWidth.width(codePoint);
            index += Character.charCount(codePoint);
        }
        if (width != advanced) {
            clear();
            return;
        }
        mText.delete(0, index);
        mColumn = cursorColumn;
        mWidth -= width;
    }
}
