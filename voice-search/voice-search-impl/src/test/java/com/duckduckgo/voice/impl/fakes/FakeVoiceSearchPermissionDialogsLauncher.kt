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

package com.duckduckgo.voice.impl.fakes

import android.content.Context
import com.duckduckgo.voice.impl.VoiceSearchPermissionDialogsLauncher

class FakeVoiceSearchPermissionDialogsLauncher : VoiceSearchPermissionDialogsLauncher {
    var noMicAccessDialogShown = false
    var rationaleDialogShown = false
    var removeVoiceSearchDialogShown = false
    var boundOnRationaleAccepted: () -> Unit = {}
    var boundOnRationaleDeclined: () -> Unit = {}
    var boundNoMicAccessDialogDeclined: () -> Unit = {}
    var boundRemoveVoiceSearchAccepted: () -> Unit = {}

    override fun showNoMicAccessDialog(
        context: Context,
        onSettingsLaunchSelected: () -> Unit,
        onSettingsLaunchDeclined: () -> Unit,
    ) {
        noMicAccessDialogShown = true
        boundNoMicAccessDialogDeclined = onSettingsLaunchDeclined
    }

    override fun showPermissionRationale(
        context: Context,
        onRationaleAccepted: () -> Unit,
        onRationaleDeclined: () -> Unit,
    ) {
        rationaleDialogShown = true
        boundOnRationaleAccepted = onRationaleAccepted
        boundOnRationaleDeclined = onRationaleDeclined
    }

    override fun showRemoveVoiceSearchDialog(
        context: Context,
        onRemoveVoiceSearch: () -> Unit,
        onRemoveVoiceSearchCancelled: () -> Unit,
    ) {
        removeVoiceSearchDialogShown = true
        boundRemoveVoiceSearchAccepted = onRemoveVoiceSearch
    }
}
