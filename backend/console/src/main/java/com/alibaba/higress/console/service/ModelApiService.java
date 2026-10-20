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
package com.alibaba.higress.console.service;

import com.alibaba.higress.console.model.modelapi.ModelApi;
import com.alibaba.higress.sdk.model.PaginatedResult;

/**
 * Model API management service.
 * <p>
 * Phase 1 contract is backed by an in-memory store seeded with demo data. The interface intentionally mirrors the
 * model_api table column groups so that a PostgreSQL-backed implementation can replace the store without touching
 * controllers: user edits only write config columns guarded by the version optimistic lock.
 * Publishing is intentionally unavailable in this phase.
 */
public interface ModelApiService {

    default String storage() { return "memory"; }

    PaginatedResult<ModelApi> list(ModelApi.PageQuery query);

    ModelApi query(String apiName, String gwInstanceId);

    ModelApi add(ModelApi modelApi);

    ModelApi update(ModelApi modelApi);

    void delete(String apiName, String gwInstanceId, Long version);

}
