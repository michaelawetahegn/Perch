package dev.mkiros.perch.ui.article

import java.io.File

data class DocumentUi(
    val file: File,
    val pageCount: Int,
    val aspects: List<Float>,
    val sizeBytes: Long,
)
