-- ─── Migration 0007: CRM Import Foundation ───────────────────────────────────

DO $$
BEGIN
  CREATE TYPE "properia"."crm_import_source_family" AS ENUM(
    'properia',
    'portal',
    'website',
    'manual',
    'referral',
    'partner',
    'messaging',
    'phone',
    'email'
  );
EXCEPTION
  WHEN duplicate_object THEN NULL;
END $$;;

DO $$
BEGIN
  CREATE TYPE "properia"."crm_import_source_channel" AS ENUM(
    'properia_listing',
    'properia_chat',
    'idealista',
    'imovirtual',
    'casa_sapo',
    'site_proprio',
    'whatsapp',
    'telefone',
    'email',
    'manual',
    'csv_import',
    'api_integration'
  );
EXCEPTION
  WHEN duplicate_object THEN NULL;
END $$;;

DO $$
BEGIN
  CREATE TYPE "properia"."crm_import_ingestion_method" AS ENUM(
    'native',
    'csv',
    'manual',
    'email_forward',
    'api',
    'webhook'
  );
EXCEPTION
  WHEN duplicate_object THEN NULL;
END $$;;

DO $$
BEGIN
  CREATE TYPE "properia"."crm_import_batch_status" AS ENUM(
    'processing',
    'completed',
    'partial',
    'failed'
  );
EXCEPTION
  WHEN duplicate_object THEN NULL;
END $$;;

DO $$
BEGIN
  CREATE TYPE "properia"."crm_import_match_status" AS ENUM(
    'matched',
    'ambiguous',
    'unmatched',
    'rejected'
  );
EXCEPTION
  WHEN duplicate_object THEN NULL;
END $$;;

DO $$
BEGIN
  CREATE TYPE "properia"."crm_import_action" AS ENUM(
    'created',
    'merged',
    'skipped',
    'review_required',
    'failed'
  );
EXCEPTION
  WHEN duplicate_object THEN NULL;
END $$;;

CREATE TABLE IF NOT EXISTS "properia"."crm_import_batches" (
  "id"                 uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
  "advertiser_id"      uuid NOT NULL REFERENCES "properia"."advertisers"("id") ON DELETE CASCADE,
  "created_by_user_id" uuid REFERENCES "properia"."app_users"("id") ON DELETE SET NULL,
  "source_family"      "properia"."crm_import_source_family" NOT NULL,
  "source_channel"     "properia"."crm_import_source_channel" NOT NULL,
  "ingestion_method"   "properia"."crm_import_ingestion_method" NOT NULL,
  "status"             "properia"."crm_import_batch_status" NOT NULL DEFAULT 'processing',
  "file_name"          text,
  "total_rows"         integer NOT NULL DEFAULT 0,
  "created_rows"       integer NOT NULL DEFAULT 0,
  "merged_rows"        integer NOT NULL DEFAULT 0,
  "rejected_rows"      integer NOT NULL DEFAULT 0,
  "metadata"           jsonb NOT NULL DEFAULT '{}'::jsonb,
  "created_at"         timestamp with time zone NOT NULL DEFAULT now(),
  "updated_at"         timestamp with time zone NOT NULL DEFAULT now()
);;

CREATE INDEX IF NOT EXISTS "idx_crm_import_batches_advertiser" ON "properia"."crm_import_batches" ("advertiser_id", "created_at");;
CREATE INDEX IF NOT EXISTS "idx_crm_import_batches_status" ON "properia"."crm_import_batches" ("status", "created_at");;

CREATE TABLE IF NOT EXISTS "properia"."crm_import_items" (
  "id"                  uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
  "batch_id"            uuid NOT NULL REFERENCES "properia"."crm_import_batches"("id") ON DELETE CASCADE,
  "advertiser_id"       uuid NOT NULL REFERENCES "properia"."advertisers"("id") ON DELETE CASCADE,
  "lead_id"             uuid REFERENCES "properia"."leads"("id") ON DELETE SET NULL,
  "listing_id"          uuid REFERENCES "properia"."listings"("id") ON DELETE SET NULL,
  "source_family"       "properia"."crm_import_source_family" NOT NULL,
  "source_channel"      "properia"."crm_import_source_channel" NOT NULL,
  "ingestion_method"    "properia"."crm_import_ingestion_method" NOT NULL,
  "external_lead_id"    text,
  "external_listing_ref" text,
  "match_status"        "properia"."crm_import_match_status" NOT NULL DEFAULT 'unmatched',
  "import_action"       "properia"."crm_import_action" NOT NULL DEFAULT 'review_required',
  "confidence_score"    numeric(5,2),
  "decision_reason"     text,
  "error_message"       text,
  "raw_payload"         jsonb NOT NULL DEFAULT '{}'::jsonb,
  "normalized_payload"  jsonb NOT NULL DEFAULT '{}'::jsonb,
  "metadata"            jsonb NOT NULL DEFAULT '{}'::jsonb,
  "created_at"          timestamp with time zone NOT NULL DEFAULT now(),
  "updated_at"          timestamp with time zone NOT NULL DEFAULT now()
);;

CREATE INDEX IF NOT EXISTS "idx_crm_import_items_batch" ON "properia"."crm_import_items" ("batch_id", "created_at");;
CREATE INDEX IF NOT EXISTS "idx_crm_import_items_advertiser" ON "properia"."crm_import_items" ("advertiser_id", "created_at");;
CREATE INDEX IF NOT EXISTS "idx_crm_import_items_match_status" ON "properia"."crm_import_items" ("match_status", "created_at");;
CREATE INDEX IF NOT EXISTS "idx_crm_import_items_external_lead" ON "properia"."crm_import_items" ("external_lead_id");;
