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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Arrays;
import java.util.UUID;

import com.alibaba.higress.console.config.ModelApiPersistenceConfig;
import com.alibaba.higress.console.model.modelapi.MethodPath;
import com.alibaba.higress.console.model.modelapi.ModelApi;
import com.alibaba.higress.console.model.modelapi.RouteDispatcher;
import com.alibaba.higress.sdk.exception.ResourceConflictException;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL integration, with an isolated schema removed on completion. @author Codex */
class PostgresModelApiServiceTest {
    @Test
    void migrationSnapshotsVersionsAndSoftDelete() throws Exception {
        String url = System.getenv("MODEL_API_TEST_JDBC_URL");
        Assumptions.assumeTrue(url != null, "Set MODEL_API_TEST_JDBC_URL to run PostgreSQL integration");
        String user = System.getenv("MODEL_API_TEST_PG_USER");
        String password = System.getenv("MODEL_API_TEST_PG_PASSWORD");
        String schema = "model_api_java_it_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection base = DriverManager.getConnection(url,user,password); Statement sql = base.createStatement()) {
            sql.execute("CREATE SCHEMA " + schema);
            try {
                ModelApiPersistenceConfig wiring = new ModelApiPersistenceConfig();
                try (HikariDataSource data = wiring.modelApiDataSource(url + (url.contains("?") ? "&" : "?")
                    + "currentSchema=" + schema,user,password,true)) {
                    JdbcTemplate jdbc = wiring.modelApiJdbcTemplate(data);
                    PostgresModelApiService service = new PostgresModelApiService(jdbc,wiring.modelApiTransactions(data));
                    ModelApi created = service.add(definition());
                    assertEquals(1L,created.getVersion());
                    assertEquals(1L,created.getConfigVersion());
                    assertEquals(0L,created.getAppliedVersion());
                    assertEquals("PENDING",created.getPublishStatus());
                    assertFalse(jdbc.queryForObject("SELECT config ? 'publishStatus' FROM model_api",Boolean.class));
                    assertThrows(ResourceConflictException.class, () -> service.add(definition()));

                    try (Connection connection = data.getConnection(); Statement publisher = connection.createStatement()) {
                        connection.setAutoCommit(false);
                        publisher.execute("SET LOCAL model_api.actor='reconciler'");
                        publisher.execute("UPDATE model_api SET publish_status='SUCCESS',applied_version=config_version,last_reconcile_at=now()");
                        connection.commit();
                    }
                    ModelApi edit = service.query(created.getApiName(),"default");
                    edit.setDescription("new snapshot");
                    edit.setAppliedVersion(999L);
                    edit.setPublishStatus("SUCCESS");
                    ModelApi updated = service.update(edit);
                    assertEquals(2L,updated.getVersion());
                    assertEquals(1L,updated.getAppliedVersion());
                    assertEquals("PENDING",updated.getPublishStatus());
                    assertEquals("new snapshot",updated.getDescription());
                    assertThrows(ResourceConflictException.class, () -> service.update(edit));
                    assertThrows(ResourceConflictException.class, () -> service.delete(created.getApiName(),"default",1L));
                    service.delete(created.getApiName(),"default",2L);
                    assertTrue(service.list(null).getData().isEmpty());
                    assertEquals(3L,jdbc.queryForObject("SELECT config_version FROM model_api",Long.class));
                    assertTrue(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM model_api",Boolean.class));
                    // Name reuse is safe because each incarnation has a different resource identity.
                    assertNotEquals(created.getApiId(),service.add(definition()).getApiId());
                }
            } finally {
                sql.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private ModelApi definition() {
        return ModelApi.builder().apiName("java-test").protocol("OPENAI").sceneType("TEXT_GENERATION")
            .basePath("/java-test/")
            .methodPathList(Arrays.asList(MethodPath.builder().method("POST").path("/chat").build()))
            .routeDispatcher(RouteDispatcher.builder().strategy("SINGLE_MODEL_MAPPING")
                .targets(Arrays.asList(RouteDispatcher.DispatcherTarget.builder().provider("test").build())).build()).build();
    }
}
