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

/**
 * Thrown when the node process backing a {@link Bridge} exits before answering a request.
 * <p>
 * The detail message carries the exit code and whatever the process wrote to its standard error stream, which is
 * normally the only record of why it died. It extends {@link IllegalStateException} so that existing callers which
 * expect that type continue to work.
 */
public final class NodeProcessCrashedException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    private final Integer exitCode;
    private final String standardError;

    public NodeProcessCrashedException(String message, Integer exitCode, String standardError) {
        super(message);

        this.exitCode = exitCode;
        this.standardError = standardError;
    }

    /**
     * Returns the exit code of the node process, or null if it could not be determined.
     *
     * @return the exit code, or null if the process had not exited yet
     */
    public Integer getExitCode() {
        return this.exitCode;
    }

    /**
     * Returns the tail of the standard error stream of the node process.
     *
     * @return the retained stderr output, never null but possibly empty
     */
    public String getStandardError() {
        return this.standardError;
    }
}
