@file:OptIn(UnstableApi::class)

package com.nuvio.app.features.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser

/**
 * Media3 reads a subtitle file as UTF-8, so an Arabic file saved as Windows-1256 shows as question
 * marks. libmpv detects the encoding by itself; this gives ExoPlayer the same result.
 */
internal class ReadableSubtitleParserFactory(
    private val delegate: SubtitleParser.Factory = DefaultSubtitleParserFactory(),
) : SubtitleParser.Factory by delegate {
    override fun create(format: Format): SubtitleParser {
        val parser = delegate.create(format)
        return if (format.sampleMimeType in TEXT_FORMATS) ReadableSubtitleParser(parser) else parser
    }

    private companion object {
        val TEXT_FORMATS = setOf(MimeTypes.APPLICATION_SUBRIP, MimeTypes.TEXT_SSA, MimeTypes.TEXT_VTT)
    }
}

internal class ReadableSubtitleParser(private val delegate: SubtitleParser) : SubtitleParser by delegate {
    override fun parse(
        data: ByteArray,
        offset: Int,
        length: Int,
        outputOptions: SubtitleParser.OutputOptions,
        output: Consumer<CuesWithTiming>,
    ) {
        val converted = subtitleText(data.copyOfRange(offset, offset + length)).converted
        if (converted != null) {
            delegate.parse(converted, 0, converted.size, outputOptions, output)
        } else {
            delegate.parse(data, offset, length, outputOptions, output)
        }
    }
}
