package com.example.casc.ingest;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * One named source: UTF-8 text, or raw bytes for binary formats (spreadsheets, Word, PDF), or a read error that explains why
 * neither is available. Names are labels (file name or relative path using '/'); they are never used to open files.
 */
public record SourceInput(String name, String text, String readError, byte[] bytes) {

    public static SourceInput of(String name, String text) {
        return new SourceInput(name, text, null, null);
    }

    public static SourceInput failed(String name, String error) {
        return new SourceInput(name, null, error, null);
    }

    /** A binary source. The ingestion service routes it to a reader for its extension or reports that none is installed. */
    public static SourceInput binary(String name, byte[] bytes) {
        return new SourceInput(name, null, null, bytes);
    }

    /** A binary source from the Base64 text used to carry files in JSON and HTML forms. Throws IllegalArgumentException if it is not Base64. */
    public static SourceInput fromBase64(String name, String base64) {
        try {
            return binary(name, java.util.Base64.getDecoder().decode(base64.replaceAll("\\s+", "")));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("The file content of '" + name + "' is not valid Base64");
        }
    }

    /** Base64 of the bytes (binary sources only), for forms and project files. */
    public String base64() {
        return bytes == null ? "" : java.util.Base64.getEncoder().encodeToString(bytes);
    }

    public boolean isBinary() {
        return bytes != null;
    }

    /** Characters for text sources, bytes for binary ones: used for the size limit. */
    public long size() {
        return bytes != null ? bytes.length : text == null ? 0 : text.length();
    }

    /** Strict UTF-8 decoding; a leading byte-order mark is removed. Invalid bytes make the source unreadable. */
    public static SourceInput fromBytes(String name, byte[] bytes) {
        try {
            String s = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            if (s.startsWith("\uFEFF")) {
                s = s.substring(1);
            }
            return of(name, s);
        } catch (CharacterCodingException e) {
            return failed(name, "The file is not valid UTF-8 text. Save it as UTF-8 and add it again.");
        }
    }
}
