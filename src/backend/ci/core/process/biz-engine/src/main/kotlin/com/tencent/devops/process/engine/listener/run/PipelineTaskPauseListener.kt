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
 */

package com.tencent.devops.process.engine.listener.run

import com.fasterxml.jackson.databind.ObjectMapper
import com.tencent.devops.common.api.util.JsonUtil
import com.tencent.devops.common.event.dispatcher.pipeline.PipelineEventDispatcher
import com.tencent.devops.common.event.enums.ActionType
import com.tencent.devops.common.event.listener.pipeline.BaseListener
import com.tencent.devops.common.log.utils.BuildLogPrinter
import com.tencent.devops.common.pipeline.enums.BuildStatus
import com.tencent.devops.common.pipeline.pojo.element.Element
import com.tencent.devops.common.redis.RedisOperation
import com.tencent.devops.process.engine.common.BS_MANUAL_STOP_PAUSE_ATOM
import com.tencent.devops.process.engine.common.VMUtils
import com.tencent.devops.process.engine.control.lock.BuildIdLock
import com.tencent.devops.process.engine.dao.PipelineBuildTaskDao
import com.tencent.devops.process.engine.dao.PipelinePauseValueDao
import com.tencent.devops.process.engine.pojo.PipelineBuildTask
import com.tencent.devops.process.engine.pojo.event.PipelineTaskPauseEvent
import com.tencent.devops.process.engine.service.PipelineBuildDetailService
import com.tencent.devops.process.engine.service.PipelineRuntimeService
import com.tencent.devops.process.pojo.mq.PipelineBuildContainerEvent
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Suppress("ALL")
@Component
class PipelineTaskPauseListener @Autowired constructor(
    pipelineEventDispatcher: PipelineEventDispatcher,
    val redisOperation: RedisOperation,
    val buildDetailService: PipelineBuildDetailService,
    val dslContext: DSLContext,
    val pipelineBuildTaskDao: PipelineBuildTaskDao,
    val pipelineRuntimeService: PipelineRuntimeService,
    val objectMapper: ObjectMapper,
    private val buildLogPrinter: BuildLogPrinter,
    val pipelinePauseValueDao: PipelinePauseValueDao
) : BaseListener<PipelineTaskPauseEvent>(pipelineEventDispatcher) {

    override fun run(event: PipelineTaskPauseEvent) {
        val taskRecord = pipelineRuntimeService.getBuildTask(event.buildId, event.taskId)
        val redisLock = BuildIdLock(redisOperation = redisOperation, buildId = event.buildId)
        try {
            redisLock.lock()
            if (event.actionType == ActionType.REFRESH) {
                taskContinue(task = taskRecord!!, userId = event.userId)
            } else if (event.actionType == ActionType.END) {
                taskCancel(task = taskRecord!!, userId = event.userId)
            }
            // #3400 减少重复DETAIL事件转发， Cancel与Continue之后插件任务执行都会刷新DETAIL
        } catch (ignored: Exception) {
            logger.warn("ENGINE|${event.buildId}|pause task execute fail,$ignored")
        } finally {
            redisLock.unlock()
        }
    }

    private fun taskContinue(task: PipelineBuildTask, userId: String) {
        continuePauseTask(current = task, userId = userId)

        val newElementRecord = pipelinePauseValueDao.get(dslContext, task.buildId, task.taskId)
        if (newElementRecord != null) {
            val newElement = JsonUtil.to(newElementRecord.newValue, Element::class.java)
            newElement.executeCount = task.executeCount ?: 1
            // 修改插件运行设置
            pipelineBuildTaskDao.updateTaskParam(
                dslContext, task.buildId, task.taskId, objectMapper.writeValueAsString(newElement)
            )
            logger.info("update task param success | ${task.buildId}| ${task.taskId} ")

            // 修改详情model
            buildDetailService.updateElementWhenPauseContinue(
                buildId = task.buildId,
                stageId = task.stageId,
                containerId = task.containerId,
                taskId = task.taskId,
                element = newElement
            )
        } else {
            buildDetailService.updateElementWhenPauseContinue(
                buildId = task.buildId,
                stageId = task.stageId,
                containerId = task.containerId,
                taskId = task.taskId,
                element = null
            )
        }

        // 触发引擎container事件，继续后续流程
        pipelineEventDispatcher.dispatch(
            PipelineBuildContainerEvent(
                source = "pauseContinue",
                containerId = task.containerId,
                stageId = task.stageId,
                pipelineId = task.pipelineId,
                buildId = task.buildId,
                userId = userId,
                projectId = task.projectId,
                actionType = ActionType.REFRESH,
                containerType = ""
            )
        )
        buildLogPrinter.addYellowLine(
            buildId = task.buildId,
            message = "[${task.taskName}] processed. user: $userId, action: continue",
            tag = task.taskId,
            jobId = task.containerId,
            executeCount = task.executeCount ?: 1
        )
    }

    private fun taskCancel(task: PipelineBuildTask, userId: String) {
        logger.info("${task.buildId}|task cancel|${task.taskId}|CANCELED")
        // 修改插件状态位运行
        pipelineRuntimeService.updateTaskStatus(task = task, userId = userId, buildStatus = BuildStatus.CANCELED)

        // 刷新detail内model
        buildDetailService.taskCancel(
            buildId = task.buildId,
            stageId = task.stageId,
            containerId = task.containerId,
            taskId = task.taskId
        )

        buildDetailService.updateBuildCancelUser(task.buildId, userId)

        buildLogPrinter.addYellowLine(
            buildId = task.buildId,
            message = "[${task.taskName}] processed. user: $userId, action: terminate",
            tag = task.taskId,
            jobId = task.containerId,
            executeCount = task.executeCount ?: 1
        )
        val containerRecord = pipelineRuntimeService.getContainer(
            buildId = task.buildId,
            stageId = task.stageId,
            containerId = task.containerId
        )

        // 刷新stage状态
        pipelineEventDispatcher.dispatch(
            PipelineBuildContainerEvent(
                source = BS_MANUAL_STOP_PAUSE_ATOM,
                actionType = ActionType.END,
                pipelineId = task.pipelineId,
                projectId = task.projectId,
                userId = userId,
                buildId = task.buildId,
                containerId = task.containerId,
                stageId = task.stageId,
                containerType = containerRecord?.containerType ?: "vmBuild"
            )
        )
    }

    private fun continuePauseTask(current: PipelineBuildTask, userId: String) {
        logger.info("ENGINE|${current.buildId}]|PAUSE|${current.stageId}]|j(${current.containerId}|${current.taskId}")

        // 将启动和结束任务置为排队。用于启动构建机
        val taskRecords = pipelineRuntimeService.getAllBuildTask(current.buildId)
        val startAndEndTask = mutableListOf<PipelineBuildTask>()
        taskRecords.forEach { task ->
            if (task.containerId == current.containerId && task.stageId == current.stageId) {
                if (task.taskId == current.taskId) {
                    startAndEndTask.add(task)
                } else if (task.taskName.startsWith(VMUtils.getCleanVmLabel()) &&
                    task.taskId.startsWith(VMUtils.getStopVmLabel())) {
                    startAndEndTask.add(task)
                } else if (task.taskName.startsWith(VMUtils.getPrepareVmLabel()) &&
                    task.taskId.startsWith(VMUtils.getStartVmLabel())) {
                    startAndEndTask.add(task)
                } else if (task.taskName.startsWith(VMUtils.getWaitLabel()) &&
                    task.taskId.startsWith(VMUtils.getEndLabel())) {
                    startAndEndTask.add(task)
                }
            }
        }

        startAndEndTask.forEach {
            pipelineRuntimeService.updateTaskStatus(task = it, userId = userId, buildStatus = BuildStatus.QUEUE)
            logger.info("update|${current.buildId}|${it.taskId}|task status from ${it.status} to ${BuildStatus.QUEUE}")
        }

        // 修改容器状态位运行
        pipelineRuntimeService.updateContainerStatus(
            buildId = current.buildId,
            stageId = current.stageId,
            containerId = current.containerId,
            buildStatus = BuildStatus.QUEUE
        )
    }
}
