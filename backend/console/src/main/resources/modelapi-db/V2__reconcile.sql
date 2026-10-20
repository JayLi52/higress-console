-- One-time, transactional upgrade. Existing scalar columns remain as legacy history;
-- config JSONB is the only authoritative business configuration after this migration.
CREATE TABLE IF NOT EXISTS model_api_schema_history (version integer PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now());
DO $migration$
BEGIN
  IF EXISTS (SELECT 1 FROM model_api_schema_history WHERE version=2) THEN RETURN; END IF;
  CREATE TABLE IF NOT EXISTS model_api (
    api_id varchar(22) PRIMARY KEY, api_name varchar(63) NOT NULL, gw_instance_id varchar(63) NOT NULL,
    publish_status varchar(32) NOT NULL DEFAULT 'PENDING',
    gmt_create timestamptz NOT NULL DEFAULT now(), gmt_modified timestamptz NOT NULL DEFAULT now()
  );
  DROP TRIGGER IF EXISTS trg_model_api_publish_guard ON model_api;
  DROP TRIGGER IF EXISTS trg_model_api_touch ON model_api;
  ALTER TABLE model_api ADD COLUMN IF NOT EXISTS config jsonb;
  ALTER TABLE model_api ADD COLUMN IF NOT EXISTS config_version bigint NOT NULL DEFAULT 1;
  ALTER TABLE model_api ADD COLUMN IF NOT EXISTS applied_version bigint NOT NULL DEFAULT 0;
  ALTER TABLE model_api ADD COLUMN IF NOT EXISTS last_reconcile_at timestamptz;
  ALTER TABLE model_api ADD COLUMN IF NOT EXISTS last_error text;
  ALTER TABLE model_api ADD COLUMN IF NOT EXISTS deleted_at timestamptz;
  ALTER TABLE model_api ADD COLUMN IF NOT EXISTS purged_at timestamptz;
  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=current_schema()
             AND table_name='model_api' AND column_name='base_path') THEN
    ALTER TABLE model_api ALTER COLUMN base_path DROP NOT NULL;
  END IF;
  UPDATE model_api m SET config=jsonb_strip_nulls(jsonb_build_object(
    'protocol',to_jsonb(m)->'protocol','sceneType',to_jsonb(m)->'scene_type',
    'description',to_jsonb(m)->'description','basePath',to_jsonb(m)->'base_path',
    'basePathRemove',to_jsonb(m)->'base_path_remove','firstByteTimeout',to_jsonb(m)->'first_byte_timeout',
    'methodPathList',to_jsonb(m)->'method_path_list','domainNameList',to_jsonb(m)->'domain_name_list',
    'routeDispatcher',to_jsonb(m)->'route_dispatcher','upstreamAffinityConfig',to_jsonb(m)->'upstream_affinity_config',
    'observationConfig',to_jsonb(m)->'observation_config','fallback',to_jsonb(m)->'fallback',
    'authenticationConfig',to_jsonb(m)->'authentication_config','policyConfig',to_jsonb(m)->'policy_config'
  )), config_version=greatest(1, coalesce((to_jsonb(m)->>'version')::bigint,0)+1),
       applied_version=0,publish_status='PENDING' WHERE config IS NULL;
  ALTER TABLE model_api ALTER COLUMN config SET NOT NULL;
  ALTER TABLE model_api ALTER COLUMN publish_status SET DEFAULT 'PENDING';
  ALTER TABLE model_api DROP CONSTRAINT IF EXISTS uk_gw_instance_api_name;
  CREATE UNIQUE INDEX IF NOT EXISTS model_api_live_name ON model_api(gw_instance_id,api_name) WHERE deleted_at IS NULL;
  CREATE INDEX IF NOT EXISTS model_api_pending ON model_api(gw_instance_id,config_version,applied_version);
  ALTER TABLE model_api ADD CONSTRAINT model_api_versions CHECK(config_version>=1 AND applied_version>=0 AND applied_version<=config_version);
  ALTER TABLE model_api ADD CONSTRAINT model_api_status CHECK(publish_status IN ('PENDING','SYNCING','SUCCESS','FAILED'));
  ALTER TABLE model_api ADD CONSTRAINT model_api_config_object CHECK(jsonb_typeof(config)='object');

  CREATE OR REPLACE FUNCTION model_api_reconcile_guard() RETURNS trigger AS $guard$
  BEGIN
    IF NEW.api_id IS DISTINCT FROM OLD.api_id OR NEW.api_name IS DISTINCT FROM OLD.api_name
       OR NEW.gw_instance_id IS DISTINCT FROM OLD.gw_instance_id OR NEW.gmt_create IS DISTINCT FROM OLD.gmt_create THEN
      RAISE EXCEPTION 'model API identity is immutable' USING ERRCODE='42501';
    END IF;
    IF current_setting('model_api.actor',true)='reconciler' THEN
      IF NEW.config IS DISTINCT FROM OLD.config OR NEW.config_version IS DISTINCT FROM OLD.config_version
         OR NEW.deleted_at IS DISTINCT FROM OLD.deleted_at THEN
        RAISE EXCEPTION 'reconciler cannot edit business configuration' USING ERRCODE='42501';
      END IF;
      IF NEW.applied_version < OLD.applied_version THEN
        RAISE EXCEPTION 'applied_version cannot regress' USING ERRCODE='42501';
      END IF;
      NEW.gmt_modified := OLD.gmt_modified;
    ELSE
      IF NEW.applied_version IS DISTINCT FROM OLD.applied_version
         OR NEW.last_reconcile_at IS DISTINCT FROM OLD.last_reconcile_at
         OR NEW.purged_at IS DISTINCT FROM OLD.purged_at THEN
        RAISE EXCEPTION 'reconcile result columns are read-only' USING ERRCODE='42501';
      END IF;
      IF OLD.deleted_at IS NOT NULL THEN RAISE EXCEPTION 'deleted model API cannot be edited' USING ERRCODE='42501'; END IF;
      IF NEW.config_version <> OLD.config_version+1 OR NEW.publish_status <> 'PENDING' THEN
        RAISE EXCEPTION 'configuration updates must increment version and become PENDING' USING ERRCODE='42501';
      END IF;
      NEW.last_error := NULL;
      NEW.gmt_modified := now();
    END IF;
    RETURN NEW;
  END;
  $guard$ LANGUAGE plpgsql;
  CREATE TRIGGER trg_model_api_reconcile_guard BEFORE UPDATE ON model_api FOR EACH ROW EXECUTE FUNCTION model_api_reconcile_guard();

  CREATE OR REPLACE FUNCTION model_api_notify() RETURNS trigger AS $notify$
  BEGIN
    IF TG_OP='INSERT' THEN PERFORM pg_notify('model_api_changed',NEW.api_id);
    ELSIF NEW.config_version IS DISTINCT FROM OLD.config_version THEN PERFORM pg_notify('model_api_changed',NEW.api_id);
    END IF;
    RETURN NEW;
  END;
  $notify$ LANGUAGE plpgsql;
  CREATE TRIGGER trg_model_api_notify AFTER INSERT OR UPDATE ON model_api FOR EACH ROW EXECUTE FUNCTION model_api_notify();
  CREATE OR REPLACE FUNCTION model_api_delete_guard() RETURNS trigger AS $delete_guard$
  BEGIN
    IF current_setting('model_api.actor',true) IS DISTINCT FROM 'retention'
       OR OLD.deleted_at IS NULL OR OLD.purged_at IS NULL OR OLD.applied_version<>OLD.config_version THEN
      RAISE EXCEPTION 'use soft deletion; retention requires confirmed cleanup' USING ERRCODE='42501';
    END IF;
    RETURN OLD;
  END;
  $delete_guard$ LANGUAGE plpgsql;
  CREATE TRIGGER trg_model_api_delete_guard BEFORE DELETE ON model_api FOR EACH ROW EXECUTE FUNCTION model_api_delete_guard();
  INSERT INTO model_api_schema_history(version) VALUES(2);
END;
$migration$;
