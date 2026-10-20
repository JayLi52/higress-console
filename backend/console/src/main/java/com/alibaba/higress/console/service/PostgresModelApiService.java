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

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.security.SecureRandom;
import java.util.List;

import com.alibaba.higress.console.model.modelapi.ModelApi;
import com.alibaba.higress.sdk.exception.NotFoundException;
import com.alibaba.higress.sdk.exception.ResourceConflictException;
import com.alibaba.higress.sdk.exception.ValidationException;
import com.alibaba.higress.sdk.model.PaginatedResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Complete snapshots and optimistic writes; PostgreSQL emits notifications after commit. @author Codex */
@Service("ModelApiService")
@ConditionalOnProperty(name = "model-api.storage", havingValue = "postgres")
public class PostgresModelApiService implements ModelApiService {
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final SecureRandom random = new SecureRandom();

    public PostgresModelApiService(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public String storage() { return "postgres"; }

    @Override
    public PaginatedResult<ModelApi> list(ModelApi.PageQuery query) {
        String instance = query == null ? "default" : StringUtils.defaultIfBlank(query.getGwInstanceId(), "default");
        List<ModelApi> all = jdbc.query("SELECT * FROM model_api WHERE gw_instance_id=? AND deleted_at IS NULL "
            + "ORDER BY gmt_modified DESC,api_id", this::read, instance);
        if (query != null) all.removeIf(api -> !api.matchesQuery(query.getKeyword()));
        PaginatedResult<ModelApi> result = PaginatedResult.createFromFullList(all, null);
        if (query != null && query.paginationEnabled()) {
            int page = query.getPageNum() == null ? 1 : Math.max(1, query.getPageNum());
            int size = query.getPageSize() == null || query.getPageSize()<1 ? 10 : query.getPageSize();
            int start = (int)Math.min((long)(page-1)*size,all.size());
            result.setData(all.subList(start,(int)Math.min((long)start+size,all.size())));
            result.setPageNum(page);
            result.setPageSize(size);
        }
        return result;
    }

    @Override
    public ModelApi query(String name, String instance) {
        List<ModelApi> rows = jdbc.query("SELECT * FROM model_api WHERE gw_instance_id=? AND api_name=? AND deleted_at IS NULL",
            this::read, StringUtils.defaultIfBlank(instance,"default"),name);
        if (rows.isEmpty()) throw new NotFoundException("Model API not found: " + name);
        return rows.get(0);
    }

    @Override
    public ModelApi add(ModelApi api) {
        api.applyDefaults();
        api.validate();
        try {
            return transactions.execute(status -> {
                lockInstance(api.getGwInstanceId());
                checkPathConflicts(api);
                jdbc.update("INSERT INTO model_api(api_id,api_name,gw_instance_id,config) VALUES(?,?,?,?::jsonb)",
                    generateId(),api.getApiName(),api.getGwInstanceId(),config(api));
                return query(api.getApiName(),api.getGwInstanceId());
            });
        } catch (DuplicateKeyException conflict) {
            throw new ResourceConflictException("Model API name already exists in this instance.");
        }
    }

    @Override
    public ModelApi update(ModelApi api) {
        api.applyDefaults();
        api.validate();
        if (api.getVersion() == null) throw new ResourceConflictException("version is required.");
        return transactions.execute(status -> {
            lockInstance(api.getGwInstanceId());
            ModelApi current = query(api.getApiName(),api.getGwInstanceId());
            if (api.getApiId()!=null && !api.getApiId().equals(current.getApiId())) {
                throw new ValidationException("apiId and gateway instance are immutable.");
            }
            checkPathConflicts(api);
            int count = jdbc.update("UPDATE model_api SET config=?::jsonb,config_version=config_version+1,"
                + "publish_status='PENDING' WHERE api_id=? AND config_version=? AND deleted_at IS NULL",
                config(api),current.getApiId(),api.getVersion());
            if (count!=1) throw new ResourceConflictException("Version conflict. Refresh and retry.");
            return query(api.getApiName(),api.getGwInstanceId());
        });
    }

    @Override
    public void delete(String name, String instance, Long version) {
        transactions.execute(status -> {
            ModelApi current = query(name,instance);
            int count = jdbc.update("UPDATE model_api SET deleted_at=now(),config_version=config_version+1,"
                + "publish_status='PENDING' WHERE api_id=? AND config_version=? AND deleted_at IS NULL",
                current.getApiId(),version);
            if (count!=1) throw new ResourceConflictException("Version conflict. Refresh and retry.");
            return null;
        });
    }

    private void lockInstance(String instance) {
        // Serialize cross-API path checks across console replicas, inside the write transaction.
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>)connection -> {
            try (java.sql.PreparedStatement sql = connection.prepareStatement(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 1))")) {
                sql.setString(1,instance);
                sql.execute();
            }
            return null;
        });
    }

    private void checkPathConflicts(ModelApi api) {
        ModelApi.PageQuery query = new ModelApi.PageQuery();
        query.setGwInstanceId(api.getGwInstanceId());
        String path = normalize(api.getBasePath());
        String host = api.getDomainNameList()==null || api.getDomainNameList().isEmpty() ? "" : api.getDomainNameList().get(0);
        for (ModelApi other : list(query).getData()) {
            if (other.getApiName().equals(api.getApiName())) continue;
            String otherHost = other.getDomainNameList()==null || other.getDomainNameList().isEmpty()
                ? "" : other.getDomainNameList().get(0);
            if (!host.isEmpty() && !otherHost.isEmpty() && !host.equalsIgnoreCase(otherHost)) continue;
            String otherPath = normalize(other.getBasePath());
            if (path.equals(otherPath) || path.equals("/") || otherPath.equals("/")
                || path.startsWith(otherPath+"/") || otherPath.startsWith(path+"/")) {
                throw new ValidationException("Base path conflicts with Model API " + other.getApiName());
            }
        }
    }

    private static String normalize(String path) {
        return path.length()>1 ? path.replaceAll("/+$", "") : path;
    }

    private String generateId() {
        StringBuilder id = new StringBuilder("m-");
        for (int i=0;i<20;i++) id.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        return id.toString();
    }

    private static String config(ModelApi api) {
        ObjectNode value = JSON.valueToTree(api);
        value.remove(java.util.Arrays.asList("apiId","apiName","gwInstanceId","version","configVersion","appliedVersion",
            "publishStatus","publishFailureReason","gmtCreate","gmtModified","lastReconcileAt","lastError","deletedAt"));
        return value.toString();
    }

    private ModelApi read(ResultSet row, int index) throws SQLException {
        try {
            ModelApi api = JSON.readValue(row.getString("config"),ModelApi.class);
            api.setApiId(row.getString("api_id"));
            api.setApiName(row.getString("api_name"));
            api.setGwInstanceId(row.getString("gw_instance_id"));
            api.setVersion(row.getLong("config_version"));
            api.setConfigVersion(row.getLong("config_version"));
            api.setAppliedVersion(row.getLong("applied_version"));
            api.setPublishStatus(row.getString("publish_status"));
            api.setPublishFailureReason(row.getString("last_error"));
            api.setLastError(row.getString("last_error"));
            api.setGmtCreate(row.getTimestamp("gmt_create").toInstant());
            api.setGmtModified(row.getTimestamp("gmt_modified").toInstant());
            Timestamp checked = row.getTimestamp("last_reconcile_at");
            api.setLastReconcileAt(checked==null ? null : checked.toInstant());
            return api;
        } catch (java.io.IOException error) {
            throw new SQLException("Invalid stored model API config",error);
        }
    }
}
