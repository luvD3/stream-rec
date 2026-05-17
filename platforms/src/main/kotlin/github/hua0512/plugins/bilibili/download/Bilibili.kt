/*
 * MIT License
 *
 * Stream-rec  https://github.com/hua0512/stream-rec
 *
 * Copyright (c) 2026 hua0512 (https://github.com/hua0512)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package github.hua0512.plugins.bilibili.download

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import github.hua0512.app.App
import github.hua0512.data.config.AppConfig
import github.hua0512.data.config.DownloadConfig.BilibiliDownloadConfig
import github.hua0512.data.media.VideoFormat
import github.hua0512.data.stream.StreamInfo
import github.hua0512.plugins.base.ExtractorError
import github.hua0512.plugins.danmu.base.Danmu
import github.hua0512.plugins.download.base.PlatformDownloader
import github.hua0512.utils.info
import github.hua0512.utils.warn

/**
 * Bilibili platform downloader.
 */
class Bilibili(app: App, danmu: Danmu, override val extractor: BilibiliExtractor) :
  PlatformDownloader<BilibiliDownloadConfig>(app, danmu, extractor) {

  override fun getProgramArgs(): List<String> = emptyList()

  override fun onConfigUpdated(config: AppConfig) {
    super.onConfigUpdated(config)
  }

  override suspend fun applyFilters(streams: List<StreamInfo>): Result<StreamInfo, ExtractorError> {
    if (streams.isEmpty()) return Err(ExtractorError.NoStreamsFound)

    val preferredFormat = downloadConfig.sourceFormat ?: app.config.bilibiliConfig.sourceFormat ?: VideoFormat.flv
    val formatStreams = streams.filter { it.format == preferredFormat }.ifEmpty {
      warn("No Bilibili stream found for format {}, falling back to flv", preferredFormat)
      streams.filter { it.format == VideoFormat.flv }
    }.ifEmpty {
      warn("No Bilibili flv stream found, using all available formats")
      streams
    }

    val quality = downloadConfig.quality ?: app.config.bilibiliConfig.quality
    val qualityStreams = quality?.let { selectedQuality ->
      formatStreams.filter { it.currentQn() <= selectedQuality.qn }.ifEmpty {
        warn(
          "No Bilibili stream found under quality {}, using the best available",
          selectedQuality.qn,
        )
        formatStreams
      }
    } ?: formatStreams

    val selected = qualityStreams.maxWith(
      compareBy<StreamInfo> { it.currentQn() }
        .thenBy { it.bitrate }
        .thenBy { it.avcRank() }
    )
    info(
      "selected Bilibili stream, target quality: {}, actual qn: {}, format: {}, codec: {}, bitrate: {}",
      quality?.qn ?: "auto",
      selected.currentQn(),
      selected.format,
      selected.extras["codec_name"] ?: selected.extras["codec"],
      selected.bitrate,
    )

    return Ok(selected)
  }

  private fun StreamInfo.currentQn(): Int =
    extras["current_qn"]?.toIntOrNull() ?: extras["qn"]?.toIntOrNull() ?: 0

  private fun StreamInfo.avcRank(): Int =
    if ((extras["codec_name"] ?: extras["codec"]).equals("avc", ignoreCase = true)) 1 else 0
}
