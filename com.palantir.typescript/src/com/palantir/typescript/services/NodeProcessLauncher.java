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

import java.io.IOException;

/**
 * Starts the node process which hosts the TypeScript services.
 * <p>
 * This is the seam which keeps {@link Bridge} independent of the Eclipse runtime so that its crash handling can be
 * exercised by tests. Production code uses {@link EclipseNodeProcessLauncher}.
 */
public interface NodeProcessLauncher {

    /**
     * Starts a new node process.
     *
     * @return the newly started process
     * @throws IOException if the process cannot be started
     */
    Process launch() throws IOException;

    /**
     * Returns the command used to start the process, for use in diagnostics.
     *
     * @return a human readable description of the command
     */
    String describeCommand();
}
