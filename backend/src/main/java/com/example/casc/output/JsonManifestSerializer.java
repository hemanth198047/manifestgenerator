package com.example.casc.output;

import com.example.casc.domain.CasCManifest;
import com.example.casc.json.Json;
import com.example.casc.manifest.ManifestMapper;

public final class JsonManifestSerializer implements ManifestSerializer {
    @Override public String format() { return "json"; }

    @Override public String fileExtension() { return "json"; }

    @Override public String contentType() { return "application/json; charset=UTF-8"; }

    @Override
    public String serialize(CasCManifest manifest) {
        return Json.write(ManifestMapper.toTree(manifest)) + "\n";
    }
}
