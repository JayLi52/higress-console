import { LlmProvider } from '@/interfaces/llm-provider';
import { ModelApi, MethodPath, DispatcherTarget, DispatcherStrategy,
  HTTP_METHODS,
  MODEL_API_STATUS,
  PROTOCOLS,
  SCENE_TYPES,
} from '@/interfaces/model-api';
import { getLlmProviders } from '@/services/llm-provider';
import { MinusCircleOutlined, PlusOutlined } from '@ant-design/icons';
import { useRequest } from 'ahooks';
import { AutoComplete, Button, Collapse, Form, Input, InputNumber, Select, Space, Switch, Tooltip } from 'antd';
import React, { forwardRef, useEffect, useImperativeHandle, useState } from 'react';
import { useTranslation } from 'react-i18next';

const { Option } = Select;

const DISPATCHER_STRATEGIES: Array<{ value: DispatcherStrategy; labelKey: string }> = [
  { value: 'HEADER', labelKey: 'modelApi.dispatcherStrategies.HEADER' },
  { value: 'WEIGHT_MODEL_MAPPING', labelKey: 'modelApi.dispatcherStrategies.WEIGHT_MODEL_MAPPING' },
  { value: 'SINGLE_MODEL_MAPPING', labelKey: 'modelApi.dispatcherStrategies.SINGLE_MODEL_MAPPING' },
];

const JSON_CONFIG_FIELDS = [
  'upstreamAffinityConfig', 'observationConfig', 'fallback', 'authenticationConfig', 'policyConfig',
] as const;

interface FormRef {
  reset: () => void;
  handleSubmit: () => Promise<ModelApi | null>;
}

const ModelApiForm = forwardRef<FormRef, { value: ModelApi | null }>((props, ref) => {
  const { t: translate } = useTranslation();
  const t = (key: string): string => translate(key) || '';
  const { value } = props;
  const [form] = Form.useForm();
  const [llmList, setLlmList] = useState<LlmProvider[]>([]);

  useRequest(getLlmProviders, {
    manual: false,
    onSuccess: (result) => {
      const providers = ((result || []) as LlmProvider[]).slice().sort((a, b) => a.name.localeCompare(b.name));
      setLlmList(providers);
    },
  });

  const strategy = Form.useWatch('routeDispatcher_strategy', form);
  const isEditing = !!value;

  useEffect(() => {
    form.resetFields();
    if (!value) return;
    const {
      apiName,
      description,
      protocol,
      sceneType,
      basePath,
      basePathRemove,
      firstByteTimeout,
      methodPathList,
      domainNameList,
      routeDispatcher,
    } = value;
    form.setFieldsValue({
      ...Object.fromEntries(JSON_CONFIG_FIELDS.map(key => [
        `${key}Json`, value[key] == null ? '' : JSON.stringify(value[key], null, 2),
      ])),
      apiName,
      description,
      protocol,
      sceneType,
      basePath,
      basePathRemove: !!basePathRemove,
      firstByteTimeout,
      methodPathList: (methodPathList || []).map((mp: MethodPath) => ({ ...mp })),
      domainName: domainNameList && domainNameList.length ? domainNameList[0] : undefined,
      routeDispatcher_strategy: routeDispatcher?.strategy,
      routeDispatcher_targets: (routeDispatcher?.targets || []).map((tg: DispatcherTarget) => ({ ...tg })),
    });
  }, [value]);

  useImperativeHandle(ref, () => ({
    reset: () => {
      form.resetFields();
    },
    handleSubmit: async (): Promise<ModelApi | null> => {
      const values = await form.validateFields();
      const payload: ModelApi = {
        apiName: values.apiName,
        protocol: values.protocol,
        sceneType: values.sceneType,
        description: values.description,
        basePath: values.basePath,
        basePathRemove: !!values.basePathRemove,
        firstByteTimeout: values.firstByteTimeout,
        methodPathList: values.methodPathList,
        domainNameList: values.domainName ? [values.domainName] : [],
        routeDispatcher: {
          strategy: values.routeDispatcher_strategy,
          targets: values.routeDispatcher_targets,
        },
      };
      JSON_CONFIG_FIELDS.forEach((key) => {
        const raw = values[`${key}Json`];
        payload[key] = raw?.trim() ? JSON.parse(raw) : undefined;
      });
      if (value) {
        // The optimistic lock version and identity columns are carried through on the editing chain.
        payload.apiId = value.apiId;
        payload.gwInstanceId = value.gwInstanceId;
        payload.version = value.version;
      }
      return payload;
    },
  }));

  const providerOptions = llmList.map((p) => ({ value: p.name, label: p.name }));

  return (
    <Form form={form} layout="vertical">
      <Form.Item
        label={t('modelApi.form.apiName')}
        name="apiName"
        required
        extra={t('modelApi.form.apiNameExtra')}
        rules={[
          { required: true, message: t('modelApi.form.apiNameRequired') },
          {
            pattern: /^(?!-)[a-z0-9-]{1,49}[a-z0-9]$/,
            message: t('modelApi.form.apiNamePattern'),
          },
        ]}
      >
        <Input placeholder="qwen-chat" disabled={isEditing} />
      </Form.Item>
      <Form.Item label={t('modelApi.form.description')} name="description">
        <Input.TextArea rows={2} placeholder={t('modelApi.form.descriptionPlaceholder') || ''} />
      </Form.Item>
      <Space size="middle" style={{ display: 'flex' }}>
        <Form.Item
          label={t('modelApi.form.sceneType')}
          name="sceneType"
          required
          rules={[{ required: true, message: t('modelApi.form.sceneTypeRequired') }]}
          style={{ flex: 1 }}
        >
          <Select placeholder={t('modelApi.form.sceneTypePlaceholder')}>
            {SCENE_TYPES.map((s) => <Option key={s} value={s}>{t(`modelApi.sceneTypes.${s}`)}</Option>)}
          </Select>
        </Form.Item>
        <Form.Item
          label={t('modelApi.form.protocol')}
          name="protocol"
          required
          rules={[{ required: true, message: t('modelApi.form.protocolRequired') }]}
          style={{ flex: 1 }}
        >
          <Select placeholder={t('modelApi.form.protocolPlaceholder')}>
            {PROTOCOLS.map((p) => <Option key={p} value={p}>{t(`modelApi.protocols.${p}`)}</Option>)}
          </Select>
        </Form.Item>
      </Space>
      <Form.Item
        label={t('modelApi.form.basePath')}
        name="basePath"
        required
        extra={t('modelApi.form.basePathExtra')}
        rules={[
          { required: true, message: t('modelApi.form.basePathRequired') },
          { pattern: /^\/[^?#\s]*$/, message: t('modelApi.form.basePathPattern') },
        ]}
      >
        <Input placeholder="/api/qwen/" />
      </Form.Item>
      <Space size="middle" style={{ display: 'flex' }}>
        <Form.Item
          label={t('modelApi.form.basePathRemove')}
          name="basePathRemove"
          valuePropName="checked"
          style={{ flex: 1 }}
          extra={t('modelApi.form.basePathRemoveExtra')}
        >
          <Switch />
        </Form.Item>
        <Form.Item
          label={t('modelApi.form.firstByteTimeout')}
          name="firstByteTimeout"
          style={{ flex: 1 }}
          extra={t('modelApi.form.firstByteTimeoutExtra')}
        >
          <InputNumber min={1} precision={0} style={{ width: '100%' }} placeholder="300000" />
        </Form.Item>
        <Form.Item
          label={t('modelApi.form.domain')}
          name="domainName"
          style={{ flex: 1 }}
          extra={t('modelApi.form.domainExtra')}
        >
          <Input placeholder="ai.example.com" />
        </Form.Item>
      </Space>

      <Form.Item label={t('modelApi.form.methodPathList')} required style={{ marginBottom: 0 }}>
        <Form.List name="methodPathList" initialValue={[{ method: 'POST', path: '' }]}>
          {(fields, { add, remove }) => (
            <>
              {fields.map((field) => (
                <Space key={field.key} align="baseline" style={{ display: 'flex', marginBottom: 8 }}>
                  <Form.Item
                    name={[field.name, 'method']}
                    rules={[{ required: true, message: t('modelApi.form.methodRequired') }]}
                    noStyle
                  >
                    <Select style={{ width: 110 }}>
                      {HTTP_METHODS.map((m) => <Option key={m} value={m}>{m === 'MULTI' ? t('modelApi.form.methodAny') : m}</Option>)}
                    </Select>
                  </Form.Item>
                  <Form.Item
                    name={[field.name, 'path']}
                    rules={[
                      { required: true, message: t('modelApi.form.pathRequired') },
                      { pattern: /^\/[^?#\s]*$/, message: t('modelApi.form.pathPattern') },
                    ]}
                    noStyle
                  >
                    <Input style={{ width: 340 }} placeholder="/v1/chat/completions" />
                  </Form.Item>
                  {fields.length > 1 && (
                    <MinusCircleOutlined onClick={() => remove(field.name)} />
                  )}
                </Space>
              ))}
              <Button type="dashed" onClick={() => add({ method: 'POST', path: '' })} block icon={<PlusOutlined />}>
                {t('modelApi.form.addMethodPath')}
              </Button>
            </>
          )}
        </Form.List>
      </Form.Item>

      <Form.Item
        label={t('modelApi.form.dispatcherStrategy')}
        name="routeDispatcher_strategy"
        required
        rules={[{ required: true, message: t('modelApi.form.dispatcherStrategyRequired') }]}
        extra={t('modelApi.form.dispatcherStrategyExtra')}
      >
        <Select>
          {DISPATCHER_STRATEGIES.map((s) => (
            <Option key={s.value} value={s.value}>{t(s.labelKey)}</Option>
          ))}
        </Select>
      </Form.Item>

      <Form.Item label={t('modelApi.form.dispatcherTargets')} required style={{ marginBottom: 0 }}>
        <Form.List name="routeDispatcher_targets" initialValue={[{}]}>
          {(fields, { add, remove }) => (
            <>
              {fields.map((field) => (
                <Space key={field.key} align="baseline" style={{ display: 'flex', marginBottom: 8, flexWrap: 'wrap' }}>
                  <Form.Item
                    name={[field.name, 'provider']}
                    rules={[{ required: true, message: t('modelApi.form.providerRequired') }]}
                    noStyle
                  >
                    <AutoComplete
                      style={{ width: 180 }}
                      options={providerOptions}
                      placeholder={t('modelApi.form.providerPlaceholder')}
                      filterOption={(input, option) =>
                        (option?.value || '').toLowerCase().includes(input.toLowerCase())}
                    />
                  </Form.Item>
                  {strategy === 'WEIGHT_MODEL_MAPPING' && (
                    <Tooltip title={t('modelApi.form.weightExtra')}>
                      <Form.Item
                        name={[field.name, 'weight']}
                        rules={[{ required: true, message: t('modelApi.form.weightRequired') }]}
                        noStyle
                      >
                        <InputNumber min={1} max={1000} precision={0} style={{ width: 100 }} placeholder="1-1000" />
                      </Form.Item>
                    </Tooltip>
                  )}
                  {strategy === 'HEADER' && (
                    <Form.Item name={[field.name, 'modelGlob']} noStyle>
                      <Input style={{ width: 140 }} placeholder="qwen-*" />
                    </Form.Item>
                  )}
                  <Form.Item name={[field.name, 'targetModel']} noStyle>
                    <Input style={{ width: 150 }} placeholder={t('modelApi.form.targetModelPlaceholder')} />
                  </Form.Item>
                  {fields.length > 1 && (
                    <MinusCircleOutlined onClick={() => remove(field.name)} />
                  )}
                </Space>
              ))}
              {strategy !== 'SINGLE_MODEL_MAPPING' && (
                <Button type="dashed" onClick={() => add({})} block icon={<PlusOutlined />}>
                  {t('modelApi.form.addTarget')}
                </Button>
              )}
            </>
          )}
        </Form.List>
      </Form.Item>

      <Collapse style={{ marginTop: 24, marginBottom: 24 }}>
        <Collapse.Panel forceRender header={t('modelApi.form.advancedConfig')} key="advanced">
          {JSON_CONFIG_FIELDS.map(key => (
            <Form.Item
              key={key}
              name={`${key}Json`}
              label={t(`modelApi.form.${key}`)}
              rules={[{ validator: async (_, raw) => {
                if (!raw?.trim()) return;
                try {
                  const parsed = JSON.parse(raw);
                  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error();
                } catch {
                  throw new Error(t('modelApi.form.jsonObjectRequired'));
                }
              } }]}
            >
              <Input.TextArea rows={4} placeholder="{}" />
            </Form.Item>
          ))}
        </Collapse.Panel>
      </Collapse>
      {isEditing && value?.publishStatus && (
        <Form.Item label={t('modelApi.form.currentStatus')}>
          <span className="model-api-form-status">{t(`modelApi.status.${value.publishStatus}`)}</span>
          {value.publishStatus === MODEL_API_STATUS.FAILED && value.publishFailureReason && (
            <div style={{ color: '#c22828' }}>{value.publishFailureReason}</div>
          )}
        </Form.Item>
      )}
    </Form>
  );
});

export default ModelApiForm;
