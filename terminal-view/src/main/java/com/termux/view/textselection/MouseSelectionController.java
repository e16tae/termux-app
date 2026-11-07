package com.termux.view.textselection;

import android.graphics.Rect;
import android.os.Build;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Magnifier;
import android.widget.Toast;

import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalRow;
import com.termux.terminal.WcWidth;
import com.termux.view.R;
import com.termux.view.TerminalView;

import java.util.Arrays;

/**
 * Lets a finger select text in a program that tracks the mouse, by acting as a mouse with a held
 * button. The program does the selecting, which keeps it inside a pane of a multiplexer, and copies
 * through OSC 52 when the button is released.
 *
 * A long press sends a double click, so the program selects the word under the finger and a drag
 * extends by words. Lifting the finger keeps the button held, and shows handles for adjusting by
 * characters and a copy action. Copying releases the button.
 *
 * Which program selects depends on the pane: Claude Code tracks the mouse itself in fullscreen, and
 * otherwise Herdr selects. Claude Code copies a selection on any release, and also on a new press,
 * so nothing that should not copy may do either with a selection in place. {@link #CLEAR_SELECTION}
 * drops the selection in both first.
 *
 * The program does not report its selection, so the handles show where this class expects it to be.
 */
public class MouseSelectionController implements CursorController {

    private static final int DRAG_REPEAT_MILLIS = 100;
    private static final int COPY_TIMEOUT_MILLIS = 1000;
    private static final int ACTION_COPY = 1;

    /**
     * Ctrl+Space. Claude Code clears a selection on a key it has no other use for, Herdr does as well,
     * and a shell shows nothing for it, unlike function keys, which print part of their sequence.
     */
    private static final String CLEAR_SELECTION = "\0";

    /** Further than any double click tolerance of the program. */
    private static final int PLAIN_PRESS_DISTANCE = 4;

    private enum State {IDLE, DRAGGING, HOLDING}

    private enum Anchor {NONE, START, END}

    private final TerminalView mTerminalView;
    private final TextSelectionHandleView mStartHandle, mEndHandle;
    private final int mHandleHeight;

    private State mState = State.IDLE;
    private Anchor mAnchor = Anchor.NONE;
    private ActionMode mActionMode;
    private Magnifier mMagnifier;
    private boolean mHandlesShown;

    private int mPressColumn, mPressRow;
    private int[] mPressRowCells;
    private int mLastColumn, mLastRow;
    private int mStartColumn, mStartRow, mEndColumn, mEndRow;

    /** Programs scroll a selection on mouse events at an edge, and a held finger does not produce any. */
    private final Runnable mDragRepeat = new Runnable() {
        @Override
        public void run() {
            if (mState != State.DRAGGING || mTerminalView.mEmulator == null) return;
            sendMouse(TerminalEmulator.MOUSE_LEFT_BUTTON_MOVED, mLastColumn, mLastRow, true);
            mTerminalView.postDelayed(this, DRAG_REPEAT_MILLIS);
        }
    };

    private final Runnable mCopyTimeout = new Runnable() {
        @Override
        public void run() {
            Toast.makeText(mTerminalView.getContext(), R.string.mouse_selection_lost, Toast.LENGTH_SHORT).show();
        }
    };

    public MouseSelectionController(TerminalView terminalView) {
        mTerminalView = terminalView;
        mStartHandle = new TextSelectionHandleView(terminalView, this, TextSelectionHandleView.LEFT);
        mEndHandle = new TextSelectionHandleView(terminalView, this, TextSelectionHandleView.RIGHT);
        mHandleHeight = Math.max(mStartHandle.getHandleHeight(), mEndHandle.getHandleHeight());
    }

    public boolean isDragging() {
        return mState == State.DRAGGING;
    }

    public boolean isHolding() {
        return mState == State.HOLDING;
    }

    public ActionMode getActionMode() {
        return mActionMode;
    }

    /** Start from a long press. This also replaces a selection that is being held. */
    public void startDrag(MotionEvent event) {
        if (mState == State.HOLDING) clearSelection();
        hideHoldingUi();
        final int[] columnAndRow = mTerminalView.getColumnAndRow(event, false);
        mPressColumn = mLastColumn = clampColumn(columnAndRow[0]);
        mPressRow = mLastRow = clampRow(columnAndRow[1]);
        mPressRowCells = cellsOfRow(mPressRow);
        mState = State.DRAGGING;
        mAnchor = Anchor.NONE;

        // Keep the drawer from taking over a drag or handle that gets near the screen edge.
        mTerminalView.mClient.copyModeChanged(true);
        if (mTerminalView.getParent() != null) mTerminalView.getParent().requestDisallowInterceptTouchEvent(true);

        sendMouse(TerminalEmulator.MOUSE_LEFT_BUTTON, mPressColumn, mPressRow, true);
        sendMouse(TerminalEmulator.MOUSE_LEFT_BUTTON, mPressColumn, mPressRow, false);
        sendMouse(TerminalEmulator.MOUSE_LEFT_BUTTON, mPressColumn, mPressRow, true);
        showMagnifier(event.getX(), event.getY());
        mTerminalView.postDelayed(mDragRepeat, DRAG_REPEAT_MILLIS);
    }

    public void onDragTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                final int[] columnAndRow = mTerminalView.getColumnAndRow(event, false);
                final int column = clampColumn(columnAndRow[0]);
                final int row = clampRow(columnAndRow[1]);
                if (column != mLastColumn || row != mLastRow) {
                    mLastColumn = column;
                    mLastRow = row;
                    sendMouse(TerminalEmulator.MOUSE_LEFT_BUTTON_MOVED, column, row, true);
                }
                showMagnifier(event.getX(), event.getY());
                break;
            case MotionEvent.ACTION_UP:
                finishDrag();
                break;
            case MotionEvent.ACTION_CANCEL:
                cancel();
                break;
        }
    }

    private void finishDrag() {
        mTerminalView.removeCallbacks(mDragRepeat);
        dismissMagnifier();

        final boolean moved = mLastColumn != mPressColumn || mLastRow != mPressRow;
        final int[] pressWord = WordBoundary.find(cellsOfRow(mPressRow), mPressColumn);
        if (!moved && pressWord == null) {
            // A long press on nothing: the program has not selected anything.
            release();
            finish();
            return;
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // No floating toolbar to offer the copy action with.
            release();
            finish();
            return;
        }

        mState = State.HOLDING;
        final int[] press = pressWord != null ? pressWord : new int[]{mPressColumn, mPressColumn};
        final int[] lastWord = WordBoundary.find(cellsOfRow(mLastRow), mLastColumn);
        final int[] last = lastWord != null ? lastWord : new int[]{mLastColumn, mLastColumn};
        if (isBefore(mLastRow, mLastColumn, mPressRow, mPressColumn)) {
            setSelection(mLastRow, last[0], mPressRow, press[1]);
        } else {
            setSelection(mPressRow, press[0], mLastRow, last[1]);
        }

        // If the drag scrolled the program, the selection started outside of what is known here. It
        // can still be copied, but there is nothing to adjust from.
        mHandlesShown = Arrays.equals(mPressRowCells, cellsOfRow(mPressRow));
        if (mHandlesShown) positionHandles(true);
        startActionMode();
        mTerminalView.invalidate();
    }

    private void setSelection(int startRow, int startColumn, int endRow, int endColumn) {
        mStartRow = startRow;
        mStartColumn = startColumn;
        mEndRow = endRow;
        mEndColumn = endColumn;
    }

    private static boolean isBefore(int row1, int column1, int row2, int column2) {
        return row1 < row2 || (row1 == row2 && column1 < column2);
    }

    /** Release the button, which is what makes the program copy. */
    private void copy() {
        release();
        finish();
        mTerminalView.removeCallbacks(mCopyTimeout);
        mTerminalView.postDelayed(mCopyTimeout, COPY_TIMEOUT_MILLIS);
    }

    /** The program put text on the clipboard. */
    public void onProgramCopiedToClipboard() {
        mTerminalView.removeCallbacks(mCopyTimeout);
    }

    /** Drop the selection in the program without copying. */
    public void cancel() {
        if (mState == State.IDLE) return;
        clearSelection();
        release();
        finish();
    }

    /** Stop without telling the program, for when something else ends the selection in it. */
    public void reset() {
        if (mState != State.IDLE) finish();
    }

    private void clearSelection() {
        if (mTerminalView.mTermSession != null) mTerminalView.mTermSession.write(CLEAR_SELECTION);
    }

    private void release() {
        sendMouse(TerminalEmulator.MOUSE_LEFT_BUTTON, mLastColumn, mLastRow, false);
    }

    private void finish() {
        mState = State.IDLE;
        mAnchor = Anchor.NONE;
        mTerminalView.removeCallbacks(mDragRepeat);
        dismissMagnifier();
        hideHoldingUi();
        if (mTerminalView.mClient != null) mTerminalView.mClient.copyModeChanged(false);
        mTerminalView.invalidate();
    }

    private void hideHoldingUi() {
        mHandlesShown = false;
        mStartHandle.hide();
        mEndHandle.hide();
        if (mActionMode != null) {
            mActionMode.finish();
            mActionMode = null;
        }
    }

    private void sendMouse(int button, int column, int row, boolean pressed) {
        if (mTerminalView.mEmulator != null) mTerminalView.mEmulator.sendMouseEvent(button, column + 1, row + 1, pressed);
    }

    private int clampColumn(int column) {
        return Math.max(0, Math.min(column, mTerminalView.mEmulator.mColumns - 1));
    }

    private int clampRow(int row) {
        return Math.max(0, Math.min(row, mTerminalView.mEmulator.mRows - 1));
    }

    /** The code point of each column of a row on screen. */
    private int[] cellsOfRow(int row) {
        final TerminalEmulator emulator = mTerminalView.mEmulator;
        final int[] cells = new int[emulator.mColumns];
        Arrays.fill(cells, ' ');
        final TerminalBuffer screen = emulator.getScreen();
        final TerminalRow line = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row));
        final char[] text = line.mText;
        final int used = line.getSpaceUsed();
        int index = 0;
        for (int column = 0; column < cells.length && index < used; ) {
            final int codePoint = Character.codePointAt(text, index, used);
            final int width = WcWidth.width(codePoint);
            index += Character.charCount(codePoint);
            if (width <= 0) continue;
            cells[column] = codePoint;
            if (width == 2 && column + 1 < cells.length) cells[column + 1] = WordBoundary.WIDE_CONTINUATION;
            column += width;
        }
        return cells;
    }

    private void showMagnifier(float x, float y) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        if (mMagnifier == null) {
            final float density = mTerminalView.getResources().getDisplayMetrics().density;
            mMagnifier = new Magnifier.Builder(mTerminalView)
                .setSize((int) (240 * density), (int) (64 * density))
                .setInitialZoom(1.5f)
                .setDefaultSourceToMagnifierOffset(0, (int) (-96 * density))
                .build();
        }
        mMagnifier.show(x, y);
    }

    private void dismissMagnifier() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mMagnifier != null) mMagnifier.dismiss();
    }

    private void positionHandles(boolean forceOrientationCheck) {
        final int topRow = mTerminalView.getTopRow();
        mStartHandle.positionAtCursor(mStartColumn, mStartRow + topRow, forceOrientationCheck);
        mEndHandle.positionAtCursor(mEndColumn + 1, mEndRow + topRow, forceOrientationCheck);
    }

    /** A handle was let go. */
    public void onHandleTouchFinished() {
        dismissMagnifier();
    }

    @Override
    public void updatePosition(TextSelectionHandleView handle, int x, int y) {
        if (mState != State.HOLDING || mTerminalView.mEmulator == null) return;

        final float fontWidth = mTerminalView.mRenderer.getFontWidth();
        final float lineSpacing = mTerminalView.mRenderer.getFontLineSpacing();
        // A handle hangs below its row, and the column of the end handle is the one before it.
        int row = clampRow(Math.round((y + mHandleHeight * 0.3f) / lineSpacing) - 1);

        if (handle == mStartHandle) {
            int column = clampColumn(Math.round(x / fontWidth));
            if (isBefore(mEndRow, mEndColumn, row, column)) {
                row = mEndRow;
                column = mEndColumn;
            }
            if (row == mStartRow && column == mStartColumn) return;
            mStartRow = row;
            mStartColumn = column;
            dragTo(Anchor.END, mEndRow, mEndColumn, row, column);
        } else {
            int column = clampColumn(Math.round(x / fontWidth) - 1);
            if (isBefore(row, column, mStartRow, mStartColumn)) {
                row = mStartRow;
                column = mStartColumn;
            }
            if (row == mEndRow && column == mEndColumn) return;
            mEndRow = row;
            mEndColumn = column;
            dragTo(Anchor.START, mStartRow, mStartColumn, row, column);
        }

        showMagnifier((mLastColumn + 0.5f) * fontWidth, (mLastRow + 0.5f) * lineSpacing);
        mTerminalView.invalidate();
    }

    /** Select by characters from one end of the selection, which stays in place, to the other. */
    private void dragTo(Anchor anchor, int anchorRow, int anchorColumn, int row, int column) {
        if (mAnchor != anchor) {
            clearSelection();
            // Pressing somewhere else first keeps Herdr from seeing another double click.
            final int columns = mTerminalView.mEmulator.mColumns;
            final int away = anchorColumn + PLAIN_PRESS_DISTANCE < columns ? anchorColumn + PLAIN_PRESS_DISTANCE : anchorColumn - PLAIN_PRESS_DISTANCE;
            sendMouse(TerminalEmulator.MOUSE_LEFT_BUTTON, clampColumn(away), anchorRow, true);
            sendMouse(TerminalEmulator.MOUSE_LEFT_BUTTON, anchorColumn, anchorRow, true);
            mAnchor = anchor;
        }
        if (row == anchorRow && column == anchorColumn) {
            // The program selects nothing until the mouse has left the pressed cell.
            final int beside = column + 1 < mTerminalView.mEmulator.mColumns ? column + 1 : column - 1;
            sendMouse(TerminalEmulator.MOUSE_LEFT_BUTTON_MOVED, beside, row, true);
        }
        mLastRow = row;
        mLastColumn = column;
        sendMouse(TerminalEmulator.MOUSE_LEFT_BUTTON_MOVED, column, row, true);
    }

    private void startActionMode() {
        //noinspection NewApi
        mActionMode = mTerminalView.startActionMode(new ActionMode.Callback2() {
            @Override
            public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                menu.add(Menu.NONE, ACTION_COPY, Menu.NONE, R.string.mouse_selection_copy)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS | MenuItem.SHOW_AS_ACTION_WITH_TEXT);
                return true;
            }

            @Override
            public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                return false;
            }

            @Override
            public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                if (item.getItemId() == ACTION_COPY && isHolding()) copy();
                return true;
            }

            @Override
            public void onDestroyActionMode(ActionMode mode) {
                if (mode == mActionMode) mActionMode = null;
            }

            @Override
            public void onGetContentRect(ActionMode mode, View view, Rect outRect) {
                final float fontWidth = mTerminalView.mRenderer.getFontWidth();
                final float lineSpacing = mTerminalView.mRenderer.getFontLineSpacing();
                int left = Math.round(Math.min(mStartColumn, mEndColumn) * fontWidth);
                int right = Math.round((Math.max(mStartColumn, mEndColumn) + 1) * fontWidth);
                int top = Math.round((mStartRow - 1) * lineSpacing) + mHandleHeight;
                int bottom = Math.round((mEndRow + 1) * lineSpacing) + mHandleHeight;
                final int terminalBottom = mTerminalView.getBottom();
                outRect.set(left, Math.min(top, terminalBottom), right, Math.min(bottom, terminalBottom));
            }
        }, ActionMode.TYPE_FLOATING);
    }

    @Override
    public void show(MotionEvent event) {
        startDrag(event);
    }

    @Override
    public boolean hide() {
        if (mState == State.IDLE) return false;
        cancel();
        return true;
    }

    @Override
    public void render() {
        if (mState != State.HOLDING) return;
        if (mHandlesShown) positionHandles(false);
        if (mActionMode != null) mActionMode.invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return false;
    }

    @Override
    public void onTouchModeChanged(boolean isInTouchMode) {
    }

    @Override
    public void onDetached() {
        mTerminalView.removeCallbacks(mCopyTimeout);
        cancel();
    }

    @Override
    public boolean isActive() {
        return mState != State.IDLE;
    }
}
