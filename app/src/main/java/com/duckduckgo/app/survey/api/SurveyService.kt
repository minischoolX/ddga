/*
 * Copyright (c) 2019 DuckDuckGo
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

package com.duckduckgo.app.survey.api

import com.duckduckgo.anvil.annotations.ContributesServiceApi
import com.duckduckgo.di.scopes.AppScope
import retrofit2.Call
import retrofit2.http.GET

@ContributesServiceApi(AppScope::class)
interface SurveyService {

    @GET("https://staticcdn.duckduckgo.com/survey/v2/survey-mobile.json")
    fun survey(): Call<SurveyGroup?>

    @GET("https://staticcdn.duckduckgo.com/survey/netp/waitlist-survey-mobile.json")
    fun surveyNetPWaitlistBeta(): Call<SurveyGroup?>
}
