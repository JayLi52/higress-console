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

import javax.annotation.Resource;
import javax.validation.constraints.NotBlank;

import org.springdoc.api.annotations.ParameterObject;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.alibaba.higress.console.controller.dto.PaginatedResponse;
import com.alibaba.higress.console.controller.dto.Response;
import com.alibaba.higress.console.controller.util.ControllerUtil;
import com.alibaba.higress.console.model.modelapi.ModelApi;
import com.alibaba.higress.console.service.ModelApiService;
import com.alibaba.higress.sdk.model.PaginatedResult;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController("ModelApisController")
@RequestMapping("/v1/ai/model-apis")
@Validated
@Tag(name = "Model API APIs")
public class ModelApisController {

    private ModelApiService modelApiService;

    @Resource
    public void setModelApiService(ModelApiService modelApiService) {
        this.modelApiService = modelApiService;
    }

    @GetMapping("/_capabilities")
    public ResponseEntity<Response<java.util.Map<String, String>>> capabilities() {
        return ControllerUtil.buildResponseEntity(java.util.Collections.singletonMap("storage", modelApiService.storage()));
    }

    @GetMapping
    @Operation(summary = "List Model APIs")
    @ApiResponses(value = {@ApiResponse(responseCode = "200", description = "Model APIs listed successfully"),
        @ApiResponse(responseCode = "500", description = "Internal server error")})
    public ResponseEntity<PaginatedResponse<ModelApi>> list(@ParameterObject ModelApi.PageQuery query) {
        PaginatedResult<ModelApi> modelApis = modelApiService.list(query);
        return ControllerUtil.buildResponseEntity(modelApis);
    }

    @PostMapping
    @Operation(summary = "Add a new Model API")
    @ApiResponses(value = {@ApiResponse(responseCode = "200", description = "Model API added successfully"),
        @ApiResponse(responseCode = "400", description = "Model API data is not valid"),
        @ApiResponse(responseCode = "409", description = "Model API already existed with the same name."),
        @ApiResponse(responseCode = "500", description = "Internal server error")})
    public ResponseEntity<Response<ModelApi>> add(@RequestBody ModelApi modelApi) {
        modelApi.validate();
        ModelApi newModelApi = modelApiService.add(modelApi);
        return ControllerUtil.buildResponseEntity(newModelApi);
    }

    @GetMapping(value = "/{name}")
    @Operation(summary = "Get a Model API by name")
    @ApiResponses(value = {@ApiResponse(responseCode = "200", description = "Model API found"),
        @ApiResponse(responseCode = "404", description = "Model API not found"),
        @ApiResponse(responseCode = "500", description = "Internal server error")})
    public ResponseEntity<Response<ModelApi>> query(@PathVariable("name") @NotBlank String name,
        @RequestParam(defaultValue = "default") String gwInstanceId) {
        ModelApi modelApi = modelApiService.query(name, gwInstanceId);
        return ControllerUtil.buildResponseEntity(modelApi);
    }

    @PutMapping("/{name}")
    @Operation(summary = "Update an existed Model API")
    @ApiResponses(value = {@ApiResponse(responseCode = "200", description = "Model API updated successfully"),
        @ApiResponse(responseCode = "400",
            description = "Model API data is not valid or the name in the URL doesn't match the one in the body."),
        @ApiResponse(responseCode = "409", description = "Version conflict: the object has been modified by others."),
        @ApiResponse(responseCode = "500", description = "Internal server error")})
    public ResponseEntity<Response<ModelApi>> put(@PathVariable("name") @NotBlank String name,
        @RequestBody ModelApi modelApi) {
        if (!name.equals(modelApi.getApiName())) {
            throw new com.alibaba.higress.sdk.exception.ValidationException(
                "Model API name in the URL doesn't match the one in the body. apiName is immutable.");
        }
        modelApi.validate();
        ModelApi updatedModelApi = modelApiService.update(modelApi);
        return ControllerUtil.buildResponseEntity(updatedModelApi);
    }

    @DeleteMapping("/{name}")
    @Operation(summary = "Delete a Model API")
    @ApiResponses(value = {@ApiResponse(responseCode = "204", description = "Model API deleted successfully"),
        @ApiResponse(responseCode = "500", description = "Internal server error")})
    public ResponseEntity<Response<ModelApi>> delete(@PathVariable("name") @NotBlank String name,
        @RequestParam(defaultValue = "default") String gwInstanceId, @RequestParam Long version) {
        modelApiService.delete(name, gwInstanceId, version);
        return ResponseEntity.noContent().build();
    }

}
