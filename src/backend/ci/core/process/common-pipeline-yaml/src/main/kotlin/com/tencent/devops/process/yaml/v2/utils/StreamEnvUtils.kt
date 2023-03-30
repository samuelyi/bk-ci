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

package com.tencent.devops.process.yaml.v2.utils

import com.tencent.devops.common.api.util.MessageUtil
import com.tencent.devops.common.web.utils.I18nUtil
import com.tencent.devops.process.constant.ProcessMessageCode.ERROR_YAML_FORMAT_EXCEPTION_ENV_QUANTITY_LIMIT_EXCEEDED
import com.tencent.devops.process.constant.ProcessMessageCode.ERROR_YAML_FORMAT_EXCEPTION_ENV_VARIABLE_LENGTH_LIMIT_EXCEEDED
import com.tencent.devops.process.yaml.v2.exception.YamlFormatException

object StreamEnvUtils {
    fun checkEnv(env: Map<String, Any?>?, fileName: String? = null): Boolean {
        if (env != null) {
            if (env.size > 100) {
                throw YamlFormatException(
                    MessageUtil.getMessageByLocale(
                        ERROR_YAML_FORMAT_EXCEPTION_ENV_QUANTITY_LIMIT_EXCEEDED,
                        I18nUtil.getLanguage(I18nUtil.getRequestUserId()),
                        arrayOf(fileName ?: "")
                    )
                )
            }

            env.forEach { (t, u) ->
                if (t.length > 128) {
                    throw YamlFormatException(
                        MessageUtil.getMessageByLocale(
                            ERROR_YAML_FORMAT_EXCEPTION_ENV_VARIABLE_LENGTH_LIMIT_EXCEEDED,
                            I18nUtil.getLanguage(I18nUtil.getRequestUserId()),
                            arrayOf(fileName ?: "", "key", "128", t)
                        )
                    )
                }

                if (u != null && u.toString().length > 4000) {
                    throw YamlFormatException(MessageUtil.getMessageByLocale(
                        ERROR_YAML_FORMAT_EXCEPTION_ENV_VARIABLE_LENGTH_LIMIT_EXCEEDED,
                        I18nUtil.getLanguage(I18nUtil.getRequestUserId()),
                        arrayOf(fileName ?: "", "value", "4k", t)
                    ))
                }
            }
            return true
        } else {
            return true
        }
    }
}
