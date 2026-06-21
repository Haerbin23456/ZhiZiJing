package com.example.zhizijing.utils

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object AppExecutors {
    val io: ExecutorService = Executors.newSingleThreadExecutor()
}
