import { ModelApi, MODEL_API_STATUS } from '@/interfaces/model-api';
import { getModelApis, addModelApi, updateModelApi, deleteModelApi, getModelApiCapabilities } from '@/services/model-api';
import { ExclamationCircleOutlined, RedoOutlined, SearchOutlined } from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-layout';
import { useRequest } from 'ahooks';
import { Alert, Button, Col, Drawer, Form, Input, message, Modal, Row, Space, Table, Tag } from 'antd';
import React, { useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import ModelApiForm from './components/ModelApiForm';

interface FormRef {
  reset: () => void;
  handleSubmit: () => Promise<ModelApi | null>;
}

const STATUS_TAG_COLORS: Record<string, string> = {
  PENDING: 'warning',
  SYNCING: 'processing',
  SUCCESS: 'success',
  DRAFT: 'default',
  PUBLISHING: 'processing',
  PUBLISHED: 'success',
  FAILED: 'error',
};

const ModelApiList: React.FC = () => {
  const { t } = useTranslation();
  const { data: capabilities } = useRequest(getModelApiCapabilities);

  const formRef = useRef<FormRef>(null);
  const modelApisRef = useRef<ModelApi[] | null>(null);
  const [dataSource, setDataSource] = useState<ModelApi[]>([]);
  const [currentModelApi, setCurrentModelApi] = useState<ModelApi | null>(null);
  const [openDrawer, setOpenDrawer] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [openDeleteModal, setOpenDeleteModal] = useState(false);
  const [confirmLoading, setConfirmLoading] = useState(false);
  const [keyword, setKeyword] = useState('');

  const applyFilters = (keywordValue: string) => {
    let results = [...(modelApisRef.current || [])];
    if (keywordValue) {
      const q = keywordValue.toLowerCase();
      results = results.filter(item =>
        item.apiName?.toLowerCase().includes(q)
        || item.description?.toLowerCase().includes(q)
        || item.basePath?.toLowerCase().includes(q));
    }
    setDataSource(results);
  };

  const { loading, run, refresh } = useRequest(getModelApis, {
    manual: true,
    pollingInterval: 5000,
    pollingWhenHidden: false,
    onSuccess: (result) => {
      const modelApis = ((result || []) as ModelApi[]).map(item => ({ ...item, key: item.apiName }));
      modelApis.sort((a, b) => (b.gmtModified || '').localeCompare(a.gmtModified || ''));
      modelApisRef.current = modelApis;
      applyFilters(keyword);
    },
  });

  React.useEffect(() => {
    run();
  }, []);

  const onKeywordChange = (value: string) => {
    setKeyword(value);
    applyFilters(value);
  };

  const onShowDrawer = () => {
    setCurrentModelApi(null);
    setOpenDrawer(true);
  };

  const onEditDrawer = (modelApi: ModelApi) => {
    setCurrentModelApi(modelApi);
    setOpenDrawer(true);
  };

  const handleDrawerOk = async () => {
    setSubmitting(true);
    try {
      const values = formRef.current ? await formRef.current.handleSubmit() : null;
      if (!values) {
        return;
      }
      if (currentModelApi) {
        await updateModelApi(values);
        message.success(t('modelApi.messages.updated'));
      } else {
        await addModelApi(values);
        message.success(t('modelApi.messages.created'));
      }
      setOpenDrawer(false);
      formRef.current && formRef.current.reset();
      refresh();
    } catch (err) {
      // Error details are shown by the request layer.
    } finally {
      setSubmitting(false);
    }
  };

  const handleDrawerCancel = () => {
    setOpenDrawer(false);
    formRef.current && formRef.current.reset();
    setCurrentModelApi(null);
  };

  const onShowDeleteModal = (modelApi: ModelApi) => {
    setCurrentModelApi(modelApi);
    setOpenDeleteModal(true);
  };

  const handleDeleteOk = async () => {
    if (!currentModelApi) {
      return;
    }
    setConfirmLoading(true);
    try {
      await deleteModelApi(currentModelApi);
      message.success(t('modelApi.messages.deleted'));
      setOpenDeleteModal(false);
      setCurrentModelApi(null);
      refresh();
    } catch (err) {
      // The request layer displays the conflict or validation error.
    } finally {
      setConfirmLoading(false);
    }
  };

  const handleDeleteCancel = () => {
    setOpenDeleteModal(false);
    setCurrentModelApi(null);
  };

  const columns = [
    {
      title: t('modelApi.columns.apiName'),
      dataIndex: 'apiName',
      key: 'apiName',
      ellipsis: true,
      render: (value: string) => <span style={{ fontWeight: 500 }}>{value}</span>,
    },
    {
      title: t('modelApi.columns.sceneAndProtocol'),
      key: 'sceneAndProtocol',
      ellipsis: true,
      render: (_, record: ModelApi) => (
        <Space size={4}>
          <span>{t(`modelApi.sceneTypes.${record.sceneType}`)}</span>
          <span style={{ color: '#919191' }}>·</span>
          <span>{t(`modelApi.protocols.${record.protocol}`) || record.protocol}</span>
        </Space>
      ),
    },
    {
      title: t('modelApi.columns.basePath'),
      dataIndex: 'basePath',
      key: 'basePath',
      ellipsis: true,
      render: (value: string, record: ModelApi) => (
        <span>
          {record.domainNameList && record.domainNameList.length > 0 ? `${record.domainNameList[0]} ` : ''}
          {value}
        </span>
      ),
    },
    {
      title: t('modelApi.columns.routes'),
      dataIndex: 'methodPathList',
      key: 'methodPathList',
      ellipsis: true,
      render: (value: ModelApi['methodPathList']) => {
        if (!Array.isArray(value) || !value.length) {
          return '-';
        }
        return value.map((mp, i) => (
          <span key={i}>{!!i && <br />}{mp.method === 'MULTI' ? t('modelApi.form.methodAny') : mp.method} {mp.path}</span>
        ));
      },
    },
    {
      title: t('modelApi.columns.providers'),
      key: 'providers',
      ellipsis: true,
      render: (_, record: ModelApi) => {
        const targets = record.routeDispatcher?.targets || [];
        if (!targets.length) {
          return '-';
        }
        return targets.map((tg, i) => (
          <span key={i}>
            {!!i && <br />}
            {tg.provider}
            {tg.weight != null && record.routeDispatcher?.strategy === 'WEIGHT_MODEL_MAPPING' ? `: ${tg.weight}` : ''}
            {tg.targetModel ? ` → ${tg.targetModel}` : ''}
          </span>
        ));
      },
    },
    {
      title: t('modelApi.columns.publishStatus'),
      dataIndex: 'publishStatus',
      key: 'publishStatus',
      width: 110,
      render: (value: string, record: ModelApi) => (
        <div>
          <Tag color={STATUS_TAG_COLORS[value] || 'default'}>{t(`modelApi.status.${value}`) || value}</Tag>
          {value === MODEL_API_STATUS.FAILED && record.publishFailureReason && (
            <div style={{ fontSize: 12, color: '#c22828', maxWidth: 240 }} title={record.publishFailureReason}>
              {record.publishFailureReason}
            </div>
          )}
        </div>
      ),
    },
    {
      title: t('modelApi.columns.versions'),
      render: (_, record: ModelApi) => `${record.configVersion ?? record.version ?? '-'} / ${record.appliedVersion ?? '-'}`,
      dataIndex: 'version',
      key: 'version',
      width: 80,
    },
    {
      title: t('misc.actions'),
      dataIndex: 'action',
      key: 'action',
      width: 140,
      align: 'center' as const,
      render: (_, record: ModelApi) => (
        <Space size="small">
          <a onClick={() => onEditDrawer(record)}>{t('misc.edit')}</a>
          <a onClick={() => onShowDeleteModal(record)}>{t('misc.delete')}</a>
        </Space>
      ),
    },
  ];

  return (
    <PageContainer>
      <Alert
        type="info"
        showIcon
        message={t(capabilities?.storage === 'postgres' ? 'modelApi.reconcileNotice' : 'modelApi.demoNotice')}
        style={{ marginBottom: 16 }}
      />
      <Form
        style={{
          background: '#fff',
          padding: '16px 16px 24px',
          marginBottom: 16,
        }}
      >
        <Row gutter={24}>
          <Col span={8}>
            <Form.Item label={t('modelApi.search.keyword')}>
              <Input
                prefix={<SearchOutlined />}
                allowClear
                placeholder={t('modelApi.search.keywordPlaceholder') || ''}
                value={keyword}
                onChange={e => onKeywordChange(e.target.value)}
              />
            </Form.Item>
          </Col>
          <Col span={16} style={{ textAlign: 'right', paddingTop: 30 }}>
            <Button
              style={{ marginRight: 8 }}
              type="primary"
              onClick={onShowDrawer}
            >
              {t('modelApi.create')}
            </Button>
            <Button icon={<RedoOutlined />} onClick={refresh} />
          </Col>
        </Row>
      </Form>
      <Table
        loading={loading}
        dataSource={dataSource}
        columns={columns}
        pagination={{ defaultPageSize: 10, showSizeChanger: true }}
        rowKey="apiId"
        scroll={{ x: 1100 }}
      />
      <Drawer
        title={t(currentModelApi ? 'modelApi.edit' : 'modelApi.create')}
        placement="right"
        width={660}
        destroyOnClose
        onClose={handleDrawerCancel}
        open={openDrawer}
        extra={
          <Space>
            <Button onClick={handleDrawerCancel}>{t('misc.cancel')}</Button>
            <Button type="primary" onClick={handleDrawerOk} loading={submitting}>
              {t('misc.confirm')}
            </Button>
          </Space>
        }
      >
        <ModelApiForm ref={formRef} value={currentModelApi} />
      </Drawer>
      <Modal
        title={<div><ExclamationCircleOutlined style={{ color: '#ffde5c', marginRight: 8 }} />{t('misc.delete')}</div>}
        open={openDeleteModal}
        onOk={handleDeleteOk}
        confirmLoading={confirmLoading}
        onCancel={handleDeleteCancel}
        cancelText={t('misc.cancel')}
        okText={t('misc.confirm')}
      >
        <p>
          {t('modelApi.deleteConfirmationPrefix')}
          <span style={{ color: '#0070cc' }}>{currentModelApi?.apiName}</span>
          {t('modelApi.deleteConfirmationSuffix')}
        </p>
      </Modal>
    </PageContainer>
  );
};

export default ModelApiList;
