package com.example.casc.mcp;

/** A problem with a tool call that the agent can fix (bad argument, path outside the allowed folder, ...). The message is shown to the agent. */
public final class ToolError extends Exception {
    public ToolError(String message) {
        super(message);
    }
}
