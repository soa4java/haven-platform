/*
 * Copyright 2016 Code Above Lab LLC
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

package com.codeabovelab.dm.cluman.events;

import com.codeabovelab.dm.cluman.model.WithSeverity;
import com.codeabovelab.dm.common.mb.Subscriptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 */
@Configuration
public class EventsConfiguration {
    @Bean(name = EventsUtils.BUS_ERRORS)
    Subscriptions<WithSeverity> errorMessageBus(ErrorAggregator errorAggregator) {
        return errorAggregator.getSubscriptions();
    }
}
