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

package com.tencent.devops.artifactory.api

import com.tencent.devops.common.api.auth.AUTH_HEADER_USER_ID
import com.tencent.devops.common.api.pojo.Result
import io.swagger.annotations.Api
import io.swagger.annotations.ApiOperation
import io.swagger.annotations.ApiParam
import javax.servlet.http.HttpServletResponse
import javax.ws.rs.Consumes
import javax.ws.rs.DELETE
import javax.ws.rs.GET
import javax.ws.rs.HeaderParam
import javax.ws.rs.PUT
import javax.ws.rs.Path
import javax.ws.rs.PathParam
import javax.ws.rs.Produces
import javax.ws.rs.QueryParam
import javax.ws.rs.core.Context
import javax.ws.rs.core.MediaType

@Api(tags = ["SERVICE_ARTIFACTORY"], description = "仓库-插件")
@Path("/service/artifactories/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
interface ServiceArchiveAtomResource {

    @ApiOperation("获取插件包文件内容")
    @GET
    @Path("/atom/file/content")
    fun getAtomFileContent(
        @ApiParam("文件路径", required = true)
        @QueryParam("filePath")
        filePath: String
    ): Result<String>

    @ApiOperation("下载插件包文件")
    @GET
    @Path("/atom/file/download")
    fun downloadAtomFile(
        @ApiParam("文件路径", required = true)
        @QueryParam("filePath")
        filePath: String,
        @Context
        response: HttpServletResponse
    )

    @ApiOperation("删除插件包文件")
    @DELETE
    @Path("/atom/file/delete")
    fun deleteAtomFile(
        @ApiParam("用户ID", required = true)
        @HeaderParam(AUTH_HEADER_USER_ID)
        userId: String,
        @ApiParam("项目编码", required = true)
        @QueryParam("projectCode")
        projectCode: String,
        @ApiParam("插件代码", required = true)
        @QueryParam("atomCode")
        atomCode: String
    ): Result<Boolean>

    @ApiOperation("更新插件包文件内容")
    @PUT
    @Path("/projectCodes/{projectCode}/atoms/{atomCode}/file/content")
    fun updateArchiveFile(
        @ApiParam("项目编码", required = true)
        @PathParam("projectCode")
        projectCode: String,
        @ApiParam("插件编码", required = true)
        @PathParam("atomCode")
        atomCode: String,
        @ApiParam("插件版本号", required = true)
        @QueryParam("version")
        version: String,
        @ApiParam("文件名", required = true)
        @QueryParam("fileName")
        fileName: String,
        @ApiParam("文件内容", required = true)
        content: String
    ): Result<Boolean>
}
