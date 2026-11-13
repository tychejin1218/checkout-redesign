package com.example.checkout.support

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * [count] 개의 스레드가 동시에 [task] 를 시작하게 하고 각 결과(또는 예외)를 돌려준다.
 *
 * 모든 스레드가 준비된 뒤 한꺼번에 출발시킨다. 스레드마다 커넥션이 따로 잡힌다.
 */
fun <T> runConcurrently(
    count: Int,
    task: (index: Int) -> T,
): List<Result<T>> {
    val executor = Executors.newFixedThreadPool(count)
    val ready = CountDownLatch(count)
    val start = CountDownLatch(1)
    try {
        val futures =
            (0 until count).map { index ->
                executor.submit<Result<T>> {
                    ready.countDown()
                    start.await()
                    runCatching { task(index) }
                }
            }
        ready.await()
        start.countDown()
        return futures.map { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
    } finally {
        executor.shutdownNow()
    }
}

private const val TIMEOUT_SECONDS = 60L
