package com.example.casc.ingest;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * One named source. Either text is present, or readError explains why it could not be read.
 * Names are labels (file name or relative path using '/'); they are never used to open files.
 */
public record SourceInput(String name, String text, String readError) {

    public static SourceInput of(String name, String text) {
        return new SourceInput(name, text, null);
    }

    public static SourceInput failed(String name, String error) {
        return new SourceInput(name, null, error);
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
