/*
 * Copyright 2013 Palantir Technologies, Inc.
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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.google.common.base.Charsets;
import com.google.common.base.Strings;

/**
 * This handles all requests that need to be handled by TypeScript's built in language services.
 *
 * @author tyleradams
 */
public final class Bridge {

    private static final String LINE_SEPARATOR = System.getProperty("line.separator");
    private static final String ERROR_PREFIX = "ERROR: ";
    private static final String RESULT_PREFIX = "RESULT: ";

    /**
     * The number of characters of the node process's standard error stream kept for crash diagnostics.
     */
    private static final int MAX_RETAINED_STDERR_CHARS = 8192;

    /**
     * The number of characters of a request included in a crash report.
     */
    private static final int MAX_REPORTED_REQUEST_CHARS = 1024;

    /**
     * The time to wait for a crashed node process to report its exit code and flush its standard error stream.
     */
    private static final long PROCESS_EXIT_TIMEOUT_MILLIS = 2000L;
    private static final long PROCESS_EXIT_POLL_MILLIS = 20L;

    /**
     * The number of times the node process is restarted before the bridge stops trying. The count is reset as soon as a
     * request succeeds, so this only gives up when the process dies repeatedly without making progress.
     */
    private static final int MAX_CONSECUTIVE_RESTARTS = 3;

    private static final Pattern OUT_OF_MEMORY_PATTERN = Pattern.compile(
        "JavaScript heap out of memory|process out of memory|CALL_AND_RETRY_LAST|Allocation failed",
        Pattern.CASE_INSENSITIVE);

    private final String endpointName;
    private final NodeProcessLauncher launcher;
    private final boolean retryRequestAfterRestart;
    private final ObjectMapper mapper;

    private Process nodeProcess;
    private BufferedReader nodeStdout;
    private PrintWriter nodeStdin;
    private StandardErrorReader standardErrorReader;
    private Thread shutdownHook;
    private Runnable restartListener;
    private int consecutiveRestarts;
    private boolean disposed;

    public Bridge(String endpointName) {
        this(endpointName, false);
    }

    /**
     * Creates a bridge for the given endpoint.
     *
     * @param endpointName the name of the endpoint, used in log and error messages
     * @param retryRequestAfterRestart whether a request may be replayed against a freshly started node process. This is
     *        only safe for endpoints which keep no state in the node process
     */
    public Bridge(String endpointName, boolean retryRequestAfterRestart) {
        this(endpointName, new EclipseNodeProcessLauncher(), retryRequestAfterRestart);
    }

    public Bridge(String endpointName, NodeProcessLauncher launcher, boolean retryRequestAfterRestart) {
        checkNotNull(endpointName);
        checkNotNull(launcher);

        this.endpointName = endpointName;
        this.launcher = launcher;
        this.retryRequestAfterRestart = retryRequestAfterRestart;
        this.mapper = new ObjectMapper();

        // start the node process
        this.start();
    }

    /**
     * Sets the callback used to re-establish any state the node process was holding after it has been restarted.
     *
     * @param listener the callback, or null to remove the current one
     */
    public synchronized void setRestartListener(Runnable listener) {
        this.restartListener = listener;
    }

    public <T> T call(Request request, Class<T> resultType) {
        checkNotNull(request);
        checkNotNull(resultType);

        JavaType type = TypeFactory.defaultInstance().uncheckedSimpleType(resultType);

        return this.call(request, type);
    }

    public synchronized <T> T call(Request request, JavaType resultType) {
        checkNotNull(request);
        checkNotNull(resultType);
        checkState(!this.disposed, "The bridge for the %s endpoint has been disposed.", this.endpointName);

        // process the request
        String resultJson;
        try {
            String requestJson = this.mapper.writeValueAsString(request);

            resultJson = this.processRequestAndRecover(requestJson);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // convert the JSON result into a Java object
        try {
            return this.mapper.readValue(resultJson, resultType);
        } catch (IOException e) {
            throw new RuntimeException("Error parsing result: " + resultJson, e);
        }
    }

    public synchronized void dispose() {
        if (this.disposed) {
            return;
        }
        this.disposed = true;

        this.shutdown();
    }

    /**
     * Returns whether the node process is currently running.
     *
     * @return true if a node process is running
     */
    synchronized boolean isRunning() {
        return this.nodeProcess != null && this.awaitExitCode(0L) == null;
    }

    /*
     * Runs a request and, if the node process dies, restarts it so that the rest of the session keeps working. A dead
     * process used to leave every subsequent request failing until Eclipse was restarted.
     */
    private String processRequestAndRecover(String requestJson) throws IOException {
        try {
            String resultJson = this.processRequest(requestJson);

            // the process answered, so it is healthy again
            this.consecutiveRestarts = 0;

            return resultJson;
        } catch (NodeProcessCrashedException e) {
            this.restartAfterCrash(e);

            // replaying the request is only safe when the node process holds no state for this endpoint
            if (!this.retryRequestAfterRestart) {
                throw e;
            }

            String resultJson = this.processRequest(requestJson);
            this.consecutiveRestarts = 0;

            return resultJson;
        }
    }

    private String processRequest(String requestJson) throws IOException {
        checkNotNull(requestJson);

        // a restart which failed to launch leaves no streams behind
        if (this.nodeStdin == null || this.nodeStdout == null) {
            throw this.createCrashedException(requestJson);
        }

        // write the request JSON to the bridge's stdin
        this.nodeStdin.println(requestJson);

        // writing to a process which has already died fails silently, so check for it explicitly
        if (this.nodeStdin.checkError()) {
            throw this.createCrashedException(requestJson);
        }

        // read the response JSON from the bridge's stdout
        String resultJson = null;
        do {
            String line = this.nodeStdout.readLine();

            // process errors and logger statements
            if (line == null) {
                throw this.createCrashedException(requestJson);
            } else if (line.startsWith(ERROR_PREFIX)) {
                // remove prefix
                line = line.substring(ERROR_PREFIX.length(), line.length());
                // put newlines back
                line = line.replaceAll("\\\\n", LINE_SEPARATOR); // put newlines back
                // replace soft tabs with hardtabs to match Java's error stack trace.
                line = line.replaceAll("    ", "\t");

                throw new RuntimeException("The following request caused an error to be thrown:" + LINE_SEPARATOR
                        + requestJson + LINE_SEPARATOR
                        + line);
            } else if (line.startsWith(RESULT_PREFIX)) {
                resultJson = line.substring(RESULT_PREFIX.length());
            } else { // log statement
                System.out.println(this.endpointName + ": " + line);
            }
        } while (resultJson == null);

        return resultJson;
    }

    /*
     * Builds the crash report. Without the exit code and the standard error stream there is nothing to go on, which is
     * what made these crashes so hard to diagnose.
     */
    private NodeProcessCrashedException createCrashedException(String requestJson) {
        Integer exitCode = this.awaitExitCode(PROCESS_EXIT_TIMEOUT_MILLIS);

        // the process is gone, so wait for the last of its output to arrive before reporting it
        String standardError = "";
        if (this.standardErrorReader != null) {
            this.standardErrorReader.awaitCompletion(PROCESS_EXIT_TIMEOUT_MILLIS);
            standardError = this.standardErrorReader.getRetainedOutput();
        }

        StringBuilder message = new StringBuilder();
        message.append("The node process has crashed.");
        message.append(LINE_SEPARATOR);
        message.append(LINE_SEPARATOR);
        message.append("Endpoint: ").append(this.endpointName).append(LINE_SEPARATOR);
        message.append("Command: ").append(this.launcher.describeCommand()).append(LINE_SEPARATOR);
        message.append("Exit code: ").append(exitCode != null ? exitCode.toString() : "unknown").append(LINE_SEPARATOR);
        message.append("Request: ").append(abbreviate(requestJson, MAX_REPORTED_REQUEST_CHARS)).append(LINE_SEPARATOR);
        message.append(LINE_SEPARATOR);

        if (!Strings.isNullOrEmpty(standardError)) {
            message.append("Output written by the node process to stderr:").append(LINE_SEPARATOR);
            message.append(standardError);
        } else {
            message.append("The node process did not write anything to stderr.").append(LINE_SEPARATOR);
        }

        String hint = createHint(standardError);
        if (hint != null) {
            message.append(LINE_SEPARATOR);
            message.append(hint).append(LINE_SEPARATOR);
        }

        return new NodeProcessCrashedException(message.toString(), exitCode, standardError);
    }

    private static String createHint(String standardError) {
        if (standardError != null && OUT_OF_MEMORY_PATTERN.matcher(standardError).find()) {
            return "Hint: the node process ran out of memory. Large projects can exceed the default heap, which is"
                    + " around 1.5 GB on 64-bit node. Raise it by setting the 'Node arguments' preference (TypeScript >"
                    + " General) to something like --max-old-space-size=4096, and make sure folders such as"
                    + " node_modules are excluded from the TypeScript build path.";
        }

        return null;
    }

    private static String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }

        return value.substring(0, maxLength) + "... (" + (value.length() - maxLength) + " more characters)";
    }

    /*
     * Waits up to the given time for the process to exit. Process.waitFor(long, TimeUnit) is not available on the Java 6
     * runtime this plug-in supports, so the exit code is polled instead.
     */
    private Integer awaitExitCode(long timeoutMillis) {
        Process process = this.nodeProcess;
        if (process == null) {
            return null;
        }

        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (true) {
            try {
                return Integer.valueOf(process.exitValue());
            } catch (IllegalThreadStateException e) {
                if (System.currentTimeMillis() >= deadline) {
                    return null;
                }

                try {
                    Thread.sleep(PROCESS_EXIT_POLL_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();

                    return null;
                }
            }
        }
    }

    private void restartAfterCrash(NodeProcessCrashedException crash) {
        if (this.consecutiveRestarts >= MAX_CONSECUTIVE_RESTARTS) {
            throw crash;
        }
        this.consecutiveRestarts++;

        this.shutdown();

        try {
            this.start();
        } catch (RuntimeException e) {
            // the original crash explains what went wrong; report that instead
            System.err.println(this.endpointName + ": unable to restart the node process: " + e.getMessage());

            throw crash;
        }

        // let the endpoint rebuild whatever state the dead process was holding
        Runnable listener = this.restartListener;
        if (listener != null) {
            int restarts = this.consecutiveRestarts;
            try {
                listener.run();
            } catch (RuntimeException e) {
                System.err.println(this.endpointName + ": unable to reinitialize the node process: " + e.getMessage());

                throw crash;
            } finally {
                // the listener issues its own requests; they must not refill the restart budget
                this.consecutiveRestarts = restarts;
            }
        }
    }

    private void start() {
        Process process;
        try {
            process = this.launcher.launch();
        } catch (IOException e) {
            throw new RuntimeException("Unable to start the node process: " + this.launcher.describeCommand(), e);
        }

        this.nodeProcess = process;
        this.nodeStdout = new BufferedReader(new InputStreamReader(process.getInputStream(), Charsets.UTF_8));
        this.nodeStdin = new PrintWriter(new OutputStreamWriter(process.getOutputStream(), Charsets.UTF_8), true);

        // drain stderr continuously: node blocks forever once the pipe buffer fills up, and its contents are the only
        // explanation available when the process dies
        this.standardErrorReader = new StandardErrorReader(this.endpointName, process.getErrorStream());
        this.standardErrorReader.start();

        // destroy the node process in case its not properly disposed
        this.shutdownHook = new ShutdownHookThread(process);
        Runtime.getRuntime().addShutdownHook(this.shutdownHook);
    }

    private void shutdown() {
        if (this.shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(this.shutdownHook);
            } catch (IllegalStateException e) {
                // the JVM is already shutting down and the hook is about to run on its own
            }
            this.shutdownHook = null;
        }

        // closing stdin lets the bridge exit on its own
        if (this.nodeStdin != null) {
            this.nodeStdin.close();
            this.nodeStdin = null;
        }

        if (this.nodeStdout != null) {
            try {
                this.nodeStdout.close();
            } catch (IOException e) {
                // nothing useful can be done while shutting down
            }
            this.nodeStdout = null;
        }

        // destroy the process as well, otherwise a wedged node process is left behind for the life of the workbench
        if (this.nodeProcess != null) {
            this.nodeProcess.destroy();
            this.nodeProcess = null;
        }

        if (this.standardErrorReader != null) {
            this.standardErrorReader.awaitCompletion(PROCESS_EXIT_TIMEOUT_MILLIS);
            this.standardErrorReader = null;
        }
    }

    /**
     * Continuously reads the standard error stream of the node process, keeping the tail of it for crash reports.
     */
    private static final class StandardErrorReader extends Thread {

        private final String endpointName;
        private final BufferedReader reader;
        private final StringBuilder retained;

        StandardErrorReader(String endpointName, InputStream stream) {
            super("TypeScript bridge stderr (" + endpointName + ")");

            this.endpointName = endpointName;
            this.reader = new BufferedReader(new InputStreamReader(stream, Charsets.UTF_8));
            this.retained = new StringBuilder();

            this.setDaemon(true);
        }

        @Override
        public void run() {
            try {
                String line = this.reader.readLine();
                while (line != null) {
                    this.retain(line);

                    System.err.println(this.endpointName + ": " + line);

                    line = this.reader.readLine();
                }
            } catch (IOException e) {
                // the process died or its streams were closed - there is nothing left to read
            } finally {
                try {
                    this.reader.close();
                } catch (IOException e) {
                    // best effort
                }
            }
        }

        String getRetainedOutput() {
            synchronized (this.retained) {
                return this.retained.toString();
            }
        }

        void awaitCompletion(long timeoutMillis) {
            try {
                this.join(timeoutMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void retain(String line) {
            synchronized (this.retained) {
                this.retained.append(line).append(LINE_SEPARATOR);

                // only the tail is kept; the end of the stream is where the crash is described
                int excess = this.retained.length() - MAX_RETAINED_STDERR_CHARS;
                if (excess > 0) {
                    this.retained.delete(0, excess);
                }
            }
        }
    }

    private static final class ShutdownHookThread extends Thread {

        private final Process process;

        ShutdownHookThread(Process process) {
            this.process = process;
        }

        @Override
        public void run() {
            this.process.destroy();
        }
    }
}
