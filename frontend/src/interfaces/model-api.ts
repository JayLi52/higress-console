export interface MethodPath {
  method: string;
  path: string;
}

export interface DispatcherTarget {
  provider: string;
  weight?: number;
  modelGlob?: string;
  targetModel?: string;
  contextLowerBound?: number;
  contextUpperBound?: number;
}

export type DispatcherStrategy =
  | 'HEADER'
  | 'WEIGHT_MODEL_MAPPING'
  | 'CONTEXT_LENGTH'
  | 'SINGLE_MODEL_MAPPING';

export interface RouteDispatcher {
  strategy: DispatcherStrategy;
  targets: DispatcherTarget[];
}

export const MODEL_API_STATUS = {
  PENDING: 'PENDING',
  SYNCING: 'SYNCING',
  SUCCESS: 'SUCCESS',
  DRAFT: 'DRAFT',
  PUBLISHING: 'PUBLISHING',
  PUBLISHED: 'PUBLISHED',
  FAILED: 'FAILED',
} as const;

export type ModelApiStatus = typeof MODEL_API_STATUS[keyof typeof MODEL_API_STATUS];

export const SCENE_TYPES = [
  'TEXT_GENERATION',
  'IMAGE_GENERATION',
  'VIDEO_GENERATION',
  'SPEECH_RECOGNITION',
  'SPEECH_SYNTHESIS',
  'RERANK',
  'EMBEDDING',
  'OTHER',
] as const;

export const PROTOCOLS = [
  'OPENAI',
  'ANTHROPIC',
  'BAILIAN',
  'VLLM',
  'DOUBAO',
  'COMFYUI',
  'HTTP_CUSTOM',
  'WS_CUSTOM',
] as const;

export const HTTP_METHODS = ['GET', 'POST', 'PUT', 'DELETE', 'PATCH', 'HEAD', 'OPTIONS', 'MULTI'] as const;

export interface ModelApi {
  apiId?: string;
  apiName: string;
  gwInstanceId?: string;
  protocol: string;
  sceneType: string;
  description?: string;
  basePath: string;
  basePathRemove?: boolean;
  firstByteTimeout?: number;
  methodPathList: MethodPath[];
  domainNameList?: string[];
  routeDispatcher: RouteDispatcher;
  upstreamAffinityConfig?: Record<string, any>;
  observationConfig?: Record<string, any>;
  fallback?: Record<string, any>;
  authenticationConfig?: Record<string, any>;
  policyConfig?: Record<string, any>;
  publishStatus?: ModelApiStatus;
  publishFailureReason?: string;
  version?: number;
  configVersion?: number;
  appliedVersion?: number;
  lastReconcileAt?: string;
  lastError?: string;
  gmtCreate?: string;
  gmtModified?: string;
}
