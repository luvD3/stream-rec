import github.hua0512.backend.logger
import github.hua0512.data.StreamDataId
import github.hua0512.repo.stream.StreamDataRepo
import github.hua0512.utils.md5
import io.ktor.http.ContentDisposition
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.http.content.LocalPathContent
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.options
import io.ktor.server.routing.route
import io.ktor.utils.io.CancellationException
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Path

@Serializable
data class StreamDataFiles(
  val id: Long,
  val files: List<FileInfo>
)

@Serializable
data class FileInfo(
  val name: String,
  val size: Long,
  val contentType: String,
  val exists: Boolean,
  val hash: String,
  val type: String
)

fun Route.filesRoute(streamDataRepo: StreamDataRepo) {
  route("/files") {
    install(PartialContent)

    // Get file information endpoint
    get("{id}") {
      val id = call.parameters["id"]?.toLongOrNull()
      if (id == null) {
        call.respond(HttpStatusCode.BadRequest, "Invalid stream data id")
        return@get
      }

      try {
        val streamData = streamDataRepo.getStreamDataById(StreamDataId(id))
        if (streamData == null) {
          call.respond(HttpStatusCode.NotFound, "Stream data not found")
          return@get
        }

        val files = mutableListOf<FileInfo>()

        // Add video file info if exists
        streamData.outputFilePath?.let { path ->
          val file = File(path)
          val fileName = Path.of(path).fileName.toString()
          val contentType = playbackContentType(fileName)
          val hash = streamDataFileHash(path)
          files.add(
            FileInfo(
              name = fileName,
              size = if (file.exists()) file.length() else 0,
              contentType = contentType,
              exists = file.exists(),
              hash = hash,
              type = "video"
            )
          )
        }

        // Add danmu file info if exists
        streamData.danmuFilePath?.let { path ->
          val file = File(path)
          val fileName = Path.of(path).fileName.toString()
          val hash = streamDataFileHash(path)
          files.add(
            FileInfo(
              name = fileName,
              size = if (file.exists()) file.length() else 0,
              contentType = "application/xml",
              exists = file.exists(),
              hash = hash,
              type = "danmu"
            )
          )
        }

        call.respond(StreamDataFiles(id = id, files = files))

      } catch (e: Exception) {
        logger.error("Error getting file information", e)
        call.respond(HttpStatusCode.InternalServerError, "Error getting file information: ${e.message}")
      }
    }

    // Common function to validate and get file path
    suspend fun validateAndGetFilePath(id: Long, hashWithExt: String): Pair<File?, String?> {
      val streamData = streamDataRepo.getStreamDataById(StreamDataId(id))
        ?: return null to "Stream data not found"

      // Extract hash and extension
      val lastDotIndex = hashWithExt.lastIndexOf('.')
      if (lastDotIndex == -1) {
        return null to "Invalid file format"
      }

      val extension = hashWithExt.substring(lastDotIndex)

      // Check video file
      val videoHash = streamData.outputFilePath?.let { path ->
        streamDataHashWithExtension(path)
      }
      val danmuHash = streamData.danmuFilePath?.let { path ->
        streamDataHashWithExtension(path)
      }

      val filePath = when (hashWithExt) {
        videoHash -> streamData.outputFilePath
        danmuHash -> streamData.danmuFilePath
        else -> null
      }

      if (filePath.isNullOrBlank()) {
        return null to "File not found"
      }

      val file = File(filePath)
      if (!file.exists()) {
        return null to "File not found"
      }

      return file to null
    }

    // Add file existence check endpoint
    get("{id}/{hash}/exists") {
      val id = call.parameters["id"]?.toLongOrNull()
      val hashWithExt = call.parameters["hash"]

      if (id == null || hashWithExt.isNullOrBlank()) {
        call.respond(HttpStatusCode.BadRequest, "Invalid parameters")
        return@get
      }

      try {
        val (file, error) = validateAndGetFilePath(id, hashWithExt)
        when {
          error != null -> call.respond(HttpStatusCode.NotFound, error)
          file != null -> {
            val isVideo = file.extension != "xml"

            // return file info
            call.respond(
              HttpStatusCode.OK, FileInfo(
                name = file.name,
                size = file.length(),
                contentType = playbackContentType(file.name),
                exists = true,
                hash = hashWithExt,
                type = if (isVideo) "video" else "danmu"
              )
            )
          }

          else -> call.respond(HttpStatusCode.NotFound)
        }
      } catch (e: Exception) {
        logger.error("Error checking file existence", e)
        call.respond(HttpStatusCode.InternalServerError, "Error checking file: ${e.message}")
      }
    }

    // File serving endpoint
    get("{id}/{hash}") {
      val id = call.parameters["id"]?.toLongOrNull()
      val hashWithExt = call.parameters["hash"]

      if (id == null || hashWithExt.isNullOrBlank()) {
        call.respond(HttpStatusCode.BadRequest, "Invalid parameters")
        return@get
      }

      try {
        val (file, error) = validateAndGetFilePath(id, hashWithExt)
        if (error != null || file == null) {
          call.respond(HttpStatusCode.NotFound, error ?: "File not found")
          return@get
        }

        logger.debug("Serving file: {}", file)

//        call.request.headers.forEach { key, value ->
//          logger.debug("request Header: $key: $value")
//        }

        val fileName = file.name
        call.response.header(
          HttpHeaders.ContentDisposition,
          ContentDisposition.Attachment.withParameter(
            ContentDisposition.Parameters.FileName,
            fileName
          ).toString()
        )

        call.respondFile(file)

      } catch (e: Exception) {
        // if connection is already terminated, ignore the exception
        if (call.response.status() == null) {
          return@get
        }
        // if connection is reset by peer, ignore the exception
        if (e.message?.contains("Cannot write to a channel") == true) {
          return@get
        }
        if (e is CancellationException)
          return@get // ignore CancellationException

        logger.error("Error serving file", e)
        call.respond(HttpStatusCode.InternalServerError, "Error serving file: ${e.message}")
      }
    }
  }
}

fun streamDataFileHash(path: String): String {
  return path.md5()
}

fun streamDataHashWithExtension(path: String): String {
  val fileName = Path.of(path).fileName.toString()
  val ext = fileName.substringAfterLast('.', "")
  val hash = streamDataFileHash(path)
  return if (ext.isBlank()) hash else "$hash.$ext"
}

fun playbackContentType(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
  "mp4" -> "video/mp4"
  "flv" -> "video/x-flv"
  "ts" -> "video/mp2t"
  "m3u8" -> "application/vnd.apple.mpegurl"
  "mkv" -> "video/x-matroska"
  "mov" -> "video/quicktime"
  "avi" -> "video/x-msvideo"
  "xml" -> "application/xml"
  else -> "application/octet-stream"
}
