package com.tencent.devops.auth.service

import com.google.common.cache.CacheBuilder
import com.tencent.bk.sdk.iam.dto.V2PageInfoDTO
import com.tencent.bk.sdk.iam.dto.application.ApplicationDTO
import com.tencent.bk.sdk.iam.dto.manager.dto.SearchGroupDTO
import com.tencent.bk.sdk.iam.dto.manager.vo.V2ManagerRoleGroupVO
import com.tencent.bk.sdk.iam.dto.response.GroupPermissionDetailResponseDTO
import com.tencent.bk.sdk.iam.service.v2.V2ManagerService
import com.tencent.devops.auth.constant.AuthMessageCode
import com.tencent.devops.auth.dao.AuthActionDao
import com.tencent.devops.auth.dao.AuthResourceGroupConfigDao
import com.tencent.devops.auth.dao.AuthResourceGroupDao
import com.tencent.devops.auth.dao.AuthResourceTypeDao
import com.tencent.devops.auth.pojo.ApplicationInfo
import com.tencent.devops.auth.pojo.RelatedResourceInfo
import com.tencent.devops.auth.pojo.SearchGroupInfo
import com.tencent.devops.auth.pojo.vo.ActionInfoVo
import com.tencent.devops.auth.pojo.vo.AuthApplyRedirectInfoVo
import com.tencent.devops.auth.pojo.vo.AuthRedirectGroupInfoVo
import com.tencent.devops.auth.pojo.vo.GroupPermissionDetailVo
import com.tencent.devops.auth.pojo.vo.ResourceTypeInfoVo
import com.tencent.devops.auth.service.iam.PermissionApplyService
import com.tencent.devops.auth.service.iam.PermissionResourceService
import com.tencent.devops.common.api.exception.ErrorCodeException
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit

@Service
@Suppress("ALL")
class RbacPermissionApplyService @Autowired constructor(
    val dslContext: DSLContext,
    val v2ManagerService: V2ManagerService,
    val permissionResourceService: PermissionResourceService,
    val strategyService: StrategyService,
    val authResourceService: AuthResourceService,
    val authResourceGroupConfigDao: AuthResourceGroupConfigDao,
    val authResourceGroupDao: AuthResourceGroupDao,
    val authResourceTypeDao: AuthResourceTypeDao,
    val authActionDao: AuthActionDao
) : PermissionApplyService {
    @Value("\${auth.iamSystem:}")
    private val systemId = ""

    @Value("\${devopsGateway.host:}")
    private val host = ""

    private val authApplyRedirectUrl = "$host/console/permission/%s/applyPermission?" +
        "projectId=%s&groupId=%s&resourceType=%s&resourceName=%s&action=%s"

    private val actionCache = CacheBuilder.newBuilder()
        .maximumSize(10000)
        .expireAfterWrite(7L, TimeUnit.DAYS)
        .build<String, List<ActionInfoVo>>()
    private val resourceTypesCache = CacheBuilder.newBuilder()
        .maximumSize(10000)
        .expireAfterWrite(7L, TimeUnit.DAYS)
        .build<String, List<ResourceTypeInfoVo>>()

    override fun listResourceTypes(userId: String): List<ResourceTypeInfoVo> {
        if (resourceTypesCache.getIfPresent(ALL_RESOURCE) == null) {
            val resourceTypeList = authResourceTypeDao.list(dslContext).map {
                ResourceTypeInfoVo(
                    resourceType = it.resourcetype,
                    name = it.name,
                    parent = it.parent,
                    system = it.system
                )
            }
            resourceTypesCache.put(ALL_RESOURCE, resourceTypeList)
        }
        return resourceTypesCache.getIfPresent(ALL_RESOURCE)!!
    }

    override fun listActions(userId: String, resourceType: String): List<ActionInfoVo> {
        if (actionCache.getIfPresent(resourceType) == null) {
            val actionList = authActionDao.list(dslContext, resourceType)
            if (actionList.isEmpty()) {
                throw ErrorCodeException(
                    errorCode = AuthMessageCode.RESOURCE_ACTION_EMPTY,
                    params = arrayOf(resourceType),
                    defaultMessage = "权限系统：[$resourceType]资源类型关联的动作不存在"
                )
            }
            val actionInfoVoList = actionList.map {
                ActionInfoVo(
                    actionId = it.actionid,
                    actionName = it.actionname,
                    resourceType = it.resourcetype
                )
            }
            actionCache.put(resourceType, actionInfoVoList)
        }
        return actionCache.getIfPresent(resourceType)!!

    }

    override fun listGroups(
        userId: String,
        projectId: String,
        searchGroupInfo: SearchGroupInfo
    ): V2ManagerRoleGroupVO {
        val projectInfo = authResourceService.get(
            projectCode = projectId,
            resourceType = "project",
            resourceCode = projectId
        )
        val searchGroupDTO = SearchGroupDTO
            .builder()
            .inherit(searchGroupInfo.inherit)
            .id(searchGroupInfo.groupId)
            .actionId(searchGroupInfo.actionId)
            .resourceTypeSystemId(systemId)
            .resourceTypeId(searchGroupInfo.resourceType)
            .resourceId(searchGroupInfo.resourceCode)
            .bkIamPath(searchGroupInfo.bkIamPath)
            .name(searchGroupInfo.name)
            .description(searchGroupInfo.description)
            .build()
        val v2PageInfoDTO = V2PageInfoDTO()
        v2PageInfoDTO.pageSize = searchGroupInfo.pageSize
        v2PageInfoDTO.page = searchGroupInfo.page
        try {
            return v2ManagerService.getGradeManagerRoleGroupV2(projectInfo.relationId, searchGroupDTO, v2PageInfoDTO)
        } catch (e: Exception) {
            throw ErrorCodeException(
                errorCode = AuthMessageCode.GET_IAM_GROUP_FAIL,
                defaultMessage = "权限系统：获取用户组失败！"
            )
        }
    }

    override fun applyToJoinGroup(userId: String, applicationInfo: ApplicationInfo): Boolean {
        try {
            val iamApplicationDTO = ApplicationDTO
                .builder()
                .groupId(applicationInfo.groupIds)
                .applicant(userId)
                .expiredAt(applicationInfo.expiredAt.toLong())
                .reason(applicationInfo.reason).build()
            v2ManagerService.createRoleGroupApplicationV2(iamApplicationDTO)
        } catch (e: Exception) {
            throw ErrorCodeException(
                errorCode = AuthMessageCode.APPLY_TO_JOIN_GROUP_FAIL,
                params = arrayOf(applicationInfo.groupIds.toString()),
                defaultMessage = "权限系统：申请加入用户组[${applicationInfo.groupIds}]失败！"
            )
        }
        return true
    }

    override fun getGroupPermissionDetail(userId: String, groupId: Int): List<GroupPermissionDetailVo> {
        val iamGroupPermissionDetailList: List<GroupPermissionDetailResponseDTO>
        try {
            iamGroupPermissionDetailList = v2ManagerService.getGroupPermissionDetail(groupId)
        } catch (e: Exception) {
            throw ErrorCodeException(
                errorCode = AuthMessageCode.GET_GROUP_PERMISSION_DETAIL_FAIL,
                params = arrayOf(groupId.toString()),
                defaultMessage = "权限系统：获取用户组[$groupId]权限信息失败！"
            )
        }
        val groupPermissionDetailVoList: MutableList<GroupPermissionDetailVo> = ArrayList()
        iamGroupPermissionDetailList.forEach {
            val relatedResourceTypesDTO = it.resourceGroups[0].relatedResourceTypesDTO[0]
            val relatedResourceInfo = RelatedResourceInfo(
                type = relatedResourceTypesDTO.type,
                name = relatedResourceTypesDTO.name,
                instances = relatedResourceTypesDTO.condition[0].instances[0]
            )
            groupPermissionDetailVoList.add(
                GroupPermissionDetailVo(
                    actionId = it.id,
                    name = it.name,
                    relatedResourceInfo = relatedResourceInfo
                )
            )
        }
        return groupPermissionDetailVoList
    }

    override fun getRedirectInformation(
        userId: String,
        projectId: String,
        resourceType: String,
        resourceCode: String,
        action: String
    ): AuthApplyRedirectInfoVo {
        val groupInfoList: ArrayList<AuthRedirectGroupInfoVo> = ArrayList()
        val isEnablePermission = permissionResourceService.isEnablePermission(
            projectId = projectId,
            resourceType = resourceType,
            resourceCode = resourceCode
        )

        if (resourceTypesCache.getIfPresent(ALL_RESOURCE) == null) {
            listResourceTypes(userId)
        }
        val resourceTypeName = resourceTypesCache.getIfPresent(ALL_RESOURCE)!!.filter { it.resourceType == resourceType }[0].name

        if (actionCache.getIfPresent(resourceType) == null) {
            listActions(userId, resourceType)
        }
        val actionName = actionCache.getIfPresent(resourceType)!!.filter { it.actionId == action }[0].actionName

        val resourceName = authResourceService.get(
            projectCode = projectId,
            resourceType = resourceType,
            resourceCode = resourceCode
        ).resourceName

        if (isEnablePermission) {
            // 若开启权限,则得根据资源类型去查询默认组，然后查询组的策略，看是否包含对应 资源+动作
            authResourceGroupConfigDao.get(dslContext, resourceType).forEach {
                val strategy = strategyService.getStrategyByName(it.resourceType + "_" + it.groupCode)?.strategy
                if (strategy != null) {
                    val isStrategyContainsAction = strategy[resourceType]?.contains(action.substring(action.lastIndexOf("_")+1))
                    if (isStrategyContainsAction != null && isStrategyContainsAction) {
                        buildGroupInfoList(
                            groupInfoList = groupInfoList,
                            projectId = projectId,
                            userId = userId,
                            resourceName = resourceName,
                            action = action,
                            resourceType = resourceType,
                            resourceCode = resourceCode,
                            groupCode = it.groupCode
                        )
                    }
                }
            }
        } else {
            buildGroupInfoList(
                groupInfoList = groupInfoList,
                projectId = projectId,
                userId = userId,
                resourceName = resourceName,
                action = action,
                resourceType = resourceType,
                resourceCode = resourceCode,
                groupCode = "manager"
            )
        }
        if (groupInfoList.isEmpty()) {
            throw ErrorCodeException(
                errorCode = AuthMessageCode.GET_REDIRECT_INFORMATION_FAIL,
                defaultMessage = "权限系统: 获取权限申请跳转信息失败！"
            )
        }
        return AuthApplyRedirectInfoVo(
            auth = isEnablePermission,
            resourceTypeName = resourceTypeName,
            resourceName = resourceName,
            actionName = actionName,
            groupInfoList = groupInfoList
        )
    }

    private fun buildGroupInfoList(
        projectId: String,
        groupInfoList: ArrayList<AuthRedirectGroupInfoVo>,
        userId: String,
        resourceName: String,
        action: String,
        resourceType: String,
        resourceCode: String,
        groupCode: String,
    ) {
        val resourceGroup = authResourceGroupDao.get(
            dslContext = dslContext,
            projectCode = projectId,
            resourceType = resourceType,
            resourceCode = resourceCode,
            groupCode = groupCode
        )
        if (resourceGroup != null) {
            groupInfoList.add(
                AuthRedirectGroupInfoVo(
                    url = String.format(
                        authApplyRedirectUrl, userId, projectId,
                        resourceGroup.relationId, resourceType, resourceName, action
                    ),
                    groupName = resourceGroup.groupName
                )
            )
        }
    }


    companion object {
        private val logger = LoggerFactory.getLogger(GroupUserService::class.java)
        private const val ALL_RESOURCE = "all_resource"
    }
}
