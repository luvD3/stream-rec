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

import com.github.michaelbull.result.*
import github.hua0512.data.media.MediaInfo
import github.hua0512.data.media.VideoFormat
import github.hua0512.data.stream.StreamInfo
import github.hua0512.plugins.base.Extractor
import github.hua0512.plugins.base.ExtractorError
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*

/**
 * Bilibili live stream extractor.
 */
class BilibiliExtractor(override val http: HttpClient, override val json: Json, override val url: String) :
  Extractor(http, json) {

  companion object {
    const val BASE_URL = "https://live.bilibili.com"
    const val URL_REGEX = "^https://live\\.bilibili\\.com/(\\d+)(?:[/?#].*)?$"
    private const val ROOM_INIT_API = "https://api.live.bilibili.com/room/v1/Room/room_init"
    private const val ROOM_INFO_API = "https://api.live.bilibili.com/xlive/web-room/v1/index/getInfoByRoom"
    private const val PLAY_INFO_API = "https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo"
    private const val HIGHEST_REQUEST_QN = 30000
  }

  override val regexPattern: Regex = URL_REGEX.toRegex()

  private var inputRoomId = 0L
  internal var roomId = 0L
    private set
  private var uid = 0L
  private var roomInitData: JsonObject? = null

  init {
    platformHeaders[HttpHeaders.Origin] = BASE_URL
    platformHeaders[HttpHeaders.Referrer] = BASE_URL
  }

  override fun match(): Result<String, ExtractorError> =
    regexPattern.find(url)?.groupValues?.get(1)?.toLongOrNull()
      ?.also {
        inputRoomId = it
        roomId = it
      }
      ?.let { Ok(it.toString()) }
      ?: Err(ExtractorError.InvalidExtractionUrl)

  override suspend fun isLive(): Result<Boolean, ExtractorError> {
    val roomInitResult = getRoomInit()
    if (roomInitResult.isErr) return roomInitResult.asErr()

    val data = roomInitResult.get()!!
    roomInitData = data
    roomId = data["room_id"]?.jsonPrimitive?.longOrNull ?: inputRoomId
    uid = data["uid"]?.jsonPrimitive?.longOrNull ?: 0L

    if (data["is_hidden"]?.jsonPrimitive?.booleanOrNull == true || data["is_locked"]?.jsonPrimitive?.booleanOrNull == true) {
      return Ok(false)
    }

    if (data["encrypted"]?.jsonPrimitive?.booleanOrNull == true && data["pwd_verified"]?.jsonPrimitive?.booleanOrNull != true) {
      return Err(ExtractorError.InvalidResponse("password-protected bilibili room is not supported"))
    }

    return Ok(data["live_status"]?.jsonPrimitive?.intOrNull == 1)
  }

  override suspend fun extract(): Result<MediaInfo, ExtractorError> {
    val liveResult = isLive()
    if (liveResult.isErr) return liveResult.asErr()

    val live = liveResult.get()!!
    val roomInfo = getRoomInfoOrNull()
    val room = roomInfo?.get("room_info")?.jsonObject
    val anchor = roomInfo?.get("anchor_info")?.jsonObject?.get("base_info")?.jsonObject

    val mediaInfo = MediaInfo(
      site = "$BASE_URL/$inputRoomId",
      title = room?.get("title")?.jsonPrimitive?.contentOrNull
        ?: roomId.takeIf { it != 0L }?.let { "Bilibili Live $it" }.orEmpty(),
      artist = anchor?.get("uname")?.jsonPrimitive?.contentOrNull
        ?: uid.takeIf { it != 0L }?.toString().orEmpty(),
      coverUrl = room?.get("cover")?.jsonPrimitive?.contentOrNull
        ?: room?.get("keyframe")?.jsonPrimitive?.contentOrNull.orEmpty(),
      artistImageUrl = anchor?.get("face")?.jsonPrimitive?.contentOrNull.orEmpty(),
      live = live,
      extras = roomInitData?.let {
        mapOf(
          "room_id" to roomId.toString(),
          "short_id" to (it["short_id"]?.jsonPrimitive?.longOrNull ?: 0L).toString(),
          "uid" to uid.toString(),
        )
      } ?: emptyMap()
    )

    if (!live) return Ok(mediaInfo)

    val playInfoResult = getRoomPlayInfo()
    if (playInfoResult.isErr) return playInfoResult.asErr()

    val streamsResult = parseStreams(playInfoResult.get()!!)
    if (streamsResult.isErr) return streamsResult.asErr()

    return Ok(mediaInfo.copy(streams = streamsResult.get()!!))
  }

  private suspend fun getRoomInit(): Result<JsonObject, ExtractorError> {
    val result = getResponse(ROOM_INIT_API) {
      parameter("id", inputRoomId)
    }
    if (result.isErr) return result.asErr()

    return result.get()!!.readApiDataObject("room_init")
  }

  private suspend fun getRoomInfoOrNull(): JsonObject? {
    val result = getResponse(ROOM_INFO_API) {
      parameter("room_id", roomId)
    }
    if (result.isErr) return null

    val dataResult = result.get()!!.readApiDataObject("getInfoByRoom")
    return if (dataResult.isOk) dataResult.get() else null
  }

  private suspend fun getRoomPlayInfo(): Result<JsonObject, ExtractorError> {
    val result = getResponse(PLAY_INFO_API) {
      parameter("room_id", roomId)
      parameter("protocol", "0,1")
      parameter("format", "0,1,2")
      parameter("codec", "0,1")
      parameter("qn", HIGHEST_REQUEST_QN)
      parameter("platform", "web")
      parameter("ptype", "8")
    }
    if (result.isErr) return result.asErr()

    return result.get()!!.readApiDataObject("getRoomPlayInfo")
  }

  private suspend fun HttpResponse.readApiDataObject(apiName: String): Result<JsonObject, ExtractorError> =
    runCatching {
      body<JsonElement>().jsonObject
    }.mapError {
      ExtractorError.InvalidResponse("$apiName response is not valid json")
    }.andThen { root ->
      val code = root["code"]?.jsonPrimitive?.intOrNull ?: -1
      if (code != 0) {
        val message = root["message"]?.jsonPrimitive?.contentOrNull ?: root["msg"]?.jsonPrimitive?.contentOrNull
        return@andThen Err(ExtractorError.InvalidResponse("$apiName failed with code $code: ${message.orEmpty()}"))
      }
      root["data"]?.jsonObject?.let { Ok(it) }
        ?: Err(ExtractorError.InvalidResponse("$apiName data is not an object"))
    }

  private fun parseStreams(data: JsonObject): Result<List<StreamInfo>, ExtractorError> = runCatching {
    val playUrl = data["playurl_info"]?.jsonObject?.get("playurl")?.jsonObject
      ?: return Err(ExtractorError.InvalidResponse("playurl not found"))
    val qnDescriptions = playUrl["g_qn_desc"]?.jsonArray?.mapNotNull { item ->
      val obj = item.jsonObject
      val qn = obj["qn"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
      val desc = obj["desc"]?.jsonPrimitive?.contentOrNull ?: qn.toString()
      qn to desc
    }?.toMap().orEmpty()

    val streams = playUrl["stream"]?.jsonArray.orEmpty().flatMap { streamElement ->
      val stream = streamElement.jsonObject
      val protocolName = stream["protocol_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
      stream["format"]?.jsonArray.orEmpty().flatMap { formatElement ->
        val format = formatElement.jsonObject
        val formatName = format["format_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val sourceFormat = formatName.toVideoFormat()
        format["codec"]?.jsonArray.orEmpty().flatMap { codecElement ->
          val codec = codecElement.jsonObject
          val codecName = codec["codec_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
          val currentQn = codec["current_qn"]?.jsonPrimitive?.intOrNull ?: 0
          val acceptQn = codec["accept_qn"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.intOrNull }
          val baseUrl = codec["base_url"]?.jsonPrimitive?.contentOrNull.orEmpty()
          codec["url_info"]?.jsonArray.orEmpty().mapNotNull { urlInfoElement ->
            val urlInfo = urlInfoElement.jsonObject
            val host = urlInfo["host"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val extra = urlInfo["extra"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (host.isEmpty() || baseUrl.isEmpty()) return@mapNotNull null
            val params = parseQueryString(extra)
            val cdn = params["cdn"] ?: Url(host).host
            StreamInfo(
              url = host + baseUrl + extra,
              format = sourceFormat,
              quality = qnDescriptions[currentQn] ?: currentQn.toString(),
              bitrate = params["origin_bitrate"]?.toLongOrNull()
                ?: params["qn"]?.toLongOrNull()
                ?: currentQn.toLong(),
              extras = mapOf(
                "current_qn" to currentQn.toString(),
                "qn" to currentQn.toString(),
                "accept_qn" to acceptQn.joinToString(","),
                "format_name" to formatName,
                "codec_name" to codecName,
                "codec" to codecName,
                "cdn" to cdn,
                "source_format" to sourceFormat.name,
                "protocol_name" to protocolName,
              ),
              isHeadersNeeded = true,
            )
          }
        }
      }
    }

    if (streams.isEmpty()) {
      Err(ExtractorError.NoStreamsFound)
    } else {
      Ok(streams)
    }
  }.getOrElse {
    Err(ExtractorError.InvalidResponse("failed to parse getRoomPlayInfo streams"))
  }

  private fun String.toVideoFormat(): VideoFormat = when (this) {
    "flv" -> VideoFormat.flv
    else -> VideoFormat.hls
  }
}
