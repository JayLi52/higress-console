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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.alibaba.higress.sdk.exception.ValidationException;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;

/**
 * One entry of the external contract: an HTTP method plus a path under the base path.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MethodPath implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String METHOD_ANY = "MULTI";

    @Schema(description = "HTTP method. Use MULTI for matching any method.",
        requiredMode = Schema.RequiredMode.REQUIRED,
        example = "POST")
    private String method;

    @Schema(description = "Path under the base path. Variables like {var} are supported.",
        requiredMode = Schema.RequiredMode.REQUIRED,
        example = "/v1/chat/completions")
    private String path;

    /**
     * Validate the whole list: every entry must be well-formed, method+path pairs must be unique, and MULTI must not
     * coexist with a concrete method on the same path.
     */
    public static void validateList(List<MethodPath> list) {
        Set<String> concreteKeys = new HashSet<>();
        Set<String> multiPaths = new HashSet<>();
        for (MethodPath mp : list) {
            if (mp == null || StringUtils.isEmpty(mp.method) || StringUtils.isEmpty(mp.path)) {
                throw new ValidationException("Each methodPath entry requires both method and path.");
            }
            String method = mp.method.trim().toUpperCase();
            String path = mp.path.trim();
            if (!path.matches("/[^?#\\s]*") || path.contains("//")) {
                throw new ValidationException("Invalid path: " + path);
            }
            mp.setMethod(method);
            mp.setPath(path);
            if (METHOD_ANY.equals(method)) {
                if (!multiPaths.add(path)) {
                    throw new ValidationException("Duplicated MULTI entry for path: " + path);
                }
            } else {
                if (!method.matches("GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS")) {
                    throw new ValidationException("Unsupported HTTP method: " + method);
                }
                if (!concreteKeys.add(method + " " + path)) {
                    throw new ValidationException("Duplicated method+path entry: " + method + " " + path);
                }
            }
        }
        for (String multiPath : multiPaths) {
            if (!concreteKeys.isEmpty()) {
                for (String key : concreteKeys) {
                    String concretePath = key.substring(key.indexOf(' ') + 1);
                    if (concretePath.equals(multiPath)) {
                        throw new ValidationException(
                            "MULTI cannot coexist with a concrete method on the same path: " + multiPath);
                    }
                }
            }
        }
    }
}
