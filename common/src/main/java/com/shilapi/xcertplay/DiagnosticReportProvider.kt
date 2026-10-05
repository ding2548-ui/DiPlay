package com.shilapi.xcertplay

import androidx.core.content.FileProvider

/** Separate provider identity so merged app manifests cannot broaden the report paths. */
class DiagnosticReportProvider : FileProvider()
