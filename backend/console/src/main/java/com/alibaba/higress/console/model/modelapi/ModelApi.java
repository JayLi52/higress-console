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
import java.util.Map;

import com.alibaba.higress.sdk.model.CommonPageQuery;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Model API definition, the first-class resource object which describes a stable external AI endpoint.
 * <p>
 * Column groups follow the model_api table design: identifiers are immutable after creation; scalar configs and JSON
 * domain objects belong to the user editing chain; publish columns are owned by the async publisher only; version is
 * the optimistic lock.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelApi implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "Model API id. Generated server-side as \"m-\" + 20 random chars. Immutable.")
    private String apiId;

    @Schema(description = "Model API name. Unique within the gateway instance and immutable after creation.",
        requiredMode = Schema.RequiredMode.REQUIRED,
        example = "qwen-chat")
    private String apiName;

    @Schema(description = "Gateway instance id.", requiredMode = Schema.RequiredMode.REQUIRED)
    private String gwInstanceId;

    @Schema(description = "Protocol of the external contract.", requiredMode = Schema.RequiredMode.REQUIRED,
        example = "OPENAI")
    private String protocol;

    @Schema(description = "Business scene type.", requiredMode = Schema.RequiredMode.REQUIRED,
        example = "TEXT_GENERATION")
    private String sceneType;

    @Schema(description = "Human readable description.")
    private String description;

    @Schema(description = "Base path prefix of the external contract, e.g. /api/qwen/",
        requiredMode = Schema.RequiredMode.REQUIRED)
    private String basePath;

    @Schema(description = "Whether the base path is removed before forwarding to the upstream.")
    private Boolean basePathRemove;

    @Schema(description = "First byte timeout in milliseconds. Null means the global default is used.")
    private Integer firstByteTimeout;

    @Schema(description = "Method-path list of the external contract.")
    private List<MethodPath> methodPathList;

    @Schema(description = "Bound domain names. At most 1 due to the single-route limitation.")
    private List<String> domainNameList;

    @Schema(description = "Route dispatcher which distributes requests to provider pools.")
    private RouteDispatcher routeDispatcher;

    @Schema(description = "Upstream affinity config, JSON object passthrough in this phase.")
    private Map<String, Object> upstreamAffinityConfig;

    @Schema(description = "Observation config, JSON object passthrough in this phase.")
    private Map<String, Object> observationConfig;

    @Schema(description = "Fallback config, JSON object passthrough in this phase.")
    private Map<String, Object> fallback;

    @Schema(description = "Authentication config, JSON object passthrough in this phase.")
    private Map<String, Object> authenticationConfig;

    @Schema(description = "Policy config, JSON object passthrough in this phase.")
    private Map<String, Object> policyConfig;

    @Schema(description = "Publish status. Read-only for the user chain; written by the publisher only.",
        example = "DRAFT")
    private String publishStatus;

    @Schema(description = "Failure reason of the latest publish. Read-only.")
    private String publishFailureReason;

    @Schema(description = "Optimistic lock version. Must be carried on updates.")
    private Long version;

    @Schema(description = "Desired configuration version; identical to version for compatibility.")
    private Long configVersion;

    @Schema(description = "Last configuration verified against Kubernetes; does not confirm Envoy activation.")
    private Long appliedVersion;

    private java.time.Instant lastReconcileAt;
    private String lastError;
    private java.time.Instant deletedAt;

    @Schema(description = "Creation time.")
    private java.time.Instant gmtCreate;

    @Schema(description = "Last modification time.")
    private java.time.Instant gmtModified;

    public void validate() {
        if (apiName == null || apiName.isEmpty()) {
            throw new com.alibaba.higress.sdk.exception.ValidationException("apiName is required.");
        }
        if (!apiName.matches("^(?!-)[a-z0-9-]{1,49}[a-z0-9]$")) {
            throw new com.alibaba.higress.sdk.exception.ValidationException(
                "Invalid apiName. It must be 2-50 chars of lowercase alphanumerics and hyphens, "
                    + "must not start with a hyphen, and must end with an alphanumeric.");
        }
        if (protocol == null || protocol.isEmpty()) {
            throw new com.alibaba.higress.sdk.exception.ValidationException("protocol is required.");
        }
        if (sceneType == null || sceneType.isEmpty()) {
            throw new com.alibaba.higress.sdk.exception.ValidationException("sceneType is required.");
        }
        if (basePath == null || basePath.isEmpty() || !basePath.matches("/[^?#\\s]*") || basePath.contains("//")) {
            throw new com.alibaba.higress.sdk.exception.ValidationException("basePath is required and must start with \"/\".");
        }
        if (domainNameList != null && domainNameList.size() > 1) {
            throw new com.alibaba.higress.sdk.exception.ValidationException("At most 1 domain is supported.");
        }
        if (firstByteTimeout != null && firstByteTimeout <= 0) {
            throw new com.alibaba.higress.sdk.exception.ValidationException("firstByteTimeout must be a positive integer.");
        }
        if (methodPathList == null || methodPathList.isEmpty()) {
            throw new com.alibaba.higress.sdk.exception.ValidationException("methodPathList cannot be empty.");
        }
        MethodPath.validateList(methodPathList);
        if (routeDispatcher == null) {
            throw new com.alibaba.higress.sdk.exception.ValidationException("routeDispatcher is required.");
        }
        routeDispatcher.validate();
    }

    /**
     * Fill the instance-wide default gateway instance id for queries created without one.
     */
    public void applyDefaults() {
        if (gwInstanceId == null || gwInstanceId.isEmpty()) {
            gwInstanceId = "default";
        }
    }

    public boolean matchesQuery(String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return true;
        }
        String lower = keyword.toLowerCase();
        return apiName != null && apiName.toLowerCase().contains(lower)
            || description != null && description.toLowerCase().contains(lower)
            || basePath != null && basePath.toLowerCase().contains(lower);
    }

    public static class PageQuery extends CommonPageQuery {
        private String keyword;
        private String gwInstanceId;

        public String getGwInstanceId() { return gwInstanceId; }
        public void setGwInstanceId(String gwInstanceId) { this.gwInstanceId = gwInstanceId; }


        public String getKeyword() {
            return keyword;
        }

        public void setKeyword(String keyword) {
            this.keyword = keyword;
        }
    }
}
