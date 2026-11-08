package com.termux.terminal;

public class DesktopNotificationsTest extends TerminalTestCase {

    public void testKittyTitleAndBodyInTwoParts() {
        // As sent by herdr.
        withTerminalSized(10, 3).enterString("\033]99;i=1:d=0;claude finished\033\\\033]99;i=1:p=body;work · 2\033\\");
        assertEquals(1, mOutput.notifications.size());
        TerminalNotification n = mOutput.notifications.get(0);
        assertEquals("1", n.id);
        assertEquals("claude finished", n.title);
        assertEquals("work · 2", n.body);
        assertEquals(TerminalNotification.OCCASION_ALWAYS, n.occasion);
        assertNull(n.activationReport());
    }

    public void testKittyTitleOnly() {
        withTerminalSized(10, 3).enterString("\033]99;;Hello\007");
        assertEquals(1, mOutput.notifications.size());
        assertEquals("", mOutput.notifications.get(0).id);
        assertEquals("Hello", mOutput.notifications.get(0).title);
        assertEquals("", mOutput.notifications.get(0).body);
    }

    public void testKittyNotShownBeforeDone() {
        withTerminalSized(10, 3).enterString("\033]99;i=a:d=0;Part \033\\");
        assertEquals(0, mOutput.notifications.size());
        enterString("\033]99;i=a:d=0;one\033\\\033]99;i=a;\033\\");
        assertEquals(1, mOutput.notifications.size());
        assertEquals("Part one", mOutput.notifications.get(0).title);
    }

    public void testKittyOptions() {
        withTerminalSized(10, 3).enterString("\033]99;i=x:a=report,-focus:o=unfocused:u=2;Done\033\\");
        TerminalNotification n = mOutput.notifications.get(0);
        assertTrue(n.reportActivation);
        assertFalse(n.focusOnActivation);
        assertEquals(TerminalNotification.OCCASION_UNFOCUSED, n.occasion);
        assertEquals(TerminalNotification.URGENCY_CRITICAL, n.urgency);
        assertEquals("\033]99;i=x;\033\\", n.activationReport());
    }

    public void testKittyQueryAndClose() {
        withTerminalSized(10, 3).enterString("\033]99;i=q:p=?;\033\\");
        assertEquals("\033]99;i=q:p=?;a=focus,report:o=always,unfocused,invisible:u=0,1,2:p=title,body,?,close\033\\",
            mOutput.getOutputAndClear());
        enterString("\033]99;i=q:p=close;\033\\");
        assertEquals(1, mOutput.closedNotifications.size());
        assertEquals("q", mOutput.closedNotifications.get(0));
        assertEquals(0, mOutput.notifications.size());
    }

    public void testKittyRejectsBadId() {
        withTerminalSized(10, 3).enterString("\033]99;i=a b;Hi\033\\");
        assertEquals(0, mOutput.notifications.size());
    }

    public void testOsc9() {
        withTerminalSized(10, 3).enterString("\033]9;Build done\007");
        assertEquals(1, mOutput.notifications.size());
        assertEquals("", mOutput.notifications.get(0).title);
        assertEquals("Build done", mOutput.notifications.get(0).body);
        // ConEmu progress and other numbered subcommands are not notifications.
        enterString("\033]9;4;1;50\007\033]9;4\007");
        assertEquals(1, mOutput.notifications.size());
    }

    public void testOsc777() {
        withTerminalSized(10, 3).enterString("\033]777;notify;Title;Body; with semicolon\033\\");
        assertEquals(1, mOutput.notifications.size());
        assertEquals("Title", mOutput.notifications.get(0).title);
        assertEquals("Body; with semicolon", mOutput.notifications.get(0).body);
        enterString("\033]777;preexec\033\\");
        assertEquals(1, mOutput.notifications.size());
    }

    public void testFocusEventsOnlyWhenRequested() {
        withTerminalSized(10, 3);
        mOutput.getOutputAndClear();
        mTerminal.reportFocusChange(false);
        assertEquals("", mOutput.getOutputAndClear());
        enterString("\033[?1004h");
        mTerminal.reportFocusChange(false);
        assertEquals("\033[O", mOutput.getOutputAndClear());
        mTerminal.reportFocusChange(true);
        assertEquals("\033[I", mOutput.getOutputAndClear());
        enterString("\033[?1004l");
        mTerminal.reportFocusChange(true);
        assertEquals("", mOutput.getOutputAndClear());
    }
}
