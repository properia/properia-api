-- ─── Migration 0006: Observability & Audit ────────────────────────────────────
-- Adds system_audit_events (unified persistent audit log) and
-- operational_metrics_snapshots (daily persistence for in-memory counters).

DO $$
BEGIN
  CREATE TYPE "properia"."audit_severity" AS ENUM('info', 'warn', 'critical');
EXCEPTION
  WHEN duplicate_object THEN NULL;
END $$;;

DO $$
BEGIN
  CREATE TYPE "properia"."audit_event_category" AS ENUM(
    'listing',
    'advertiser',
    'lead',
    'visit',
    'auth',
    'billing',
    'partner_lead',
    'moderation',
    'privacy',
    'system'
  );
EXCEPTION
  WHEN duplicate_object THEN NULL;
END $$;;

CREATE TABLE IF NOT EXISTS "properia"."system_audit_events" (
  "id"              uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
  "event_category"  "properia"."audit_event_category" NOT NULL,
  "action"          text NOT NULL,
  "severity"        "properia"."audit_severity" DEFAULT 'info' NOT NULL,
  "actor_user_id"   uuid REFERENCES "properia"."app_users"("id") ON DELETE SET NULL,
  "actor_email"     varchar(320),
  "actor_role"      text,
  "advertiser_id"   uuid REFERENCES "properia"."advertisers"("id") ON DELETE SET NULL,
  "listing_id"      uuid REFERENCES "properia"."listings"("id") ON DELETE SET NULL,
  "entity_type"     text,
  "entity_id"       uuid,
  "ip_address"      text,
  "request_id"      text,
  "app_env"         text NOT NULL DEFAULT 'development',
  "metadata"        jsonb NOT NULL DEFAULT '{}'::jsonb,
  "created_at"      timestamp with time zone NOT NULL DEFAULT now()
);;

CREATE INDEX IF NOT EXISTS "idx_sae_category_created"   ON "properia"."system_audit_events" ("event_category", "created_at" DESC);;
CREATE INDEX IF NOT EXISTS "idx_sae_action_created"     ON "properia"."system_audit_events" ("action", "created_at" DESC);;
CREATE INDEX IF NOT EXISTS "idx_sae_actor_created"      ON "properia"."system_audit_events" ("actor_user_id", "created_at" DESC);;
CREATE INDEX IF NOT EXISTS "idx_sae_advertiser_created" ON "properia"."system_audit_events" ("advertiser_id", "created_at" DESC);;
CREATE INDEX IF NOT EXISTS "idx_sae_listing_created"    ON "properia"."system_audit_events" ("listing_id", "created_at" DESC);;
CREATE INDEX IF NOT EXISTS "idx_sae_severity_created"   ON "properia"."system_audit_events" ("severity", "created_at" DESC);;

CREATE TABLE IF NOT EXISTS "properia"."operational_metrics_snapshots" (
  "id"           uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
  "metric_name"  text NOT NULL,
  "count"        integer NOT NULL DEFAULT 0,
  "tags"         jsonb NOT NULL DEFAULT '{}'::jsonb,
  "window_start" timestamp with time zone NOT NULL,
  "window_end"   timestamp with time zone NOT NULL,
  "created_at"   timestamp with time zone NOT NULL DEFAULT now()
);;

CREATE INDEX IF NOT EXISTS "idx_oms_name_window" ON "properia"."operational_metrics_snapshots" ("metric_name", "window_start" DESC);;
