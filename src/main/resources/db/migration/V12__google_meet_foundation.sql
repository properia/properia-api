DO $$ BEGIN CREATE TYPE "properia"."advertiser_calendar_provider" AS ENUM('google_calendar'); EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE TYPE "properia"."advertiser_calendar_connection_status" AS ENUM('active', 'revoked', 'error'); EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE TYPE "properia"."visit_meeting_provider" AS ENUM('google_meet', 'zoom', 'teams', 'custom'); EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE TYPE "properia"."visit_meeting_sync_status" AS ENUM('pending', 'synced', 'failed', 'manual'); EXCEPTION WHEN duplicate_object THEN NULL; END $$;

CREATE TABLE IF NOT EXISTS "properia"."advertiser_calendar_connections" (
  "id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
  "advertiser_id" uuid NOT NULL,
  "provider" "properia"."advertiser_calendar_provider" NOT NULL,
  "connected_by_user_id" uuid,
  "account_email" varchar(320),
  "access_token_encrypted" text,
  "refresh_token_encrypted" text,
  "token_expires_at" timestamp with time zone,
  "scopes" jsonb DEFAULT '[]'::jsonb NOT NULL,
  "status" "properia"."advertiser_calendar_connection_status" DEFAULT 'active' NOT NULL,
  "metadata" jsonb DEFAULT '{}'::jsonb NOT NULL,
  "created_at" timestamp with time zone DEFAULT now() NOT NULL,
  "updated_at" timestamp with time zone DEFAULT now() NOT NULL,
  CONSTRAINT "advertiser_calendar_connections_advertiser_provider_unique" UNIQUE("advertiser_id","provider")
);;

DO $$ BEGIN
  ALTER TABLE "properia"."advertiser_calendar_connections"
  ADD CONSTRAINT "advertiser_calendar_connections_advertiser_id_advertisers_id_fk"
  FOREIGN KEY ("advertiser_id") REFERENCES "properia"."advertisers"("id") ON DELETE cascade ON UPDATE no action;
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;
DO $$ BEGIN
  ALTER TABLE "properia"."advertiser_calendar_connections"
  ADD CONSTRAINT "advertiser_calendar_connections_connected_by_user_id_app_users_id_fk"
  FOREIGN KEY ("connected_by_user_id") REFERENCES "properia"."app_users"("id") ON DELETE set null ON UPDATE no action;
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

CREATE INDEX IF NOT EXISTS "idx_advertiser_calendar_connections_status"
  ON "properia"."advertiser_calendar_connections" USING btree ("status","updated_at");;
CREATE INDEX IF NOT EXISTS "idx_advertiser_calendar_connections_connected_by_user"
  ON "properia"."advertiser_calendar_connections" USING btree ("connected_by_user_id","updated_at");;

ALTER TABLE "properia"."visits" ADD COLUMN IF NOT EXISTS "meeting_provider" "properia"."visit_meeting_provider";;
ALTER TABLE "properia"."visits" ADD COLUMN IF NOT EXISTS "external_calendar_event_id" text;;
ALTER TABLE "properia"."visits" ADD COLUMN IF NOT EXISTS "meeting_created_at" timestamp with time zone;;
ALTER TABLE "properia"."visits" ADD COLUMN IF NOT EXISTS "meeting_sync_status" "properia"."visit_meeting_sync_status";;
