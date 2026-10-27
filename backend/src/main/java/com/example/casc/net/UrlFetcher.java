package com.example.casc.net;

/** Fetches one URL under a UrlPolicy. Implementations must apply the policy to every redirect hop. */
public interface UrlFetcher {

    /** body holds the raw bytes. displayUrl never contains a query string or credentials. */
    record Fetched(String displayUrl, String contentType, byte[] body) {}

    class FetchException extends Exception {
        private static final long serialVersionUID = 1L;

        public FetchException(String message) {
            super(message);
        }
    }

    Fetched fetch(String url) throws FetchException;

    /** True when URL sources can be used at all (an allowlist is configured). */
    boolean enabled();

    static UrlFetcher disabled() {
        return new UrlFetcher() {
            @Override
            public Fetched fetch(String url) throws FetchException {
                throw new FetchException("URL sources are disabled: no allowed hosts are configured "
                        + "(use --url-allow HOST or the CASC_URL_ALLOW environment variable).");
            }

            @Override
            public boolean enabled() {
                return false;
            }
        };
    }
}
