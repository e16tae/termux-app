package com.termux.app;

import android.content.Context;

import androidx.annotation.Nullable;

import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * e16tae fork: runs user hook scripts in ~/bin, such as termux-attach-hook. Hooks let the user's
 * own setup decide what happens, e.g. upload a file to the machine they are connected to.
 */
public final class TermuxHooks {

    public static final String BIN_DIR_PATH = TermuxConstants.TERMUX_HOME_DIR_PATH + "/bin";

    private TermuxHooks() {
    }

    /** The hook file if it exists and is executable. */
    @Nullable
    public static File find(String name) {
        File hook = new File(BIN_DIR_PATH, name);
        return hook.canExecute() ? hook : null;
    }

    /**
     * Runs the hook in the Termux environment and returns the last non-empty line it printed, or
     * {@code null} if it printed nothing. Call it off the main thread.
     *
     * @throws IOException if it fails, exits with an error or times out.
     */
    @Nullable
    public static String run(Context context, File hook, long timeoutSeconds, String... args) throws Exception {
        // Through sh so that the hook's shebang is resolved like in a Termux shell.
        List<String> command = new ArrayList<>(Arrays.asList(TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/bin/sh",
            "-c", "exec \"$0\" \"$@\"", hook.getAbsolutePath()));
        command.addAll(Arrays.asList(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().clear();
        builder.environment().putAll(new TermuxShellEnvironment().getEnvironment(context, false));
        builder.directory(new File(TermuxConstants.TERMUX_HOME_DIR_PATH));
        Process process = builder.start();
        process.getOutputStream().close();

        final StringBuilder stderr = new StringBuilder();
        Thread errReader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) stderr.append(line).append('\n');
            } catch (IOException ignored) {
            }
        });
        errReader.start();

        String last = null;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) if (!line.trim().isEmpty()) last = line.trim();
        }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroy();
            throw new IOException(hook.getName() + " timed out");
        }
        errReader.join(1000);
        if (process.exitValue() != 0) {
            String err = stderr.toString().trim();
            int nl = err.lastIndexOf('\n');
            throw new IOException(hook.getName() + " failed (" + process.exitValue() + ")"
                + (err.isEmpty() ? "" : ": " + err.substring(nl + 1)));
        }
        return last;
    }
}
