package github.hua0512.backend.routes

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

private const val BILIBILI_NAV_URL = "https://api.bilibili.com/x/web-interface/nav"
private const val BILIBILI_PLAY_INFO_URL = "https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo"
private val bilibiliRoomRegex = Regex("""^https?://live\.bilibili\.com/(\d+)(?:[/?#].*)?$""")

@Serializable
data class BilibiliCookieVerificationRequest(
  val cookie: String,
  val roomUrl: String? = null,
)

@Serializable
data class BilibiliQuality(
  val qn: Int,
  val description: String? = null,
)

@Serializable
data class BilibiliCookieVerificationResponse(
  val valid: Boolean,
  val loggedIn: Boolean,
  val userId: Long? = null,
  val userName: String? = null,
  val roomId: Long? = null,
  val live: Boolean? = null,
  val highestQuality: BilibiliQuality? = null,
  val availableQualities: List<BilibiliQuality> = emptyList(),
  val message: String? = null,
)

interface BilibiliCookieVerifier {
  suspend fun verify(request: BilibiliCookieVerificationRequest): BilibiliCookieVerificationResponse
}

internal data class BilibiliLoginState(
  val loggedIn: Boolean,
  val userId: Long?,
  val userName: String?,
  val message: String?,
)

internal data class BilibiliPlayInfo(
  val roomId: Long?,
  val live: Boolean,
  val highestQuality: BilibiliQuality?,
  val availableQualities: List<BilibiliQuality>,
)

class DefaultBilibiliCookieVerifier(
  private val http: HttpClient,
  private val json: Json,
) : BilibiliCookieVerifier, AutoCloseable {

  override suspend fun verify(request: BilibiliCookieVerificationRequest): BilibiliCookieVerificationResponse {
    val cookie = request.cookie.trim()
    require(cookie.isNotEmpty()) { "cookie is required" }

    val login = fetchLoginState(cookie)
    val playInfo = request.roomUrl?.takeIf { it.isNotBlank() }?.let { roomUrl ->
      val roomId = extractBilibiliRoomId(roomUrl) ?: throw IllegalArgumentException("invalid Bilibili room URL")
      fetchPlayInfo(cookie, roomId)
    }

    return BilibiliCookieVerificationResponse(
      valid = login.loggedIn,
      loggedIn = login.loggedIn,
      userId = login.userId,
      userName = login.userName,
      roomId = playInfo?.roomId,
      live = playInfo?.live,
      highestQuality = playInfo?.highestQuality,
      availableQualities = playInfo?.availableQualities.orEmpty(),
      message = login.message
    )
  }

  override fun close() {
    http.close()
  }

  private suspend fun fetchLoginState(cookie: String): BilibiliLoginState {
    val body = http.get(BILIBILI_NAV_URL) {
      bilibiliHeaders(cookie)
    }.bodyAsText()

    return parseBilibiliLoginState(json, body)
  }

  private suspend fun fetchPlayInfo(cookie: String, roomId: Long): BilibiliPlayInfo {
    val body = http.get(BILIBILI_PLAY_INFO_URL) {
      bilibiliHeaders(cookie, "https://live.bilibili.com/$roomId")
      parameter("room_id", roomId)
      parameter("protocol", "0,1")
      parameter("format", "0,1,2")
      parameter("codec", "0,1")
      parameter("qn", 10000)
      parameter("platform", "web")
      parameter("ptype", 8)
      parameter("dolby", 5)
      parameter("panorama", 1)
    }.bodyAsText()

    return parseBilibiliPlayInfo(json, body)
  }

  private fun HttpRequestBuilder.bilibiliHeaders(cookie: String, referrer: String = "https://www.bilibili.com/") {
    headers {
      append(HttpHeaders.Accept, "application/json, text/plain, */*")
      append(HttpHeaders.Origin, "https://www.bilibili.com")
      append(HttpHeaders.Referrer, referrer)
      append(HttpHeaders.Cookie, cookie)
    }
  }
}

fun Route.bilibiliCookieRoutes(
  json: Json,
  verifier: BilibiliCookieVerifier,
) {
  route("/platforms/bilibili/cookie") {
    post("/verify") {
      val request = call.receive<BilibiliCookieVerificationRequest>()

      val result = runCatching {
        verifier.verify(request)
      }.getOrElse { error ->
        val message = when (error) {
          is IllegalArgumentException -> error.message ?: "invalid request"
          else -> "failed to verify Bilibili cookie"
        }
        return@post call.respond(HttpStatusCode.OK, buildJsonObject {
          put("code", 400)
          put("msg", message)
        })
      }

      call.respond(HttpStatusCode.OK, buildJsonObject {
        put("code", 200)
        put("msg", "success")
        put("data", json.encodeToJsonElement(BilibiliCookieVerificationResponse.serializer(), result))
      })
    }
  }
}

internal fun extractBilibiliRoomId(roomUrl: String): Long? {
  return bilibiliRoomRegex.find(roomUrl.trim())?.groupValues?.get(1)?.toLongOrNull()
}

internal fun parseBilibiliLoginState(json: Json, body: String): BilibiliLoginState {
  val root = json.parseToJsonElement(body).jsonObject
  val code = root["code"]?.jsonPrimitive?.intOrNull
  val message = root["message"]?.jsonPrimitive?.contentOrNull ?: root["msg"]?.jsonPrimitive?.contentOrNull
  val data = root["data"]?.jsonObject

  return BilibiliLoginState(
    loggedIn = code == 0 && data?.get("isLogin")?.jsonPrimitive?.booleanOrNull == true,
    userId = data?.get("mid")?.jsonPrimitive?.longOrNull,
    userName = data?.get("uname")?.jsonPrimitive?.contentOrNull,
    message = message
  )
}

internal fun parseBilibiliPlayInfo(json: Json, body: String): BilibiliPlayInfo {
  val root = json.parseToJsonElement(body).jsonObject
  val code = root["code"]?.jsonPrimitive?.intOrNull
  require(code == 0) { root["message"]?.jsonPrimitive?.contentOrNull ?: "failed to fetch Bilibili room play info" }

  val data = root["data"]?.jsonObject ?: error("missing Bilibili room play info data")
  val roomId = data["room_id"]?.jsonPrimitive?.longOrNull
  val live = data["live_status"]?.jsonPrimitive?.intOrNull == 1
  if (!live) {
    return BilibiliPlayInfo(roomId, live = false, highestQuality = null, availableQualities = emptyList())
  }

  val playurl = data["playurl_info"]?.jsonObject
    ?.get("playurl")?.jsonObject ?: return BilibiliPlayInfo(roomId, live = true, null, emptyList())

  val descriptions = playurl["g_qn_desc"]?.jsonArray.orEmpty()
    .mapNotNull { item ->
      val obj = item.jsonObject
      val qn = obj["qn"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
      qn to obj["desc"]?.jsonPrimitive?.contentOrNull
    }
    .toMap()

  val codecObjects = playurl["stream"]?.jsonArray.orEmpty()
    .flatMap { stream -> stream.jsonObject["format"]?.jsonArray.orEmpty() }
    .flatMap { format -> format.jsonObject["codec"]?.jsonArray.orEmpty() }
    .map { it.jsonObject }

  val currentQns = codecObjects.mapNotNull { codec ->
    val hasUrl = codec["base_url"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true &&
      codec["url_info"]?.jsonArray?.isNotEmpty() == true
    codec["current_qn"]?.jsonPrimitive?.intOrNull?.takeIf { hasUrl && it > 0 }
  }

  val acceptedQns = codecObjects
    .flatMap { codec -> codec["accept_qn"]?.jsonArray.orEmpty() }
    .mapNotNull { it.jsonPrimitive.intOrNull }

  val availableQualities = (currentQns + acceptedQns)
    .distinct()
    .sortedDescending()
    .map { qn -> BilibiliQuality(qn, descriptions[qn]) }

  val highestQuality = currentQns.maxOrNull()?.let { qn -> BilibiliQuality(qn, descriptions[qn]) }

  return BilibiliPlayInfo(
    roomId = roomId,
    live = true,
    highestQuality = highestQuality,
    availableQualities = availableQualities
  )
}
