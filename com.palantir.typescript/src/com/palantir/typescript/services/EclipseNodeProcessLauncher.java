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

import java.io.File;
import java.io.IOException;
import java.util.List;

import org.eclipse.core.runtime.FileLocator;

import com.google.common.base.CharMatcher;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.palantir.typescript.IPreferenceConstants;
import com.palantir.typescript.TypeScriptPlugin;

/**
 * Starts the node process using the locations configured in the TypeScript preferences.
 */
public final class EclipseNodeProcessLauncher implements NodeProcessLauncher {

    /*
     * Enables using node-inspector for debugging the Eclipse plugin. Make sure you
     * `npm install -g node-inspector` first, and set NODE_DEBUG_PATH properly
     * (found by running `which node-debug`).
     */
    private static final boolean NODE_DEBUG = false;
    private static final String NODE_DEBUG_PATH = "/usr/local/bin/node-debug";
    private static final boolean NODE_DEBUG_BREAK_ON_START = false;
    // not final so it can be incremented and each process can have its own port.
    private static int NODE_DEBUG_PORT = 5858;

    private List<String> command;

    @Override
    public Process launch() throws IOException {
        List<String> args = this.getCommand();
        ProcessBuilder processBuilder = new ProcessBuilder(args.toArray(new String[args.size()]));

        return processBuilder.start();
    }

    @Override
    public String describeCommand() {
        return Joiner.on(' ').join(this.getCommand());
    }

    /*
     * The command is computed once so that the debug ports stay stable when the process is restarted after a crash.
     */
    private synchronized List<String> getCommand() {
        if (this.command == null) {
            this.command = createCommand();
        }

        return this.command;
    }

    private static List<String> createCommand() {
        String nodePath = getPreference(IPreferenceConstants.GENERAL_NODE_PATH);
        if (Strings.isNullOrEmpty(nodePath)) {
            throw new IllegalStateException(
                "Node.js could not be found.  If it is installed to a location not on the PATH, please specify the location in the TypeScript preferences.");
        }

        // get the path to the bridge.js file
        File bundleFile;
        try {
            bundleFile = FileLocator.getBundleFile(TypeScriptPlugin.getDefault().getBundle());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        File bridgeFile = new File(bundleFile, "bin/bridge.js");
        String bridgePath = bridgeFile.getAbsolutePath();

        // construct the arguments
        ImmutableList.Builder<String> argsBuilder = ImmutableList.builder();
        argsBuilder.add(nodePath);
        if (NODE_DEBUG) {
            argsBuilder.add(NODE_DEBUG_PATH);
            if (!NODE_DEBUG_BREAK_ON_START) {
                argsBuilder.add("--no-debug-brk");
            }
            argsBuilder.add("--web-port=" + Integer.toString(NODE_DEBUG_PORT++));
            argsBuilder.add("--debug-port=" + Integer.toString(NODE_DEBUG_PORT++));
        }

        // the user supplied arguments go before the script so that options such as --max-old-space-size are seen by node
        argsBuilder.addAll(getNodeArguments());

        argsBuilder.add(bridgePath);

        return argsBuilder.build();
    }

    private static List<String> getNodeArguments() {
        String nodeArguments = getPreference(IPreferenceConstants.GENERAL_NODE_ARGUMENTS);
        if (Strings.isNullOrEmpty(nodeArguments)) {
            return ImmutableList.of();
        }

        Splitter splitter = Splitter.on(CharMatcher.whitespace()).trimResults().omitEmptyStrings();

        return ImmutableList.copyOf(splitter.split(nodeArguments));
    }

    private static String getPreference(String name) {
        return TypeScriptPlugin.getDefault().getPreferenceStore().getString(name);
    }
}
