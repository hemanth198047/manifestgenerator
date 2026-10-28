package com.example.casc.agent;

/** A tool call that could not be carried out. code is one of INVALID_REQUEST, UNKNOWN_TOOL, UNAVAILABLE_FORMAT, INTERNAL. */
public class ToolException extends Exception {
    private static final long serialVersionUID = 1L;
    private final String code;

    public ToolException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
