/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2019 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 */

package com.tencent.devops.metrics.service.builds

import com.tencent.devops.common.api.util.JsonUtil
import com.tencent.devops.common.api.util.OkhttpUtils
import com.tencent.devops.common.api.util.timestampmilli
import com.tencent.devops.common.pipeline.event.CallBackEvent
import com.tencent.devops.common.service.utils.RetryUtils
import com.tencent.devops.metrics.config.MetricsUserConfig
import com.tencent.devops.metrics.pojo.po.MetricsEventPO
import io.micrometer.core.instrument.Tag
import java.time.LocalDateTime
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(name = ["metrics.user.enable"], havingValue = "true", matchIfMissing = false)
class MetricsEventService @Autowired constructor(
    private val metricsUserConfig: MetricsUserConfig
) {
    private val queue: BlockingQueue<MetricsEventPO.Data> = ArrayBlockingQueue(10000) // 创建一个容量为10000的阻塞队列

    private val executor = Executors.newCachedThreadPool()

    companion object {
        val logger: Logger = LoggerFactory.getLogger(MetricsEventService::class.java)
        const val BUFFER_SIZE = 10
        const val TIMEOUT = 10L
    }

    private inner class EventSendProcess : Runnable {
        val buf = mutableListOf<MetricsEventPO.Data>()
        override fun run() {
            while (true) {
                /*当buf超过BUFFER_SIZE，或达到TIMEOUT限制时才上报数据*/
                val message = queue.poll(TIMEOUT, TimeUnit.SECONDS)
                if (message != null) {
                    buf.add(message)
                    if (buf.size < BUFFER_SIZE) continue
                }
                if (buf.isEmpty()) continue
                RetryUtils.execute(action = object : RetryUtils.Action<Unit> {
                    override fun execute() {
                        send()
                        buf.clear()
                    }

                    override fun fail(e: Throwable) {
                        logger.error("event send failed:${e.message}", e)
                        buf.clear()
                    }
                }, retryTime = 3, retryPeriodMills = 5000)

            }
        }

        private fun send() {
            val url = "${metricsUserConfig.eventIp}:10205/v2/push/"
            val body = JsonUtil.toJson(
                MetricsEventPO(
                    dataId = metricsUserConfig.eventDataId,
                    accessToken = metricsUserConfig.eventToken,
                    data = buf
                ),
                false
            )
            val request = Request.Builder()
                .url(url)
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
                .build()

            OkhttpUtils.doHttp(request)
        }
    }

    fun init() {
        repeat(metricsUserConfig.eventConsumerCount) {
            executor.submit(EventSendProcess())
        }
    }

    fun registerBuildStatusEvent(
        projectId: String,
        pipelineId: String,
        buildId: String,
        status: String,
        type: CallBackEvent,
        time: LocalDateTime,
        labels: List<Tag>
    ) {
        if (metricsUserConfig.eventIp.isBlank()) return
        val dimension = mutableMapOf(
            "projectId" to projectId,
            "pipelineId" to pipelineId,
            "buildId" to buildId,
            "status" to status,
            "type" to type.name
        )
        if (labels.isNotEmpty()) {
            labels.forEach { label ->
                dimension[label.key] = label.value
            }
        }
        queue.put(
            MetricsEventPO.Data(
                eventName = MetricsUserConfig.gaugeBuildStatusKey,
                event = MetricsEventPO.Data.Event(
                    "build status for $status"
                ),
                dimension = dimension,
                timestamp = time.timestampmilli()
            )
        )
    }

    fun registerBuildStepStatusEvent(
        projectId: String,
        pipelineId: String,
        buildId: String,
        jobId: String,
        stepId: String,
        status: String,
        type: CallBackEvent,
        time: LocalDateTime,
        labels: List<Tag>
    ) {
        if (metricsUserConfig.eventIp.isBlank()) return
        val dimension = mutableMapOf(
            "projectId" to projectId,
            "pipelineId" to pipelineId,
            "buildId" to buildId,
            "status" to status,
            "jobId" to jobId,
            "stepId" to stepId,
            "type" to type.name
        )
        if (labels.isNotEmpty()) {
            labels.forEach { label ->
                dimension[label.key] = label.value
            }
        }
        queue.put(
            MetricsEventPO.Data(
                eventName = MetricsUserConfig.gaugeBuildStepStatusKey,
                event = MetricsEventPO.Data.Event(
                    "step status for $status"
                ),
                dimension = dimension,
                timestamp = time.timestampmilli()
            )
        )
    }
}
