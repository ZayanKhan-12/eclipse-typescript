/*
 * Copyright 2026 Palantir Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.typescript.services;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;

/**
 * Stands in for bridge.js so that the crash handling in {@link Bridge} can be exercised without installing node.
 * <p>
 * It speaks the same line based protocol: a request arrives on stdin and a "RESULT: " line goes back on stdout. Each
 * mode reproduces one of the ways the real node process is known to fail.
 */
public final class FakeNodeProcess {

    /** Answers every request. */
    public static final String MODE_ECHO = "echo";

    /** Dies while answering the first request, the way an out of memory abort does. */
    public static final String MODE_OUT_OF_MEMORY = "out-of-memory";

    /** Dies with a plain stack trace on stderr. */
    public static final String MODE_CRASH = "crash";

    /** Dies once, then answers normally when restarted. */
    public static final String MODE_CRASH_ONCE = "crash-once";

    /** Fills the stderr pipe before answering anything, which deadlocks a reader that never drains it. */
    public static final String MODE_FLOOD_STDERR = "flood-stderr";

    /** The amount written by {@link #MODE_FLOOD_STDERR}; comfortably more than a 64 KB pipe buffer. */
    public static final int FLOOD_BYTES = 512 * 1024;

    public static final String EXIT_CODE_MARKER = "134";

    private static final String OUT_OF_MEMORY_OUTPUT =
            "<--- Last few GCs --->" + "\n"
                    + "  102415 ms: Mark-sweep 1385.1 (1457.4) -> 1385.1 (1457.4) MB, 1250.5 / 0.0 ms" + "\n"
                    + "FATAL ERROR: CALL_AND_RETRY_LAST Allocation failed - JavaScript heap out of memory";

    private static final String CRASH_OUTPUT =
            "TypeError: Cannot read property 'kind' of undefined" + "\n"
                    + "    at getSymbolAtLocation (bridge.js:12345:17)" + "\n"
                    + "    at Object.<anonymous> (bridge.js:99:5)";

    public static void main(String[] args) throws IOException {
        String mode = args[0];
        File marker = args.length > 1 ? new File(args[1]) : null;

        if (MODE_CRASH_ONCE.equals(mode)) {
            if (marker != null && marker.exists()) {
                echo();

                return;
            }

            if (marker != null && !marker.createNewFile()) {
                throw new IOException("Unable to create the marker file: " + marker);
            }

            die(CRASH_OUTPUT);
        } else if (MODE_OUT_OF_MEMORY.equals(mode)) {
            waitForRequest();

            die(OUT_OF_MEMORY_OUTPUT);
        } else if (MODE_CRASH.equals(mode)) {
            waitForRequest();

            die(CRASH_OUTPUT);
        } else if (MODE_FLOOD_STDERR.equals(mode)) {
            flood();

            echo();
        } else {
            echo();
        }
    }

    /*
     * Writes far more to stderr than a pipe buffer holds. Nothing after this runs unless the other side is reading the
     * stream, which is exactly the deadlock being guarded against.
     */
    private static void flood() {
        PrintStream stderr = System.err;
        String line = "warning: this line exists only to fill the stderr pipe buffer";

        for (int written = 0; written < FLOOD_BYTES; written += line.length() + 1) {
            stderr.println(line);
        }
        stderr.flush();
    }

    private static void echo() throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));

        String line = reader.readLine();
        while (line != null) {
            System.out.println("RESULT: \"ok\"");
            System.out.flush();

            line = reader.readLine();
        }
    }

    private static void waitForRequest() throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));

        reader.readLine();
    }

    /*
     * halt rather than exit so that no shutdown hooks run, which is how an aborting node process behaves.
     */
    private static void die(String standardError) {
        System.err.println(standardError);
        System.err.flush();

        Runtime.getRuntime().halt(Integer.parseInt(EXIT_CODE_MARKER));
    }

    private FakeNodeProcess() {
        // prevent instantiation
    }
}
