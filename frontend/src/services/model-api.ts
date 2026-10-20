import request from './request';
import { ModelApi } from '@/interfaces/model-api';

export const getModelApis = (): Promise<ModelApi[]> => {
  return request.get<any, ModelApi[]>('/v1/ai/model-apis');
};

export const getModelApi = (name: string): Promise<ModelApi> => {
  return request.get<any, ModelApi>(`/v1/ai/model-apis/${name}`);
};

export const addModelApi = (payload: ModelApi): Promise<ModelApi> => {
  return request.post<any, ModelApi>('/v1/ai/model-apis', payload);
};

export const updateModelApi = (payload: ModelApi): Promise<ModelApi> => {
  return request.put<any, ModelApi>(`/v1/ai/model-apis/${payload.apiName}`, payload);
};

export const deleteModelApi = (modelApi: ModelApi): Promise<any> => {
  return request.delete<any, any>(`/v1/ai/model-apis/${encodeURIComponent(modelApi.apiName)}`, {
    params: { gwInstanceId: modelApi.gwInstanceId, version: modelApi.version },
  });
};


export const getModelApiCapabilities = (): Promise<{ storage: string }> => {
  return request.get<any, { storage: string }>('/v1/ai/model-apis/_capabilities');
};
