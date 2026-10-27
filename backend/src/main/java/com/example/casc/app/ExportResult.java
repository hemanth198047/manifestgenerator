package com.example.casc.app;

import com.example.casc.profile.SchemaProfile;
import com.example.casc.validation.ValidationReport;

/** content is always produced; exportable is false while the report contains errors. */
public record ExportResult(String format, String content, ValidationReport report, boolean exportable,
                           String validationLevel, SchemaProfile profile, String fileExtension, String contentType) {}
