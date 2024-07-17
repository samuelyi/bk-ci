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

package com.tencent.devops.process.service

import com.tencent.devops.common.auth.api.AuthPermission
import com.tencent.devops.common.pipeline.container.Container
import com.tencent.devops.common.pipeline.container.Stage
import com.tencent.devops.common.pipeline.enums.ChannelCode
import com.tencent.devops.common.pipeline.pojo.element.Element
import com.tencent.devops.common.pipeline.pojo.element.SubPipelineCallElement
import com.tencent.devops.common.pipeline.pojo.element.atom.BeforeDeleteParam
import com.tencent.devops.common.pipeline.pojo.element.atom.ElementCheckResult
import com.tencent.devops.common.pipeline.pojo.element.market.MarketBuildAtomElement
import com.tencent.devops.common.pipeline.pojo.element.market.MarketBuildLessAtomElement
import com.tencent.devops.common.web.utils.I18nUtil
import com.tencent.devops.process.constant.ProcessMessageCode
import com.tencent.devops.process.engine.atom.plugin.IElementBizPluginService
import com.tencent.devops.process.permission.PipelinePermissionService
import com.tencent.devops.process.pojo.pipeline.SubPipelineRef
import com.tencent.devops.process.service.pipeline.SubPipelineRefService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

/**
 * 子流水线插件扩展点处理类
 */
@Service
class SubPipelineElementBizPluginService @Autowired constructor(
    private val pipelinePermissionService: PipelinePermissionService,
    private val subPipelineRefService: SubPipelineRefService
) : IElementBizPluginService {

    companion object {
        private const val SUB_PIPELINE_EXEC_ATOM_CODE = "SubPipelineExec"
        private val logger = LoggerFactory.getLogger(SubPipelineElementBizPluginService::class.java)
    }

    override fun supportElement(element: Element): Boolean {
        return element is SubPipelineCallElement ||
                (element is MarketBuildAtomElement && element.getAtomCode() == SUB_PIPELINE_EXEC_ATOM_CODE) ||
                (element is MarketBuildLessAtomElement && element.getAtomCode() == SUB_PIPELINE_EXEC_ATOM_CODE)
    }

    override fun afterCreate(
        element: Element,
        projectId: String,
        pipelineId: String,
        pipelineName: String,
        userId: String,
        channelCode: ChannelCode,
        create: Boolean,
        container: Container
    ) = Unit

    override fun beforeDelete(element: Element, param: BeforeDeleteParam) {
        element.id?.let {
            subPipelineRefService.deleteElement(
                projectId = param.projectId,
                pipelineId = param.pipelineId,
                taskId = it
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun check(
        projectId: String?,
        userId: String,
        stage: Stage,
        container: Container,
        element: Element,
        contextMap: Map<String, String>,
        appearedCnt: Int,
        isTemplate: Boolean,
        pipelineId: String
    ): ElementCheckResult {
        // 模板保存时不需要校验子流水线权限
        if (isTemplate || projectId.isNullOrBlank()) return ElementCheckResult(true)
        val (subProjectId, subPipelineId, subPipelineName) = subPipelineRefService.getSubPipelineInfo(
            element = element,
            projectId = projectId,
            contextMap = contextMap
        ) ?: return ElementCheckResult(true)
        val subPipelineRef = SubPipelineRef(
            projectId = projectId,
            pipelineId = pipelineId,
            pipelineName = "",
            subProjectId = subProjectId,
            subPipelineId = subPipelineId,
            subPipelineName = subPipelineName,
            taskId = element.id ?: "",
            containerName = container.name,
            stageName = stage.name ?: "",
            taskName = element.name ?: "",
            channel = ChannelCode.BS.name,
            userId = userId
        )
        logger.info("start check sub pipeline element|$subPipelineRef")
        return subPipelineRef.check(
            listOf(
                this::checkPermission,
                this::checkCircularDependency
            )
        )
    }

    fun SubPipelineRef.check(
        list: List<(SubPipelineRef) -> ElementCheckResult>
    ): ElementCheckResult {
        list.forEach {
            val invoke = it.invoke(this)
            if (!invoke.result) {
                return invoke
            }
        }
        return ElementCheckResult(true)
    }

    fun checkPermission(subPipelineRef: SubPipelineRef): ElementCheckResult {
        with(subPipelineRef) {
            logger.info(
                "check the sub-pipeline permissions when deploying pipeline|" +
                        "project:$projectId|elementId:$taskId|userId:$userId|" +
                        "subProjectId:$subProjectId|subPipelineId:$subPipelineId"
            )
            // 校验流水线修改人是否有子流水线执行权限
            val checkPermission = pipelinePermissionService.checkPipelinePermission(
                userId = userId,
                projectId = subProjectId,
                pipelineId = subPipelineId,
                permission = AuthPermission.EXECUTE
            )
            val pipelinePermissionUrl =
                "/console/pipeline/$subProjectId/$subPipelineId/history"
            return if (checkPermission) {
                ElementCheckResult(true)
            } else {
                ElementCheckResult(
                    result = false,
                    errorTitle = I18nUtil.getCodeLanMessage(
                        messageCode = ProcessMessageCode.BK_NOT_SUB_PIPELINE_EXECUTE_PERMISSION_ERROR_TITLE,
                        params = arrayOf(userId)
                    ),
                    errorMessage = I18nUtil.getCodeLanMessage(
                        messageCode = ProcessMessageCode.BK_NOT_SUB_PIPELINE_EXECUTE_PERMISSION_ERROR_MESSAGE,
                        params = arrayOf(
                            stageName, containerName, taskName, pipelinePermissionUrl, subPipelineName
                        )
                    )
                )
            }
        }
    }

    fun checkCircularDependency(subPipelineRef: SubPipelineRef): ElementCheckResult {
        val checkResult = with(subPipelineRef) {
            subPipelineRefService.checkCircularDependency(
                subPipelineRef = this,
                rootNode = true,
                existsPipeline = HashMap(mapOf("${projectId}_$pipelineId" to this))
            )
        }
        logger.info("check circular dependency result|$checkResult")
        return ElementCheckResult(true)
    }
}
