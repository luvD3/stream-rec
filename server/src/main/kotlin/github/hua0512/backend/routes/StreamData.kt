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

package github.hua0512.backend.routes

import github.hua0512.backend.logger
import github.hua0512.data.StreamDataId
import github.hua0512.data.StreamerId
import github.hua0512.flv.FlvMetaInfoProvider
import github.hua0512.flv.operators.analyze
import github.hua0512.flv.utils.asFlvFlow
import github.hua0512.plugins.StreamerContext
import github.hua0512.repo.stream.StreamDataRepo
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.io.asSource
import kotlinx.io.buffered
import playbackContentType
import streamDataHashWithExtension
import java.io.File
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class PlaybackFile(
  val name: String,
  val hash: String,
  val url: String,
  val size: Long,
  val contentType: String,
  val format: String,
  val exists: Boolean,
)

@Serializable
data class PlaybackManifest(
  val id: Long,
  val title: String,
  val streamerName: String,
  val dateStart: Long? = null,
  val dateEnd: Long? = null,
  val video: PlaybackFile,
  val danmu: PlaybackFile? = null,
)

@Serializable
data class PlaybackFlvSeekIndex(
  val format: String = "flv",
  val duration: Double,
  val fileSize: Long,
  val times: List<Long>,
  val filepositions: List<Long>,
  val keyframeCount: Int,
)

private data class PlaybackFlvSeekIndexCacheKey(
  val path: String,
  val size: Long,
  val modified: Long,
)

private object PlaybackFlvSeekIndexCache {
  private val cache = ConcurrentHashMap<PlaybackFlvSeekIndexCacheKey, PlaybackFlvSeekIndex>()

  suspend fun get(file: File, title: String, streamerName: String): PlaybackFlvSeekIndex = withContext(Dispatchers.IO) {
    val key = PlaybackFlvSeekIndexCacheKey(
      path = file.canonicalPath,
      size = file.length(),
      modified = file.lastModified(),
    )
    cache[key] ?: analyze(file, title, streamerName).also { cache[key] = it }
  }

  private suspend fun analyze(file: File, title: String, streamerName: String): PlaybackFlvSeekIndex {
    val provider = FlvMetaInfoProvider()
    val context = StreamerContext(
      name = streamerName.ifBlank { file.nameWithoutExtension },
      title = title,
      platform = "local",
    )

    file.inputStream().asSource().buffered().use { source ->
      source.asFlvFlow()
        .analyze(provider, context)
        .collect()
    }

    val metaInfo = provider[0]
    val fileLength = file.length()
    val keyframes = metaInfo?.keyframes.orEmpty()
      .filter { it.filePosition >= 0 && it.filePosition < fileLength }
      .distinctBy { it.timestamp }
      .sortedBy { it.timestamp }

    return PlaybackFlvSeekIndex(
      duration = metaInfo?.duration ?: 0.0,
      fileSize = metaInfo?.fileSize ?: file.length(),
      times = keyframes.map { it.timestamp },
      filepositions = keyframes.map { it.filePosition },
      keyframeCount = keyframes.size,
    )
  }
}

private fun playbackFileFor(id: Long, path: String): PlaybackFile {
  val file = File(path)
  val fileName = Path.of(path).fileName.toString()
  val hash = streamDataHashWithExtension(path)
  val format = fileName.substringAfterLast('.', "").lowercase()

  return PlaybackFile(
    name = fileName,
    hash = hash,
    url = "/files/$id/$hash",
    size = if (file.exists()) file.length() else 0,
    contentType = playbackContentType(fileName),
    format = format,
    exists = file.exists(),
  )
}

/**
 * @author hua0512
 * @date : 2024/3/5 11:58
 */

fun Route.streamsRoute(json: Json, streamsRepo: StreamDataRepo) {
  route("/streams") {
    get {
      val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 0
      val pageSize = call.request.queryParameters["per_page"]?.toIntOrNull() ?: 10
      val filter = call.request.queryParameters["filter"]
      val streamers = call.request.queryParameters.getAll("streamer")?.run {
        mapNotNull {
          it.toLongOrNull()?.let { StreamerId(it) } ?: run {
            logger.warn("Invalid stream id: $it")
            null
          }
        }
      }
      val dateStart = call.request.queryParameters["date_start"]?.toLongOrNull()
      val dateEnd = call.request.queryParameters["date_end"]?.toLongOrNull()
      val sortColumn = call.request.queryParameters["sort"]
      val order = call.request.queryParameters["order"]?.uppercase()

      try {
        val count = streamsRepo.count(streamers, filter, dateStart, dateEnd).run {
          (this + pageSize - 1) / pageSize
        }
        val results = streamsRepo.getStreamDataPaged(page, pageSize, streamers, filter, dateStart, dateEnd, sortColumn, order)

        val body = buildJsonObject {
          put("pages", count)
          put("data", json.encodeToJsonElement(results))
        }
        call.respond(HttpStatusCode.OK, body)
      } catch (e: Exception) {
        logger.error("Failed to get stream data", e)
        call.respond(HttpStatusCode.InternalServerError, "Failed to get stream data")
      }
    }

    get("{id}/playback") {
      val id = call.parameters["id"]?.toLongOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid id")
      val streamData = streamsRepo.getStreamDataById(StreamDataId(id))
      if (streamData == null) {
        call.respond(HttpStatusCode.NotFound, "Stream data not found")
        return@get
      }

      val video = playbackFileFor(id, streamData.outputFilePath)
      if (!video.exists) {
        call.respond(HttpStatusCode.NotFound, "Video file not found")
        return@get
      }

      call.respond(
        PlaybackManifest(
          id = id,
          title = streamData.title,
          streamerName = streamData.streamerName,
          dateStart = streamData.dateStart,
          dateEnd = streamData.dateEnd,
          video = video,
          danmu = streamData.danmuFilePath?.let { playbackFileFor(id, it) }?.takeIf { it.exists },
        )
      )
    }

    get("{id}/playback/flv-index") {
      val id = call.parameters["id"]?.toLongOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid id")
      val streamData = streamsRepo.getStreamDataById(StreamDataId(id))
      if (streamData == null) {
        call.respond(HttpStatusCode.NotFound, "Stream data not found")
        return@get
      }

      val video = File(streamData.outputFilePath)
      if (!video.exists()) {
        call.respond(HttpStatusCode.NotFound, "Video file not found")
        return@get
      }

      if (video.extension.lowercase() != "flv") {
        call.respond(HttpStatusCode.BadRequest, "Playback seek index is only supported for FLV files")
        return@get
      }

      try {
        call.respond(
          PlaybackFlvSeekIndexCache.get(
            file = video,
            title = streamData.title,
            streamerName = streamData.streamerName,
          )
        )
      } catch (e: Exception) {
        logger.error("Failed to build FLV playback seek index for stream data {}", id, e)
        call.respond(HttpStatusCode.InternalServerError, "Failed to build FLV playback seek index")
      }
    }

    get("{id}") {
      val id = call.parameters["id"]?.toLongOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid id")
      val streamData = streamsRepo.getStreamDataById(StreamDataId(id))
      if (streamData == null) {
        call.respond(HttpStatusCode.NotFound, "Stream data not found")
      } else {
        call.respond(streamData)
      }
    }

    delete("{id}") {
      val id = call.parameters["id"]?.toLongOrNull() ?: return@delete call.respond(HttpStatusCode.BadRequest, "Invalid id")
      val deleteLocal = call.request.queryParameters["delete_local"]?.toBoolean() ?: false
      try {
        streamsRepo.delete(StreamDataId(id), deleteLocal)
      } catch (e: Exception) {
        e.printStackTrace()
        call.respond(HttpStatusCode.InternalServerError, "Failed to delete stream data")
        return@delete
      }
      call.respond(HttpStatusCode.OK, "Stream data deleted")
    }

    delete("/batch") {
      val ids = call.request.queryParameters["ids"]?.split(",")?.mapNotNull { it.toLongOrNull() }
      val deleteLocal = call.request.queryParameters["delete_local"]?.toBoolean() ?: false
      if (ids.isNullOrEmpty()) {
        call.respond(HttpStatusCode.BadRequest, "Invalid ids")
        return@delete
      }
      try {
        streamsRepo.delete(ids.map { StreamDataId(it) }, deleteLocal)
      } catch (e: Exception) {
        e.printStackTrace()
        call.respond(HttpStatusCode.InternalServerError, "Failed to delete stream data")
        return@delete
      }

      call.respond(HttpStatusCode.OK, "Stream data deleted")
    }
  }
}
