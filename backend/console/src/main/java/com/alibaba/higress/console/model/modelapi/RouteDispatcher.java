/*
 * Copyright (c) 2022-2023 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package com.alibaba.higress.console.model.modelapi;

import java.io.Serializable;
import java.util.List;

import com.alibaba.higress.sdk.exception.ValidationException;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Route dispatcher of a Model API: how requests are distributed to provider pools.
 * <p>
 * Strategies follow the source product: HEADER (model name Glob routing), WEIGHT_MODEL_MAPPING (weighted routing),
 * CONTEXT_LENGTH (context window routing, reserved) and SINGLE_MODEL_MAPPING (single provider).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RouteDispatcher implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String STRATEGY_HEADER = "HEADER";
    public static final String STRATEGY_WEIGHT = "WEIGHT_MODEL_MAPPING";
    public static final String STRATEGY_CONTEXT_LENGTH = "CONTEXT_LENGTH";
    public static final String STRATEGY_SINGLE = "SINGLE_MODEL_MAPPING";

    @Schema(description = "Dispatch strategy.",
        requiredMode = Schema.RequiredMode.REQUIRED,
        example = "WEIGHT_MODEL_MAPPING")
    private String strategy;

    @Schema(description = "Provider pool the dispatcher distributes to.")
    private List<DispatcherTarget> targets;

    public void validate() {
        if (StringUtils.isEmpty(strategy)) {
            throw new ValidationException("routeDispatcher.strategy is required.");
        }
        switch (strategy) {
            case STRATEGY_HEADER:
            case STRATEGY_WEIGHT:
            case STRATEGY_SINGLE:
            case STRATEGY_CONTEXT_LENGTH:
                break;
            default:
                throw new ValidationException("Unsupported dispatcher strategy: " + strategy);
        }
        if (CollectionUtils.isEmpty(targets)) {
            throw new ValidationException("routeDispatcher.targets cannot be empty.");
        }
        boolean weighted = STRATEGY_WEIGHT.equals(strategy);
        for (DispatcherTarget target : targets) {
            if (target == null || StringUtils.isEmpty(target.getProvider())) {
                throw new ValidationException("Each dispatcher target requires a provider.");
            }
            if (weighted) {
                Integer weight = target.getWeight();
                if (weight == null || weight < 1 || weight > 1000) {
                    throw new ValidationException(
                        "Weighted routing requires a weight between 1 and 1000 for provider: " + target.getProvider());
                }
            }
        }
        if (STRATEGY_SINGLE.equals(strategy) && targets.size() > 1) {
            throw new ValidationException("SINGLE_MODEL_MAPPING only allows one target provider.");
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DispatcherTarget implements Serializable {

        private static final long serialVersionUID = 1L;

        @Schema(description = "Target provider name.", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "qwen-main")
        private String provider;

        @Schema(description = "Weight of the provider under weighted routing, 1-1000.")
        private Integer weight;

        @Schema(description = "Model name Glob for HEADER routing, e.g. qwen-*.")
        private String modelGlob;

        @Schema(description = "Model name rewritten to before hitting the upstream provider.",
            example = "qwen-max")
        private String targetModel;

        @Schema(description = "Reserved for CONTEXT_LENGTH routing.")
        private Integer contextLowerBound;

        @Schema(description = "Reserved for CONTEXT_LENGTH routing.")
        private Integer contextUpperBound;
    }
}
