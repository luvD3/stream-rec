/*
 * MIT License
 *
 * Stream-rec  https://github.com/hua0512/stream-rec
 *
 * Copyright (c) 2025 hua0512 (https://github.com/hua0512)
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

package douyin

import BaseTest
import com.github.michaelbull.result.get
import github.hua0512.data.config.DownloadConfig
import github.hua0512.data.stream.Streamer
import github.hua0512.plugins.douyin.danmu.DouyinDanmu
import github.hua0512.plugins.douyin.download.DouyinExtractor
import github.hua0512.plugins.douyin.download.DouyinStrevExtractor
import io.exoquery.kmp.pprint
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

private const val DANMU_FETCH_TIMEOUT_MILLIS = 5_000L

class DouyinTest : BaseTest<DouyinStrevExtractor>({


  test("regex") {
    val url = testUrl
    val matchResult = DouyinExtractor.URL_REGEX.toRegex().find(url)
    matchResult shouldNotBeNull {
      "failed to match id"
    }
    matchResult!!.groupValues.last() shouldBeEqual "838236729071"
  }

  test("isLive") {
    val info = extractor.extract()
    println(info)
    info.isOk shouldBeEqual true
    val mediaInfo = info.get()
    mediaInfo.shouldNotBeNull()
    println(pprint(mediaInfo))
  }

  test("danmu") {
    val extractor = extractor
    val info = extractor.extract()

    info.isOk shouldBeEqual true

    val danmu = DouyinDanmu(app).apply {
      enableWrite = false
      filePath = File("build/tmp/douyin_danmu.txt").apply {
        parentFile.mkdirs()
      }.path
      idStr = extractor.idStr
    }
    val init = danmu.init(Streamer(0, "test", testUrl, downloadConfig = DownloadConfig.DouyinDownloadConfig()))
    init shouldBeEqual true
    danmu.isInitialized.get() shouldBeEqual true

    if (init) {
      withTimeoutOrNull(DANMU_FETCH_TIMEOUT_MILLIS) {
        danmu.fetchDanmu()
      }
    }
  }

}) {

  override val testUrl = "https://live.douyin.com/838236729071"

  override fun createExtractor(url: String) = DouyinStrevExtractor(app.client, app.json, testUrl)
}
