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
package com.alibaba.higress.console.controller.ai;

import com.alibaba.higress.console.aop.ApiStandardizationAspect;
import com.alibaba.higress.console.model.User;
import com.alibaba.higress.console.service.ModelApiServiceImpl;
import com.alibaba.higress.console.service.SessionService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ModelApisControllerTest {

    private MockMvc mvc;
    private SessionService sessions;

    private static final String ROOT = "/v1/ai/model-apis";
    private static final String PAYLOAD = "{\"apiName\":\"test-api\",\"protocol\":\"OPENAI\","
        + "\"sceneType\":\"TEXT_GENERATION\",\"basePath\":\"/test-api/\","
        + "\"methodPathList\":[{\"method\":\"POST\",\"path\":\"/v1/chat/completions\"}],"
        + "\"routeDispatcher\":{\"strategy\":\"SINGLE_MODEL_MAPPING\",\"targets\":[{\"provider\":\"qwen\"}]}}";

    @BeforeEach
    void setUp() {
        ModelApisController controller = new ModelApisController();
        controller.setModelApiService(new ModelApiServiceImpl());
        sessions = mock(SessionService.class);
        when(sessions.validateSession(any())).thenReturn(User.builder().name("test").build());
        ApiStandardizationAspect aspect = new ApiStandardizationAspect();
        aspect.setSessionService(sessions);
        AspectJProxyFactory proxy = new AspectJProxyFactory(controller);
        proxy.addAspect(aspect);
        mvc = MockMvcBuilders.standaloneSetup((ModelApisController)proxy.getProxy()).build();
    }

    @Test
    void httpCrudPreservesTheFrontendContractAndRejectsStaleWrites() throws Exception {
        mvc.perform(post(ROOT).contentType(MediaType.APPLICATION_JSON).content(PAYLOAD))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.version").value(0))
            .andExpect(jsonPath("$.publishStatus").value("DRAFT"));
        mvc.perform(get(ROOT)).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].apiName").value("test-api"));
        mvc.perform(get(ROOT + "/test-api")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.version").value(0));
        mvc.perform(post(ROOT).contentType(MediaType.APPLICATION_JSON).content(PAYLOAD))
            .andExpect(status().isConflict());
        String update = PAYLOAD.replace("\"apiName\"", "\"version\":0,\"apiName\"");
        mvc.perform(put(ROOT + "/test-api").contentType(MediaType.APPLICATION_JSON).content(update))
            .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        mvc.perform(put(ROOT + "/test-api").contentType(MediaType.APPLICATION_JSON).content(update))
            .andExpect(status().isConflict());
        mvc.perform(put(ROOT + "/renamed").contentType(MediaType.APPLICATION_JSON).content(update))
            .andExpect(status().isBadRequest());
        mvc.perform(delete(ROOT + "/test-api").param("version", "0")).andExpect(status().isConflict());
        mvc.perform(delete(ROOT + "/test-api").param("version", "1")).andExpect(status().isNoContent());
        mvc.perform(get(ROOT)).andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void newEndpointsRequireTheExistingSessionAuthentication() throws Exception {
        when(sessions.validateSession(any())).thenReturn(null);
        mvc.perform(get(ROOT)).andExpect(status().isUnauthorized());
        mvc.perform(post(ROOT).contentType(MediaType.APPLICATION_JSON).content(PAYLOAD))
            .andExpect(status().isUnauthorized());
    }
}
