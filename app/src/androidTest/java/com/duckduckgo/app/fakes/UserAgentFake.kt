/*
 * Copyright (c) 2022 DuckDuckGo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.duckduckgo.app.fakes

import com.duckduckgo.privacy.config.api.DefaultPolicy
import com.duckduckgo.privacy.config.api.DefaultPolicy.DDG
import com.duckduckgo.privacy.config.api.UserAgent

class UserAgentFake : UserAgent {
    override fun isAnApplicationException(url: String): Boolean = false

    override fun isAVersionException(url: String): Boolean = false

    override fun isADefaultException(url: String): Boolean = false

    override fun defaultPolicy(): DefaultPolicy = DDG

    override fun isADdgDefaultSite(url: String): Boolean = false

    override fun isADdgFixedSite(url: String): Boolean = false

    override fun closestUserAgentEnabled(): Boolean = false

    override fun ddgFixedUserAgentEnabled(): Boolean = false

    override fun isClosestUserAgentVersion(version: String): Boolean = false

    override fun isDdgFixedUserAgentVersion(version: String): Boolean = false
}
