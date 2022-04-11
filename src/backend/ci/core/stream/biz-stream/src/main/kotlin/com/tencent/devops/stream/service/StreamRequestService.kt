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

package com.tencent.devops.stream.service

import com.tencent.devops.common.api.pojo.Page
import com.tencent.devops.common.client.Client
import com.tencent.devops.common.pipeline.enums.ChannelCode
import com.tencent.devops.process.api.service.ServiceBuildResource
import com.tencent.devops.process.pojo.BuildHistory
import com.tencent.devops.stream.dao.GitPipelineResourceDao
import com.tencent.devops.stream.dao.GitRequestEventBuildDao
import com.tencent.devops.stream.dao.GitRequestEventDao
import com.tencent.devops.stream.dao.GitRequestEventNotBuildDao
import com.tencent.devops.stream.pojo.StreamBuildHistory
import com.tencent.devops.stream.pojo.StreamGitRequestEventReq
import com.tencent.devops.stream.pojo.StreamGitRequestHistory
import com.tencent.devops.stream.pojo.enums.TriggerReason
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

@Suppress("ALL")
@Service
class StreamRequestService @Autowired constructor(
    private val client: Client,
    private val dslContext: DSLContext,
    private val gitRequestEventDao: GitRequestEventDao,
    private val gitRequestEventBuildDao: GitRequestEventBuildDao,
    private val gitRequestEventNotBuildDao: GitRequestEventNotBuildDao,
    private val pipelineResourceDao: GitPipelineResourceDao,
    private val streamBasicSettingService: StreamBasicSettingService,
    private val streamGitProjectInfoCache: StreamGitProjectInfoCache
) {
    companion object {
        private val logger = LoggerFactory.getLogger(StreamRequestService::class.java)
        private const val ymlVersion = "v2.0"
    }

    private val channelCode = ChannelCode.GIT

    fun getRequestList(userId: String, gitProjectId: Long, page: Int?, pageSize: Int?): Page<StreamGitRequestHistory> {
        val pageNotNull = page ?: 1
        val pageSizeNotNull = pageSize ?: 10
        logger.info("get request list, gitProjectId: $gitProjectId")
        val conf = streamBasicSettingService.getStreamBasicSettingAndCheck(gitProjectId)
        val count = gitRequestEventDao.getRequestCount(dslContext, gitProjectId)
        val requestList = gitRequestEventDao.getRequestList(
            dslContext = dslContext,
            gitProjectId = gitProjectId,
            page = pageNotNull,
            pageSize = pageSizeNotNull
        )
        if (requestList.isEmpty() || count == 0L) {
            logger.info("Get request build list return empty, gitProjectId: $gitProjectId")
            return Page(
                page = pageNotNull,
                pageSize = pageSizeNotNull,
                count = 0L,
                records = emptyList()
            )
        }
        val resultList = mutableListOf<StreamGitRequestHistory>()
        requestList.forEach { event ->
            // 如果是来自fork库的分支，单独标识
            val gitProjectInfoCache = event.sourceGitProjectId?.let {
                lazy {
                    streamGitProjectInfoCache.getAndSaveGitProjectInfo(
                        gitProjectId = it,
                        useAccessToken = true,
                        getProjectInfo = streamScmService::getProjectInfoRetry
                    )
                }
            }
//            val realEvent = GitCommonUtils.checkAndGetForkBranch(event, gitProjectInfoCache)
            val realEvent = event

            val requestHistory = StreamGitRequestHistory(
                id = realEvent.id ?: return@forEach,
                gitProjectId = gitProjectId,
                commitId = realEvent.commitId,
                commitMsg = realEvent.commitMsg,
                branch = realEvent.branch,
                objectKind = realEvent.objectKind,
                commitTimeStamp = realEvent.commitTimeStamp,
                userId = realEvent.userId,
                description = realEvent.description,
                targetBranch = realEvent.targetBranch,
                mrTitle = realEvent.mrTitle,
                operationKind = realEvent.operationKind,
                mergeRequestId = realEvent.mergeRequestId,
                totalCommitCount = realEvent.totalCommitCount,
                buildRecords = mutableListOf()
            )

            // 已触发的所有记录
            val buildsList = gitRequestEventBuildDao.getRequestBuildsByEventId(dslContext, realEvent.id!!)
            logger.info("Get build list requestBuildsList: $buildsList, gitProjectId: $gitProjectId")
            val builds = buildsList.map { it.buildId }.toSet()
            val buildList = client.get(ServiceBuildResource::class)
                .getBatchBuildStatus(conf.projectCode!!, builds, channelCode).data
            if (buildList?.isEmpty() == false) {
                logger.info("Get build history list buildHistoryList: $buildList, gitProjectId: $gitProjectId")
                val records = mutableListOf<StreamBuildHistory>()
                buildsList.forEach nextBuild@{
                    try {
                        val history = getBuildHistory(buildList, it.buildId ?: return@nextBuild)
                        val pipeline = pipelineResourceDao.getPipelineById(dslContext, gitProjectId, it.pipelineId)
                            ?: return@nextBuild
                        records.add(
                            StreamBuildHistory(
                                displayName = pipeline.displayName,
                                pipelineId = pipeline.pipelineId,
                                gitRequestEvent = StreamGitRequestEventReq(realEvent),
                                buildHistory = history,
                                reason = TriggerReason.TRIGGER_SUCCESS.name,
                                reasonDetail = null
                            )
                        )
                    } catch (e: Exception) {
                        logger.error(
                            "Load gitProjectId: ${it.gitProjectId}, eventId: ${it.eventId}, pipelineId:" +
                                " ${it.pipelineId} failed with error: ",
                            e
                        )
                        return@nextBuild
                    }
                }
                requestHistory.buildRecords.addAll(records)
            } else {
                logger.info("Get branch build history list return empty, gitProjectId: $gitProjectId")
            }
            // -------

            // 未触发的所有记录
            val noBuildList = gitRequestEventNotBuildDao.getRequestNoBuildsByEventId(dslContext, event.id!!)
            logger.info("Get no build list requestBuildsList: $noBuildList, gitProjectId: $gitProjectId")
            val records = mutableListOf<StreamBuildHistory>()

            // 取所有记录的非空流水线ID，查出对应流水线
            val pipelineIds: List<String> = noBuildList.filter {
                !it.pipelineId.isNullOrBlank()
            }.map { it.pipelineId }
            val pipelineMap = pipelineResourceDao.getPipelinesInIds(dslContext, gitProjectId, pipelineIds).map {
                it.pipelineId to it
            }.toMap()

            noBuildList.forEach nextBuild@{
                val pipeline = if (it.pipelineId.isNullOrBlank()) null else pipelineMap[it.pipelineId]
                records.add(
                    StreamBuildHistory(
                        displayName = pipeline?.displayName,
                        pipelineId = pipeline?.pipelineId,
                        gitRequestEvent = StreamGitRequestEventReq(event),
                        buildHistory = null,
                        reason = it.reason,
                        reasonDetail = it.reasonDetail
                    )
                )
            }
            requestHistory.buildRecords.addAll(records)
            // -------

            resultList.add(requestHistory)
        }
        return Page(
            page = pageNotNull,
            pageSize = pageSizeNotNull,
            count = count,
            records = resultList
        )
    }

    private fun getBuildHistory(buildHistoryList: List<BuildHistory>, buildIdIt: String): BuildHistory? {
        buildHistoryList.forEach {
            if (it.id == buildIdIt) {
                return it
            }
        }
        return null
    }
}
