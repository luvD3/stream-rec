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

package github.hua0512.plugins.bilibili.danmu

import github.hua0512.app.App
import github.hua0512.app.COMMON_USER_AGENT
import github.hua0512.data.media.DanmuDataWrapper
import github.hua0512.data.stream.Streamer
import github.hua0512.plugins.bilibili.download.BilibiliExtractor
import github.hua0512.plugins.danmu.base.Danmu
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.websocket.WebSocketSession
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.time.Instant

class BilibiliDanmu(app: App) : Danmu(app, enablePing = false) {
  override var websocketUrl: String = DEFAULT_WEBSOCKET_URL
  override val heartBeatDelay: Long = 30_000L
  override val heartBeatPack: ByteArray = BilibiliDanmuProtocol.encode(
    operation = BilibiliDanmuProtocol.OP_HEARTBEAT,
    body = "[object Object]".encodeToByteArray(),
  )

  internal var roomId: Long = 0L
    private set
  private var token: String = ""

  init {
    headersMap[HttpHeaders.Origin] = BilibiliExtractor.BASE_URL
    headersMap[HttpHeaders.Referrer] = BilibiliExtractor.BASE_URL
    headersMap[HttpHeaders.UserAgent] = COMMON_USER_AGENT
  }

  override suspend fun initDanmu(streamer: Streamer, startTime: Instant): Boolean {
    val parsedRoomId = BilibiliExtractor.URL_REGEX.toRegex()
      .matchEntire(streamer.url)
      ?.groupValues
      ?.getOrNull(1)
      ?.toLongOrNull()

    if (parsedRoomId == null || parsedRoomId <= 0L) {
      logger.error("Failed to parse Bilibili room id from url: {}", streamer.url)
      return false
    }

    return initFromDanmuInfo(parsedRoomId, streamer)
  }

  override fun oneHello(): ByteArray {
    val authBody = buildJsonObject {
      put("uid", JsonPrimitive(0))
      put("roomid", JsonPrimitive(roomId))
      put("protover", JsonPrimitive(BilibiliDanmuProtocol.VERSION_BROTLI))
      put("platform", JsonPrimitive("web"))
      put("type", JsonPrimitive(2))
      put("key", JsonPrimitive(token))
    }.toString()

    return BilibiliDanmuProtocol.encode(
      operation = BilibiliDanmuProtocol.OP_AUTH,
      body = authBody.encodeToByteArray(),
    )
  }

  override fun onDanmuRetry(retryCount: Int) {
    logger.warn("Bilibili danmu retry count: {}", retryCount)
  }

  override suspend fun decodeDanmu(session: WebSocketSession, data: ByteArray): List<DanmuDataWrapper?> =
    BilibiliDanmuProtocol.decodeMessages(data, app.json)

  override fun clean() {
    super.clean()
    websocketUrl = DEFAULT_WEBSOCKET_URL
    roomId = 0L
    token = ""
  }

  private suspend fun initFromDanmuInfo(parsedRoomId: Long, streamer: Streamer): Boolean =
    runCatching {
      val signedParams = signedDanmuInfoParams(parsedRoomId) ?: return@runCatching false
      val cookie = danmuInfoCookie(streamer) ?: return@runCatching false
      headersMap[HttpHeaders.Cookie] = cookie

      val response = app.client.get(DANMU_INFO_API) {
        signedParams.forEach { (key, value) -> parameter(key, value) }
        fillBilibiliBrowserHeaders(parsedRoomId)
        header(HttpHeaders.Cookie, cookie)
      }

      val root = app.json.parseToJsonElement(response.bodyAsText()).jsonObject
      val code = root["code"]?.jsonPrimitive?.intOrNull ?: -1
      if (code != 0) {
        val message = root["message"]?.jsonPrimitive?.contentOrNull ?: root["msg"]?.jsonPrimitive?.contentOrNull
        logger.error("Failed to get Bilibili danmu info, code: {}, message: {}", code, message.orEmpty())
        return@runCatching false
      }

      val data = root["data"]?.jsonObject ?: return@runCatching false
      roomId = data["room_id"]?.jsonPrimitive?.longOrNull ?: parsedRoomId
      token = data["token"]?.jsonPrimitive?.contentOrNull.orEmpty()
      if (token.isEmpty()) {
        logger.error("Failed to get Bilibili danmu token for room: {}", parsedRoomId)
        return@runCatching false
      }

      websocketUrl = data.selectWebsocketUrl() ?: DEFAULT_WEBSOCKET_URL
      true
    }.getOrElse {
      logger.error("Failed to initialize Bilibili danmu", it)
      false
    }

  private suspend fun signedDanmuInfoParams(parsedRoomId: Long): Map<String, String>? {
    val keys = fetchWbiKeys() ?: return null
    return BilibiliWbiSigner.sign(
      params = mapOf(
        "id" to parsedRoomId.toString(),
        "type" to "0",
        "web_location" to "444.8",
      ),
      imgKey = keys.imgKey,
      subKey = keys.subKey,
    )
  }

  private suspend fun fetchWbiKeys(): WbiKeys? =
    runCatching {
      val response = app.client.get(WBI_NAV_API) {
        fillBilibiliBrowserHeaders()
      }
      val root = app.json.parseToJsonElement(response.bodyAsText()).jsonObject
      val wbi = root["data"]?.jsonObject?.get("wbi_img")?.jsonObject ?: return@runCatching null
      val imgKey = wbi["img_url"]?.jsonPrimitive?.contentOrNull?.extractWbiKey().orEmpty()
      val subKey = wbi["sub_url"]?.jsonPrimitive?.contentOrNull?.extractWbiKey().orEmpty()
      if (imgKey.isBlank() || subKey.isBlank()) {
        logger.error("Failed to get Bilibili WBI keys")
        return@runCatching null
      }
      WbiKeys(imgKey, subKey)
    }.getOrElse {
      logger.error("Failed to fetch Bilibili WBI keys", it)
      null
    }

  private suspend fun danmuInfoCookie(streamer: Streamer): String? {
    val configuredCookies = streamer.downloadConfig?.cookies?.trim()?.trimEnd(';')
    if (configuredCookies?.contains("buvid3=", ignoreCase = true) == true) return configuredCookies

    val buvid3 = fetchBuvid3() ?: return configuredCookies.takeUnless { it.isNullOrBlank() }.also {
      if (it.isNullOrBlank()) logger.error("Failed to get Bilibili buvid3 for anonymous danmu auth")
    }

    return if (configuredCookies.isNullOrBlank()) {
      "buvid3=$buvid3"
    } else {
      "$configuredCookies; buvid3=$buvid3"
    }
  }

  private suspend fun fetchBuvid3(): String? =
    runCatching {
      val response = app.client.get(BUVID_SPI_API) {
        fillBilibiliBrowserHeaders()
      }
      val root = app.json.parseToJsonElement(response.bodyAsText()).jsonObject
      root["data"]?.jsonObject?.get("b_3")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }.getOrElse {
      logger.error("Failed to fetch Bilibili buvid3", it)
      null
    }

  private fun io.ktor.client.request.HttpRequestBuilder.fillBilibiliBrowserHeaders(roomId: Long? = null) {
    header(HttpHeaders.UserAgent, COMMON_USER_AGENT)
    header(HttpHeaders.Accept, "application/json, text/plain, */*")
    header(HttpHeaders.AcceptLanguage, "zh-CN,zh;q=0.9,en;q=0.8")
    header(HttpHeaders.Origin, BilibiliExtractor.BASE_URL)
    header(HttpHeaders.Referrer, roomId?.let { "${BilibiliExtractor.BASE_URL}/$it" } ?: BilibiliExtractor.BASE_URL)
  }

  private fun JsonObject.selectWebsocketUrl(): String? =
    this["host_list"]?.jsonArray.orEmpty()
      .mapNotNull { it.jsonObject.toWebsocketUrlOrNull() }
      .firstOrNull()

  private fun JsonObject.toWebsocketUrlOrNull(): String? {
    val host = this["host"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
    val wssPort = this["wss_port"]?.jsonPrimitive?.intOrNull ?: 0
    if (wssPort > 0) return "wss://$host:$wssPort/sub"

    val wsPort = this["ws_port"]?.jsonPrimitive?.intOrNull
      ?: this["port"]?.jsonPrimitive?.intOrNull
      ?: 0
    return if (wsPort > 0) "ws://$host:$wsPort/sub" else null
  }

  private companion object {
    private const val DANMU_INFO_API = "https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo"
    private const val WBI_NAV_API = "https://api.bilibili.com/x/web-interface/nav"
    private const val BUVID_SPI_API = "https://api.bilibili.com/x/frontend/finger/spi"
    private const val DEFAULT_WEBSOCKET_URL = "wss://broadcastlv.chat.bilibili.com:443/sub"
  }

  private data class WbiKeys(val imgKey: String, val subKey: String)
}

private fun String.extractWbiKey(): String =
  substringAfterLast('/').substringBefore('.')
