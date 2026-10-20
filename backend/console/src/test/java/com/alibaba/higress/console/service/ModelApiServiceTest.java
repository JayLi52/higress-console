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

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import com.alibaba.higress.console.model.modelapi.MethodPath;
import com.alibaba.higress.console.model.modelapi.ModelApi;
import com.alibaba.higress.console.model.modelapi.RouteDispatcher;
import com.alibaba.higress.sdk.exception.NotFoundException;
import com.alibaba.higress.sdk.exception.ResourceConflictException;
import com.alibaba.higress.sdk.exception.ValidationException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModelApiServiceTest {

    private ModelApiServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ModelApiServiceImpl();
    }

    private ModelApi definition(String name) {
        return ModelApi.builder().apiName(name).basePath("/" + name).protocol("OPENAI")
            .sceneType("TEXT_GENERATION")
            .methodPathList(Arrays.asList(MethodPath.builder().method("POST").path("/v1/chat/completions").build()))
            .routeDispatcher(RouteDispatcher.builder().strategy(RouteDispatcher.STRATEGY_SINGLE)
                .targets(Arrays.asList(RouteDispatcher.DispatcherTarget.builder().provider("qwen").build())).build())
            .build();
    }

    @Test
    void crudHonorsIdentityVersionAndStatusOwnership() {
        ModelApi input = definition("chat-api");
        input.setApiId("client-controlled");
        input.setVersion(123L);
        input.setPublishStatus("PUBLISHED");
        ModelApi saved = service.add(input);
        assertTrue(saved.getApiId().matches("m-[a-z0-9]{20}"));
        assertEquals(0L, saved.getVersion());
        assertEquals("DRAFT", saved.getPublishStatus());
        assertThrows(ResourceConflictException.class, () -> service.add(definition("chat-api")));

        ModelApi edit = service.query("chat-api", "default");
        edit.setDescription("updated");
        edit.setApiId("changed-id");
        edit.setPublishStatus("PUBLISHED");
        edit.setPublishFailureReason("injected");
        ModelApi updated = service.update(edit);
        assertEquals(saved.getApiId(), updated.getApiId());
        assertEquals(saved.getGmtCreate(), updated.getGmtCreate());
        assertEquals("updated", updated.getDescription());
        assertEquals("DRAFT", updated.getPublishStatus());
        assertNull(updated.getPublishFailureReason());
        assertEquals(1L, updated.getVersion());
        assertThrows(ResourceConflictException.class, () -> service.update(edit));
        assertThrows(ResourceConflictException.class, () -> service.delete("chat-api", "default", 0L));
        service.delete("chat-api", "default", 1L);
        assertThrows(NotFoundException.class, () -> service.query("chat-api", "default"));
    }

    @Test
    void nestedObjectsAreDetachedOnCreateReadListAndUpdate() {
        ModelApi input = definition("chat-api");
        Map<String, Object> nested = new HashMap<>();
        nested.put("enabled", true);
        Map<String, Object> policy = new HashMap<>();
        policy.put("rateLimit", nested);
        input.setPolicyConfig(policy);
        ModelApi saved = service.add(input);
        input.getRouteDispatcher().getTargets().get(0).setProvider("mutated-input");
        nested.put("enabled", false);
        saved.getMethodPathList().get(0).setPath("/mutated");
        assertEquals("qwen", service.query("chat-api", null).getRouteDispatcher().getTargets().get(0).getProvider());
        assertEquals(Boolean.TRUE, ((Map<?, ?>)service.query("chat-api", null).getPolicyConfig().get("rateLimit")).get("enabled"));
        assertEquals("/v1/chat/completions", service.query("chat-api", null).getMethodPathList().get(0).getPath());

        ModelApi listed = service.list(null).getData().get(0);
        listed.getRouteDispatcher().getTargets().get(0).setProvider("mutated-list");
        ModelApi edit = service.query("chat-api", null);
        edit.getRouteDispatcher().getTargets().get(0).setProvider("new-provider");
        service.update(edit);
        edit.getRouteDispatcher().getTargets().get(0).setProvider("mutated-update");
        assertEquals("new-provider", service.query("chat-api", null).getRouteDispatcher().getTargets().get(0).getProvider());
    }

    @Test
    void namesAndPathsAreScopedToGatewayInstance() {
        service.add(definition("chat-api"));
        ModelApi secondInstance = definition("chat-api");
        secondInstance.setGwInstanceId("second");
        service.add(secondInstance);
        ModelApi.PageQuery query = new ModelApi.PageQuery();
        query.setGwInstanceId("second");
        query.setKeyword("chat");
        assertEquals(1, service.list(query).getTotal());
        assertEquals("second", service.list(query).getData().get(0).getGwInstanceId());
        service.delete("chat-api", "second", 0L);
        assertEquals("default", service.query("chat-api", "default").getGwInstanceId());
    }

    @Test
    void rejectsOverlappingPathsAndInvalidMethodContracts() {
        service.add(definition("chat-api"));
        ModelApi overlap = definition("other-api");
        overlap.setBasePath("/chat-api/nested");
        assertThrows(ValidationException.class, () -> service.add(overlap));
        ModelApi invalid = definition("invalid-api");
        invalid.setMethodPathList(Arrays.asList(
            MethodPath.builder().method("MULTI").path("/v1/chat").build(),
            MethodPath.builder().method("POST").path("/v1/chat").build()));
        assertThrows(ValidationException.class, () -> service.add(invalid));
    }

    @Test
    void pagesPastTheEndAreEmptyAndKeepTheTotal() {
        service.add(definition("chat-api"));
        ModelApi.PageQuery query = new ModelApi.PageQuery();
        query.setPageNum(Integer.MAX_VALUE);
        query.setPageSize(Integer.MAX_VALUE);
        assertTrue(service.list(query).getData().isEmpty());
        assertEquals(1, service.list(query).getTotal());
    }

    @Test
    void onlyOneConcurrentWriterCanUseTheSameVersion() throws Exception {
        service.add(definition("chat-api"));
        ModelApi first = service.query("chat-api", null);
        ModelApi second = service.query("chat-api", null);
        java.util.concurrent.ExecutorService writers = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.List<java.util.concurrent.Future<Boolean>> results = new java.util.ArrayList<>();
            for (ModelApi edit : Arrays.asList(first, second)) {
                results.add(writers.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        service.update(edit);
                        return true;
                    } catch (ResourceConflictException expected) {
                        return false;
                    }
                }));
            }
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            int successes = 0;
            for (java.util.concurrent.Future<Boolean> result : results) {
                if (result.get(5, java.util.concurrent.TimeUnit.SECONDS)) successes++;
            }
            assertEquals(1, successes);
            assertEquals(1L, service.query("chat-api", null).getVersion());
        } finally {
            writers.shutdownNow();
        }
    }
}
