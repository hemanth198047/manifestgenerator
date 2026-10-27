package com.example.casc.output;

import com.example.casc.domain.CasCManifest;

public interface ManifestSerializer {
    /** Lower-case format id, e.g. "json". */
    String format();

    String fileExtension();

    String contentType();

    /** Returns text using LF line endings and ending with a single newline. */
    String serialize(CasCManifest manifest);
}
