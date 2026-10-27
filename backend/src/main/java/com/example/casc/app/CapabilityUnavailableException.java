package com.example.casc.app;

/** Thrown when a requested capability is not available; the message explains what is needed. */
public class CapabilityUnavailableException extends Exception {
    private static final long serialVersionUID = 1L;

    public CapabilityUnavailableException(String message) {
        super(message);
    }
}
