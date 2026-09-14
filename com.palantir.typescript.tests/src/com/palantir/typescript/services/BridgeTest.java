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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Tests for {@link Bridge}, in particular what happens when the node process dies.
 *
 * @see <a href="https://github.com/palantir/eclipse-typescript/issues/354">issue 354</a>
 */
public final class BridgeTest {

    private static final int TIMEOUT_MILLIS = 60000;
    private static final String ENDPOINT = "test-endpoint";

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private Bridge bridge;

    @After
    public void after() {
        if (this.bridge != null) {
            this.bridge.dispose();
            this.bridge = null;
        }
    }

    @Test(timeout = TIMEOUT_MILLIS)
    public void testRequestIsAnswered() {
        this.bridge = createBridge(FakeNodeProcess.MODE_ECHO, null, false);

        assertEquals("ok", this.bridge.call(createRequest(), String.class));
    }

    /**
     * The crash report used to be the bare sentence "The node process has crashed", which left nothing to debug.
     */
    @Test(timeout = TIMEOUT_MILLIS)
    public void testCrashReportContainsTheExitCodeAndStandardError() {
        this.bridge = createBridge(FakeNodeProcess.MODE_CRASH, null, false);

        NodeProcessCrashedException exception = callExpectingCrash(this.bridge);

        assertEquals(Integer.valueOf(FakeNodeProcess.EXIT_CODE_MARKER), exception.getExitCode());
        assertTrue(exception.getStandardError().contains("Cannot read property 'kind' of undefined"));

        String message = exception.getMessage();
        assertTrue(message, message.contains("The node process has crashed."));
        assertTrue(message, message.contains("Endpoint: " + ENDPOINT));
        assertTrue(message, message.contains("Exit code: " + FakeNodeProcess.EXIT_CODE_MARKER));
        assertTrue(message, message.contains("getSymbolAtLocation"));
        assertTrue(message, message.contains(FakeNodeProcess.MODE_CRASH));
    }

    /**
     * Running out of heap is the usual cause on large projects, so the report says how to raise it.
     */
    @Test(timeout = TIMEOUT_MILLIS)
    public void testCrashReportSuggestsRaisingTheHeapAfterAnOutOfMemory() {
        this.bridge = createBridge(FakeNodeProcess.MODE_OUT_OF_MEMORY, null, false);

        NodeProcessCrashedException exception = callExpectingCrash(this.bridge);

        String message = exception.getMessage();
        assertTrue(message, message.contains("JavaScript heap out of memory"));
        assertTrue(message, message.contains("--max-old-space-size"));
    }

    /**
     * A stateless endpoint can simply be asked again once the process is back.
     */
    @Test(timeout = TIMEOUT_MILLIS)
    public void testStatelessEndpointRecoversFromACrash() {
        this.bridge = createBridge(FakeNodeProcess.MODE_CRASH_ONCE, this.newMarker(), true);

        assertEquals("ok", this.bridge.call(createRequest(), String.class));
    }

    /**
     * A stateful endpoint must not have its request replayed, but the session still has to keep working afterwards.
     */
    @Test(timeout = TIMEOUT_MILLIS)
    public void testStatefulEndpointRestartsWithoutReplayingTheRequest() {
        this.bridge = createBridge(FakeNodeProcess.MODE_CRASH_ONCE, this.newMarker(), false);

        callExpectingCrash(this.bridge);

        // the process was restarted, so the next request is served rather than failing until Eclipse is restarted
        assertEquals("ok", this.bridge.call(createRequest(), String.class));
    }

    @Test(timeout = TIMEOUT_MILLIS)
    public void testRestartListenerRunsAfterACrash() {
        this.bridge = createBridge(FakeNodeProcess.MODE_CRASH_ONCE, this.newMarker(), true);

        final boolean[] invoked = new boolean[1];
        this.bridge.setRestartListener(new Runnable() {
            @Override
            public void run() {
                invoked[0] = true;
            }
        });

        this.bridge.call(createRequest(), String.class);

        assertTrue("the endpoint was not given a chance to restore its state", invoked[0]);
    }

    /**
     * The language endpoint restores its state by issuing requests of its own, so the listener has to be reentrant.
     */
    @Test(timeout = TIMEOUT_MILLIS)
    public void testRestartListenerMayIssueRequests() {
        this.bridge = createBridge(FakeNodeProcess.MODE_CRASH_ONCE, this.newMarker(), true);

        final int[] calls = new int[1];
        this.bridge.setRestartListener(new Runnable() {
            @Override
            public void run() {
                calls[0]++;

                // this is what LanguageEndpoint does to re-send the lib contents
                BridgeTest.this.bridge.call(createRequest(), String.class);
            }
        });

        assertEquals("ok", this.bridge.call(createRequest(), String.class));
        assertEquals(1, calls[0]);
    }

    /**
     * Nothing used to read stderr, so a process which filled the pipe buffer hung forever instead of answering.
     */
    @Test(timeout = TIMEOUT_MILLIS)
    public void testLargeAmountsOfStandardErrorDoNotBlockTheProcess() {
        this.bridge = createBridge(FakeNodeProcess.MODE_FLOOD_STDERR, null, false);

        assertEquals("ok", this.bridge.call(createRequest(), String.class));
    }

    /**
     * Disposing used to close the streams and drop the reference without ever killing the process.
     */
    @Test(timeout = TIMEOUT_MILLIS)
    public void testDisposeDestroysTheNodeProcess() throws InterruptedException {
        FakeNodeProcessLauncher launcher = new FakeNodeProcessLauncher(FakeNodeProcess.MODE_ECHO, null);
        Bridge disposable = new Bridge(ENDPOINT, launcher, false);
        disposable.call(createRequest(), String.class);

        assertEquals(1, launcher.getProcesses().size());
        Process process = launcher.getProcesses().get(0);

        disposable.dispose();

        assertTrue("the node process outlived the bridge", awaitTermination(process));
        assertFalse(disposable.isRunning());
    }

    @Test(timeout = TIMEOUT_MILLIS)
    public void testDisposeIsIdempotent() {
        this.bridge = createBridge(FakeNodeProcess.MODE_ECHO, null, false);

        this.bridge.dispose();
        this.bridge.dispose();

        this.bridge = null;
    }

    @Test(timeout = TIMEOUT_MILLIS)
    public void testCallAfterDisposeIsRejected() {
        Bridge disposed = createBridge(FakeNodeProcess.MODE_ECHO, null, false);
        disposed.dispose();

        try {
            disposed.call(createRequest(), String.class);

            fail("a disposed bridge must not accept requests");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("disposed"));
        }
    }

    /**
     * A process which dies every time must not be restarted forever.
     */
    @Test(timeout = TIMEOUT_MILLIS)
    public void testRepeatedCrashesGiveUp() {
        FakeNodeProcessLauncher launcher = new FakeNodeProcessLauncher(FakeNodeProcess.MODE_CRASH, null);
        this.bridge = new Bridge(ENDPOINT, launcher, true);

        for (int i = 0; i < 10; i++) {
            try {
                this.bridge.call(createRequest(), String.class);

                fail("the request cannot succeed against a process which always dies");
            } catch (NodeProcessCrashedException expected) {
                assertNotNull(expected.getMessage());
            }
        }

        // the restarts are bounded rather than one per request
        assertTrue("too many restarts: " + launcher.getProcesses().size(), launcher.getProcesses().size() <= 8);
    }

    /**
     * A node process which cannot be launched at all must report that rather than fail with a null pointer.
     */
    @Test(timeout = TIMEOUT_MILLIS)
    public void testUnstartableProcessIsReported() {
        try {
            this.bridge = new Bridge(ENDPOINT, new UnstartableLauncher(), false);

            fail("the bridge cannot start without a node process");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("Unable to start the node process"));
        }
    }

    private static final class UnstartableLauncher implements NodeProcessLauncher {

        @Override
        public Process launch() throws IOException {
            throw new IOException("no such file or directory");
        }

        @Override
        public String describeCommand() {
            return "/does/not/exist/node bridge.js";
        }
    }

    private File newMarker() {
        return new File(this.temporaryFolder.getRoot(), "crashed-once");
    }

    private static Bridge createBridge(String mode, File marker, boolean retryAfterRestart) {
        return new Bridge(ENDPOINT, new FakeNodeProcessLauncher(mode, marker), retryAfterRestart);
    }

    private static Request createRequest() {
        return new Request("classifier", "getClassificationsForLines", "var x = 1;");
    }

    private static NodeProcessCrashedException callExpectingCrash(Bridge bridge) {
        try {
            bridge.call(createRequest(), String.class);

            fail("the node process was supposed to crash");

            return null;
        } catch (NodeProcessCrashedException e) {
            return e;
        }
    }

    private static boolean awaitTermination(Process process) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            try {
                process.exitValue();

                return true;
            } catch (IllegalThreadStateException e) {
                Thread.sleep(50);
            }
        }

        return false;
    }
}
