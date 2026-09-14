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
import java.io.UnsupportedEncodingException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.util.List;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

/**
 * Launches {@link FakeNodeProcess} in a separate JVM in place of node.
 */
public final class FakeNodeProcessLauncher implements NodeProcessLauncher {

    private final List<String> command;
    private final List<Process> processes;

    public FakeNodeProcessLauncher(String mode, File marker) {
        ImmutableList.Builder<String> builder = ImmutableList.builder();
        builder.add(getJavaExecutable());
        builder.add("-cp");
        builder.add(getClassPath());
        builder.add(FakeNodeProcess.class.getName());
        builder.add(mode);
        if (marker != null) {
            builder.add(marker.getAbsolutePath());
        }

        this.command = builder.build();
        this.processes = Lists.newArrayList();
    }

    @Override
    public Process launch() throws IOException {
        ProcessBuilder processBuilder = new ProcessBuilder(this.command.toArray(new String[this.command.size()]));
        Process process = processBuilder.start();

        synchronized (this.processes) {
            this.processes.add(process);
        }

        return process;
    }

    @Override
    public String describeCommand() {
        return Joiner.on(' ').join(this.command);
    }

    /**
     * Returns every process this launcher has started, oldest first.
     *
     * @return the started processes
     */
    public List<Process> getProcesses() {
        synchronized (this.processes) {
            return ImmutableList.copyOf(this.processes);
        }
    }

    private static String getJavaExecutable() {
        String name = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";

        return new File(new File(System.getProperty("java.home"), "bin"), name).getAbsolutePath();
    }

    /*
     * The location of the test classes is used rather than java.class.path so that this also works when the tests are
     * run from a bundle.
     */
    private static String getClassPath() {
        URL location = FakeNodeProcess.class.getProtectionDomain().getCodeSource().getLocation();

        try {
            return new File(location.toURI()).getAbsolutePath();
        } catch (URISyntaxException e) {
            try {
                return new File(URLDecoder.decode(location.getPath(), "UTF-8")).getAbsolutePath();
            } catch (UnsupportedEncodingException unsupported) {
                throw new RuntimeException(unsupported);
            }
        }
    }
}
