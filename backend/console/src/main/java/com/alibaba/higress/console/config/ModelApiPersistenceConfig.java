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
package com.alibaba.higress.console.config;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StreamUtils;

/** PostgreSQL persistence and transactionally serialized feature migration. @author Codex */
@Configuration
@ConditionalOnProperty(name = "model-api.storage", havingValue = "postgres")
public class ModelApiPersistenceConfig {
    @Bean(destroyMethod = "close")
    public HikariDataSource modelApiDataSource(@Value("${spring.datasource.url}") String url,
        @Value("${spring.datasource.username}") String username,
        @Value("${spring.datasource.password}") String password,
        @Value("${model-api.migrate:true}") boolean migrate) throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(8);
        config.setConnectionTimeout(10000);
        config.setPoolName("model-api");
        HikariDataSource dataSource = new HikariDataSource(config);
        try {
            if (migrate) {
                try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
                    connection.setAutoCommit(false);
                    sql.execute("SELECT pg_advisory_xact_lock(hashtextextended('model-api-schema', 0))");
                    String migration;
                    try (java.io.InputStream input = new ClassPathResource("modelapi-db/V2__reconcile.sql").getInputStream()) {
                        migration = StreamUtils.copyToString(input, StandardCharsets.UTF_8);
                    }
                    sql.execute(migration);
                    connection.commit();
                }
            }
            return dataSource;
        } catch (Exception error) {
            dataSource.close();
            throw error;
        }
    }

    @Bean
    public JdbcTemplate modelApiJdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    public TransactionTemplate modelApiTransactions(DataSource dataSource) {
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }
}
