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

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import com.alibaba.higress.console.model.modelapi.MethodPath;
import com.alibaba.higress.console.model.modelapi.ModelApi;
import com.alibaba.higress.console.model.modelapi.RouteDispatcher;
import com.alibaba.higress.sdk.exception.ResourceConflictException;
import com.alibaba.higress.sdk.exception.ValidationException;
import com.alibaba.higress.sdk.model.PaginatedResult;

import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * In-memory Model API service seeded with demo data.
 * <p>
 * Phase 1 scope agreed with the user: fake data, no async publisher, no data-plane translation. The store keeps the
 * same editing invariants as the model_api table:
 * <ul>
 * <li>apiName is unique and immutable after creation;</li>
 * <li>updates are guarded by the version optimistic lock and never touch the publish columns;</li>
 * </ul>
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    name = "model-api.storage", havingValue = "memory", matchIfMissing = true)
@Service("ModelApiService")
@Slf4j
public class ModelApiServiceImpl implements ModelApiService {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PUBLISHING = "PUBLISHING";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_FAILED = "FAILED";

    private static final String API_ID_PREFIX = "m-";
    private static final int API_ID_RANDOM_LENGTH = 20;
    private static final String API_ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

    private static final ObjectMapper COPY_MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private final SecureRandom random = new SecureRandom();

    /**
     * Keyed by gateway instance and API name.
     */
    private final Map<String, ModelApi> store = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        registerDemoData();
        log.info("ModelApiService initialized with {} demo Model APIs.", store.size());
    }

    @Override
    public synchronized PaginatedResult<ModelApi> list(ModelApi.PageQuery query) {
        List<ModelApi> all = store.values().stream()
            .filter(api -> api.getGwInstanceId().equals(
                query == null ? "default" : StringUtils.defaultIfBlank(query.getGwInstanceId(), "default")))
            .filter(api -> query == null || api.matchesQuery(query.getKeyword()))
            .sorted(Comparator.comparing(ModelApi::getGmtModified, Comparator.nullsLast(Comparator.reverseOrder())))
            .map(ModelApiServiceImpl::copy)
            .collect(Collectors.toList());
        PaginatedResult<ModelApi> result = PaginatedResult.createFromFullList(all, null);
        if (query != null && query.paginationEnabled()) {
            int page = query.getPageNum() == null ? 1 : Math.max(1, query.getPageNum());
            int size = query.getPageSize() == null || query.getPageSize() < 1
                ? PaginatedResult.DEFAULT_PAGE_SIZE : query.getPageSize();
            int start = (int)Math.min((long)(page - 1) * size, all.size());
            int end = (int)Math.min((long)start + size, all.size());
            result.setPageNum(page);
            result.setPageSize(size);
            result.setData(all.subList(start, end));
        }
        return result;
    }

    @Override
    public synchronized ModelApi query(String apiName, String gwInstanceId) {
        ModelApi api = store.get(key(gwInstanceId, apiName));
        if (api == null) {
            throw new com.alibaba.higress.sdk.exception.NotFoundException("Model API not found: " + apiName);
        }
        return copy(api);
    }

    @Override
    public synchronized ModelApi add(ModelApi modelApi) {
        if (modelApi == null) {
            throw new ValidationException("modelApi is required.");
        }
        modelApi.applyDefaults();
        modelApi.validate();

        if (store.containsKey(key(modelApi.getGwInstanceId(), modelApi.getApiName()))) {
            throw new ResourceConflictException(
                "Model API with the same name already exists: " + modelApi.getApiName());
        }
        checkBasePathConflicts(modelApi);

        Instant now = Instant.now();
        ModelApi saved = copy(modelApi);
        saved.setApiId(generateApiId());
        saved.setPublishStatus(STATUS_DRAFT);
        saved.setPublishFailureReason(null);
        saved.setVersion(0L);
        saved.setGmtCreate(now);
        saved.setGmtModified(now);
        store.put(key(saved.getGwInstanceId(), saved.getApiName()), saved);
        return copy(saved);
    }

    @Override
    public synchronized ModelApi update(ModelApi modelApi) {
        if (modelApi == null || StringUtils.isEmpty(modelApi.getApiName())) {
            throw new ValidationException("apiName is required.");
        }
        modelApi.applyDefaults();
        ModelApi existing = store.get(key(modelApi.getGwInstanceId(), modelApi.getApiName()));
        if (existing == null) {
            throw new com.alibaba.higress.sdk.exception.NotFoundException("Model API not found: " + modelApi.getApiName());
        }
        if (modelApi.getVersion() == null || !modelApi.getVersion().equals(existing.getVersion())) {
            throw new ResourceConflictException(
                "Version conflict. The Model API has been modified by others. Please refresh and retry.");
        }
        modelApi.validate();
        if (!StringUtils.equals(existing.getGwInstanceId(), modelApi.getGwInstanceId())) {
            throw new ValidationException("gwInstanceId is immutable.");
        }
        checkBasePathConflicts(modelApi, existing.getApiName());

        // Only the config columns are written on the user editing chain. Publish columns stay untouched.
        ModelApi updated = copy(existing);
        updated.setProtocol(modelApi.getProtocol());
        updated.setSceneType(modelApi.getSceneType());
        updated.setDescription(modelApi.getDescription());
        updated.setBasePath(modelApi.getBasePath());
        updated.setBasePathRemove(modelApi.getBasePathRemove());
        updated.setFirstByteTimeout(modelApi.getFirstByteTimeout());
        updated.setMethodPathList(modelApi.getMethodPathList());
        updated.setDomainNameList(modelApi.getDomainNameList());
        updated.setRouteDispatcher(modelApi.getRouteDispatcher());
        updated.setUpstreamAffinityConfig(modelApi.getUpstreamAffinityConfig());
        updated.setObservationConfig(modelApi.getObservationConfig());
        updated.setFallback(modelApi.getFallback());
        updated.setAuthenticationConfig(modelApi.getAuthenticationConfig());
        updated.setPolicyConfig(modelApi.getPolicyConfig());
        updated.setVersion(existing.getVersion() + 1);
        updated.setGmtModified(Instant.now());
        store.put(key(updated.getGwInstanceId(), updated.getApiName()), copy(updated));
        return copy(updated);
    }

    @Override
    public synchronized void delete(String apiName, String gwInstanceId, Long version) {
        ModelApi existing = query(apiName, gwInstanceId);
        if (version == null || !version.equals(existing.getVersion())) {
            throw new ResourceConflictException("Version conflict. Please refresh and retry.");
        }
        store.remove(key(gwInstanceId, apiName));
    }

    private static String key(String gwInstanceId, String apiName) {
        return StringUtils.defaultIfBlank(gwInstanceId, "default") + "\u0000" + apiName;
    }

    private void checkBasePathConflicts(ModelApi candidate) {
        checkBasePathConflicts(candidate, null);
    }

    private void checkBasePathConflicts(ModelApi candidate, String excludedApiName) {
        String candidateBase = normalizeBasePath(candidate.getBasePath());
        String candidateDomain = candidate.getDomainNameList() == null || candidate.getDomainNameList().isEmpty()
            ? "" : candidate.getDomainNameList().get(0);
        for (ModelApi existing : store.values()) {
            if (!candidate.getGwInstanceId().equals(existing.getGwInstanceId())) {
                continue;
            }
            if (excludedApiName != null && existing.getApiName().equals(excludedApiName)) {
                continue;
            }
            String existingDomain = existing.getDomainNameList() == null || existing.getDomainNameList().isEmpty()
                ? "" : existing.getDomainNameList().get(0);
            if (!candidateDomain.isEmpty() && !existingDomain.isEmpty() && !candidateDomain.equalsIgnoreCase(existingDomain)) {
                continue;
            }
            String existingBase = normalizeBasePath(existing.getBasePath());
            if (candidateBase.equals(existingBase) || "/".equals(candidateBase) || "/".equals(existingBase)
                || candidateBase.startsWith(existingBase + "/") || existingBase.startsWith(candidateBase + "/")) {
                throw new ValidationException(String.format(
                    "Base path conflicts with Model API %s (%s). One domain cannot host overlapping base paths.",
                    existing.getApiName(), existingBase));
            }
        }
    }

    private String normalizeBasePath(String basePath) {
        String normalized = StringUtils.defaultString(basePath);
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String generateApiId() {
        StringBuilder sb = new StringBuilder(API_ID_PREFIX.length() + API_ID_RANDOM_LENGTH);
        sb.append(API_ID_PREFIX);
        for (int i = 0; i < API_ID_RANDOM_LENGTH; i++) {
            sb.append(API_ID_ALPHABET.charAt(random.nextInt(API_ID_ALPHABET.length())));
        }
        return sb.toString();
    }

    /** Return a detached snapshot, including nested JSON configuration. */
    private static ModelApi copy(ModelApi source) {
        return COPY_MAPPER.convertValue(source, ModelApi.class);
    }

    private void registerDemoData() {
        Instant now = Instant.now();
        Instant oneHourAgo = now.minusSeconds(3600);
        Instant oneDayAgo = now.minusSeconds(86400);

        addDemo(ModelApi.builder()
            .apiName("qwen-chat")
            .gwInstanceId("default")
            .protocol("OPENAI")
            .sceneType("TEXT_GENERATION")
            .description("Qwen OpenAI-compatible chat completions endpoint")
            .basePath("/api/qwen/")
            .basePathRemove(true)
            .firstByteTimeout(300000)
            .methodPathList(Arrays.asList(
                MethodPath.builder().method("POST").path("/v1/chat/completions").build(),
                MethodPath.builder().method("POST").path("/v1/completions").build()))
            .domainNameList(Arrays.asList("ai.example.com"))
            .routeDispatcher(RouteDispatcher.builder()
                .strategy(RouteDispatcher.STRATEGY_WEIGHT)
                .targets(Arrays.asList(
                    RouteDispatcher.DispatcherTarget.builder().provider("qwen-main").weight(700).targetModel("qwen-plus").build(),
                    RouteDispatcher.DispatcherTarget.builder().provider("qwen-backup").weight(300).targetModel("qwen-plus-latest").build()))
                .build())
            .publishStatus(STATUS_DRAFT)
            .version(0L)
            .gmtCreate(oneHourAgo)
            .gmtModified(oneHourAgo)
            .build());

        addDemo(ModelApi.builder()
            .apiName("gpt-4o-unified")
            .gwInstanceId("default")
            .protocol("OPENAI")
            .sceneType("TEXT_GENERATION")
            .description("Unified OpenAI-compatible endpoint backed by weighted providers")
            .basePath("/api/openai/")
            .basePathRemove(false)
            .methodPathList(Arrays.asList(
                MethodPath.builder().method("POST").path("/v1/chat/completions").build()))
            .routeDispatcher(RouteDispatcher.builder()
                .strategy(RouteDispatcher.STRATEGY_HEADER)
                .targets(Arrays.asList(
                    RouteDispatcher.DispatcherTarget.builder().provider("openai-main").modelGlob("gpt-4o*").targetModel("gpt-4o").build(),
                    RouteDispatcher.DispatcherTarget.builder().provider("azure-openai").modelGlob("gpt-4o-mini*").targetModel("gpt-4o-mini").build()))
                .build())
            .publishStatus(STATUS_PUBLISHED)
            .version(3L)
            .gmtCreate(oneDayAgo)
            .gmtModified(oneHourAgo)
            .build());

        addDemo(ModelApi.builder()
            .apiName("claude-proxy")
            .gwInstanceId("default")
            .protocol("ANTHROPIC")
            .sceneType("TEXT_GENERATION")
            .description("Anthropic-compatible proxy, last publish failed on provider endpoint check")
            .basePath("/api/claude/")
            .basePathRemove(true)
            .firstByteTimeout(600000)
            .methodPathList(Arrays.asList(
                MethodPath.builder().method("POST").path("/v1/messages").build()))
            .routeDispatcher(RouteDispatcher.builder()
                .strategy(RouteDispatcher.STRATEGY_SINGLE)
                .targets(Arrays.asList(
                    RouteDispatcher.DispatcherTarget.builder().provider("claude-main").targetModel("claude-3-5-sonnet").build()))
                .build())
            .publishStatus(STATUS_FAILED)
            .publishFailureReason("apply Ingress failed: provider claude-main has no valid endpoint (demo failure)")
            .version(2L)
            .gmtCreate(oneDayAgo)
            .gmtModified(oneDayAgo)
            .build());

        addDemo(ModelApi.builder()
            .apiName("text-embedding")
            .gwInstanceId("default")
            .protocol("OPENAI")
            .sceneType("EMBEDDING")
            .description("Embedding endpoint over the OpenAI-compatible protocol")
            .basePath("/api/embedding/")
            .basePathRemove(false)
            .methodPathList(Arrays.asList(
                MethodPath.builder().method("POST").path("/v1/embeddings").build()))
            .routeDispatcher(RouteDispatcher.builder()
                .strategy(RouteDispatcher.STRATEGY_SINGLE)
                .targets(Arrays.asList(
                    RouteDispatcher.DispatcherTarget.builder().provider("text-embedding-vendor").build()))
                .build())
            .publishStatus(STATUS_DRAFT)
            .version(0L)
            .gmtCreate(oneHourAgo)
            .gmtModified(oneHourAgo)
            .build());
    }

    private void addDemo(ModelApi demo) {
        if (demo.getApiId() == null) {
            demo.setApiId(generateApiId());
        }
        store.put(key(demo.getGwInstanceId(), demo.getApiName()), demo);
    }
}
