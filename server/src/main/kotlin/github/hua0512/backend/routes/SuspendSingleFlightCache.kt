package github.hua0512.backend.routes

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

internal class SuspendSingleFlightCache<K : Any, V : Any> {
  private val values = ConcurrentHashMap<K, V>()
  private val locks = ConcurrentHashMap<K, Mutex>()

  suspend fun getOrPut(key: K, loader: suspend () -> V): V {
    values[key]?.let { return it }

    val lock = locks.computeIfAbsent(key) { Mutex() }
    return lock.withLock {
      values[key] ?: loader().also { values[key] = it }
    }
  }
}
