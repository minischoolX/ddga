/*
 * Copyright (c) 2021 DuckDuckGo
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

package com.duckduckgo.mobile.android.vpn.exclusion

sealed class AppCategory {
    object Undefined : AppCategory() {
        override fun toString() = "CATEGORY_UNDEFINED"
    }

    object Game : AppCategory() {
        override fun toString() = "CATEGORY_GAME"
    }

    object Audio : AppCategory() {
        override fun toString() = "CATEGORY_AUDIO"
    }

    object Video : AppCategory() {
        override fun toString() = "CATEGORY_VIDEO"
    }

    object Image : AppCategory() {
        override fun toString() = "CATEGORY_IMAGE"
    }

    object Social : AppCategory() {
        override fun toString() = "CATEGORY_SOCIAL"
    }

    object News : AppCategory() {
        override fun toString() = "CATEGORY_NEWS"
    }

    object Maps : AppCategory() {
        override fun toString() = "CATEGORY_MAPS"
    }

    object Productivity : AppCategory() {
        override fun toString() = "CATEGORY_PRODUCTIVITY"
    }
}
