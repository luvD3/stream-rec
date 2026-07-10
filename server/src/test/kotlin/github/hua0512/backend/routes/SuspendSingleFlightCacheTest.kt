package github.hua0512.backend.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class SuspendSingleFlightCacheTest : FunSpec({

  test("coalesces concurrent loaders for the same key") {
    val cache = SuspendSingleFlightCache<String, String>()
    val loaderStarted = CompletableDeferred<Unit>()
    val releaseLoader = CompletableDeferred<Unit>()
    val loaderCalls = AtomicInteger()

    val results = coroutineScope {
      val requests = (1..8).map {
        async {
          cache.getOrPut("record-220") {
            if (loaderCalls.incrementAndGet() == 1) {
              loaderStarted.complete(Unit)
            }
            releaseLoader.await()
            "seek-index"
          }
        }
      }

      loaderStarted.await()
      releaseLoader.complete(Unit)
      requests.awaitAll()
    }

    loaderCalls.get() shouldBe 1
    results shouldBe List(8) { "seek-index" }
  }

  test("keeps different keys independent") {
    val cache = SuspendSingleFlightCache<String, String>()
    val loaderCalls = ConcurrentHashMap<String, AtomicInteger>()

    val results = coroutineScope {
      listOf("record-220", "record-225").map { key ->
        async {
          cache.getOrPut(key) {
            loaderCalls.computeIfAbsent(key) { AtomicInteger() }.incrementAndGet()
            "index-$key"
          }
        }
      }.awaitAll()
    }

    results shouldBe listOf("index-record-220", "index-record-225")
    loaderCalls.mapValues { it.value.get() } shouldBe mapOf("record-220" to 1, "record-225" to 1)
  }
})
