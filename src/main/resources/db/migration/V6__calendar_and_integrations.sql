CREATE TYPE "properia"."advertiser_calendar_connection_status" AS ENUM('active', 'revoked', 'error');;
CREATE TYPE "properia"."advertiser_calendar_provider" AS ENUM('google_calendar');;
CREATE TYPE "properia"."advertiser_integration_channel" AS ENUM('idealista', 'imovirtual', 'casa_sapo', 'site_proprio', 'generic');;
CREATE TYPE "properia"."advertiser_integration_status" AS ENUM('active', 'paused', 'error', 'pending_setup');;
CREATE TYPE "properia"."advertiser_integration_type" AS ENUM('email_forward', 'webhook', 'api_polling');;
CREATE TYPE "properia"."audit_event_category" AS ENUM('listing', 'advertiser', 'lead', 'visit', 'auth', 'billing', 'partner_lead', 'moderation', 'privacy', 'system');;
CREATE TYPE "properia"."audit_severity" AS ENUM('info', 'warn', 'critical');;
CREATE TYPE "properia"."chat_conversation_status" AS ENUM('active', 'closed', 'blocked');;
CREATE TYPE "properia"."chat_message_sender_type" AS ENUM('buyer', 'advertiser_member', 'system');;
CREATE TYPE "properia"."chat_message_type" AS ENUM('text', 'system');;
CREATE TYPE "properia"."chat_participant_role" AS ENUM('buyer', 'advertiser_member');;
CREATE TYPE "properia"."crm_import_action" AS ENUM('created', 'merged', 'skipped', 'review_required', 'failed');;
CREATE TYPE "properia"."crm_import_batch_status" AS ENUM('processing', 'completed', 'partial', 'failed');;
CREATE TYPE "properia"."crm_import_ingestion_method" AS ENUM('native', 'csv', 'manual', 'email_forward', 'api', 'webhook');;
CREATE TYPE "properia"."crm_import_match_status" AS ENUM('matched', 'ambiguous', 'unmatched', 'rejected');;
CREATE TYPE "properia"."crm_import_source_channel" AS ENUM('properia_listing', 'properia_chat', 'idealista', 'imovirtual', 'casa_sapo', 'site_proprio', 'whatsapp', 'telefone', 'email', 'manual', 'csv_import', 'api_integration');;
CREATE TYPE "properia"."crm_import_source_family" AS ENUM('properia', 'portal', 'website', 'manual', 'referral', 'partner', 'messaging', 'phone', 'email');;
CREATE TYPE "properia"."moderation_decision_source" AS ENUM('manual', 'assisted');;
CREATE TYPE "properia"."moderation_reason_category" AS ENUM('duplicado', 'fraude', 'conteudo_incorreto', 'conteudo_proibido', 'spam', 'outro', 'documentacao', 'identidade_nao_verificada', 'violacao_termos', 'qualidade_insuficiente');;
CREATE TYPE "properia"."moderation_target_type" AS ENUM('listing', 'advertiser');;
DO $$ BEGIN CREATE TYPE "properia"."professional_registration_type" AS ENUM('ami', 'commercial_reference'); EXCEPTION WHEN duplicate_object THEN NULL; END $$;
CREATE TYPE "properia"."visit_meeting_provider" AS ENUM('google_meet', 'zoom', 'teams', 'custom');;
CREATE TYPE "properia"."visit_meeting_sync_status" AS ENUM('pending', 'synced', 'failed', 'manual');;
ALTER TYPE "properia"."consent_purpose" ADD VALUE 'partner_data_sharing';;
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
);
;
CREATE TABLE IF NOT EXISTS "properia"."advertiser_credit_transactions" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"advertiser_id" uuid NOT NULL,
	"type" text NOT NULL,
	"amount" integer NOT NULL,
	"balance_after" integer NOT NULL,
	"stripe_checkout_session_id" text,
	"lead_id" uuid,
	"description" text,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL
);
;
CREATE TABLE IF NOT EXISTS "properia"."advertiser_integrations" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"advertiser_id" uuid NOT NULL,
	"integration_type" "properia"."advertiser_integration_type" NOT NULL,
	"channel" "properia"."advertiser_integration_channel" NOT NULL,
	"status" "properia"."advertiser_integration_status" DEFAULT 'pending_setup' NOT NULL,
	"inbound_token" text NOT NULL,
	"credentials" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"settings" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"last_sync_at" timestamp with time zone,
	"last_error_at" timestamp with time zone,
	"last_error_message" text,
	"total_leads_imported" integer DEFAULT 0 NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "advertiser_integrations_inbound_token_unique" UNIQUE("inbound_token"),
	CONSTRAINT "advertiser_integrations_type_channel_unique" UNIQUE("advertiser_id","integration_type","channel")
);
;
CREATE TABLE IF NOT EXISTS "properia"."advertiser_visit_availability_blocks" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"advertiser_id" uuid NOT NULL,
	"starts_at" timestamp with time zone NOT NULL,
	"ends_at" timestamp with time zone NOT NULL,
	"reason" text,
	"source" text DEFAULT 'manual' NOT NULL,
	"created_by_user_id" uuid,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL
);
;
CREATE TABLE IF NOT EXISTS "properia"."advertiser_visit_availability_rules" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"advertiser_id" uuid NOT NULL,
	"weekday" integer NOT NULL,
	"is_enabled" boolean DEFAULT false NOT NULL,
	"start_time" text NOT NULL,
	"end_time" text NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "advertiser_visit_availability_rules_advertiser_weekday_unique" UNIQUE("advertiser_id","weekday"),
	CONSTRAINT "advertiser_visit_availability_rules_weekday_check" CHECK ("properia"."advertiser_visit_availability_rules"."weekday" >= 0 and "properia"."advertiser_visit_availability_rules"."weekday" <= 6)
);
;
CREATE TABLE IF NOT EXISTS "properia"."advertiser_visit_availability_settings" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"advertiser_id" uuid NOT NULL,
	"timezone" text DEFAULT 'Europe/Lisbon' NOT NULL,
	"slot_duration_minutes" integer DEFAULT 45 NOT NULL,
	"buffer_minutes" integer DEFAULT 15 NOT NULL,
	"min_notice_hours" integer DEFAULT 12 NOT NULL,
	"max_advance_days" integer DEFAULT 30 NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "advertiser_visit_availability_settings_advertiser_unique" UNIQUE("advertiser_id")
);
;
CREATE TABLE IF NOT EXISTS "properia"."chat_conversations" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"advertiser_id" uuid NOT NULL,
	"listing_id" uuid NOT NULL,
	"lead_id" uuid,
	"buyer_user_id" uuid NOT NULL,
	"status" "properia"."chat_conversation_status" DEFAULT 'active' NOT NULL,
	"last_message_at" timestamp with time zone,
	"last_message_preview" text,
	"closed_at" timestamp with time zone,
	"metadata" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "chat_conversations_advertiser_listing_buyer_unique" UNIQUE("advertiser_id","listing_id","buyer_user_id")
);
;
CREATE TABLE IF NOT EXISTS "properia"."chat_messages" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"conversation_id" uuid NOT NULL,
	"advertiser_id" uuid NOT NULL,
	"listing_id" uuid NOT NULL,
	"lead_id" uuid,
	"sender_type" "properia"."chat_message_sender_type" NOT NULL,
	"sender_user_id" uuid,
	"message_type" "properia"."chat_message_type" DEFAULT 'text' NOT NULL,
	"body" text NOT NULL,
	"metadata" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL
);
;
CREATE TABLE IF NOT EXISTS "properia"."chat_participants" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"conversation_id" uuid NOT NULL,
	"advertiser_id" uuid,
	"user_id" uuid,
	"role" "properia"."chat_participant_role" NOT NULL,
	"last_read_at" timestamp with time zone,
	"metadata" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "chat_participants_conversation_role_unique" UNIQUE("conversation_id","role"),
	CONSTRAINT "chat_participants_role_subject_check" CHECK ((
    ("role" = 'buyer' and "user_id" is not null and "advertiser_id" is null)
    or
    ("role" = 'advertiser_member' and "advertiser_id" is not null)
  ))
);
;
CREATE TABLE IF NOT EXISTS "properia"."crm_import_batches" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"advertiser_id" uuid NOT NULL,
	"created_by_user_id" uuid,
	"source_family" "properia"."crm_import_source_family" NOT NULL,
	"source_channel" "properia"."crm_import_source_channel" NOT NULL,
	"ingestion_method" "properia"."crm_import_ingestion_method" NOT NULL,
	"status" "properia"."crm_import_batch_status" DEFAULT 'processing' NOT NULL,
	"file_name" text,
	"total_rows" integer DEFAULT 0 NOT NULL,
	"created_rows" integer DEFAULT 0 NOT NULL,
	"merged_rows" integer DEFAULT 0 NOT NULL,
	"rejected_rows" integer DEFAULT 0 NOT NULL,
	"metadata" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL
);
;
CREATE TABLE IF NOT EXISTS "properia"."crm_import_items" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"batch_id" uuid NOT NULL,
	"advertiser_id" uuid NOT NULL,
	"lead_id" uuid,
	"listing_id" uuid,
	"source_family" "properia"."crm_import_source_family" NOT NULL,
	"source_channel" "properia"."crm_import_source_channel" NOT NULL,
	"ingestion_method" "properia"."crm_import_ingestion_method" NOT NULL,
	"external_lead_id" text,
	"external_listing_ref" text,
	"match_status" "properia"."crm_import_match_status" DEFAULT 'unmatched' NOT NULL,
	"import_action" "properia"."crm_import_action" DEFAULT 'review_required' NOT NULL,
	"confidence_score" numeric(5, 2),
	"decision_reason" text,
	"error_message" text,
	"raw_payload" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"normalized_payload" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"metadata" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL
);
;
CREATE TABLE IF NOT EXISTS "properia"."decision_dossiers" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"listing_id" uuid NOT NULL,
	"user_id" uuid,
	"goal" text NOT NULL,
	"budget_comfort" integer NOT NULL,
	"has_down_payment" boolean DEFAULT false NOT NULL,
	"visit_soon" boolean DEFAULT false NOT NULL,
	"priority" text NOT NULL,
	"must_haves" jsonb DEFAULT '[]'::jsonb NOT NULL,
	"fit_label" text NOT NULL,
	"strengths" jsonb DEFAULT '[]'::jsonb NOT NULL,
	"attention_points" jsonb DEFAULT '[]'::jsonb NOT NULL,
	"visit_checklist" jsonb DEFAULT '[]'::jsonb NOT NULL,
	"pricing" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"share_with_advertiser" boolean DEFAULT false NOT NULL,
	"metadata" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL
);
;
CREATE TABLE IF NOT EXISTS "properia"."lead_reveals" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"advertiser_id" uuid NOT NULL,
	"lead_id" uuid NOT NULL,
	"credits_spent" integer DEFAULT 1 NOT NULL,
	"revealed_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "lead_reveals_advertiser_lead_unique" UNIQUE("advertiser_id","lead_id")
);
;
CREATE TABLE IF NOT EXISTS "properia"."moderation_decisions" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"target_type" "properia"."moderation_target_type" NOT NULL,
	"target_id" uuid NOT NULL,
	"listing_id" uuid,
	"advertiser_id" uuid,
	"decision" "properia"."moderation_decision" NOT NULL,
	"reason_category" "properia"."moderation_reason_category" NOT NULL,
	"public_reason" text NOT NULL,
	"internal_notes" text,
	"evidence" jsonb DEFAULT '[]'::jsonb NOT NULL,
	"related_report_ids" jsonb DEFAULT '[]'::jsonb NOT NULL,
	"decision_source" "properia"."moderation_decision_source" DEFAULT 'manual' NOT NULL,
	"actor_user_id" uuid,
	"metadata" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL
);
;
CREATE TABLE IF NOT EXISTS "properia"."operational_metrics_snapshots" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"metric_name" text NOT NULL,
	"count" integer DEFAULT 0 NOT NULL,
	"tags" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"window_start" timestamp with time zone NOT NULL,
	"window_end" timestamp with time zone NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL
);
;
CREATE TABLE IF NOT EXISTS "properia"."system_audit_events" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"event_category" "properia"."audit_event_category" NOT NULL,
	"action" text NOT NULL,
	"severity" "properia"."audit_severity" DEFAULT 'info' NOT NULL,
	"actor_user_id" uuid,
	"actor_email" varchar(320),
	"actor_role" text,
	"advertiser_id" uuid,
	"listing_id" uuid,
	"entity_type" text,
	"entity_id" uuid,
	"ip_address" text,
	"request_id" text,
	"app_env" text DEFAULT 'development' NOT NULL,
	"metadata" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL
);
;
ALTER TABLE "properia"."partner_leads" DROP CONSTRAINT "partner_leads_unique";;
ALTER TABLE "properia"."partner_leads" DROP CONSTRAINT "partner_leads_partner_id_partners_id_fk";
;
ALTER TABLE "properia"."partner_leads" DROP CONSTRAINT "partner_leads_lead_id_leads_id_fk";
;
ALTER TABLE "properia"."partner_leads" ALTER COLUMN "partner_id" DROP NOT NULL;;
ALTER TABLE "properia"."partner_leads" ALTER COLUMN "lead_id" DROP NOT NULL;;
ALTER TABLE "properia"."partner_leads" ALTER COLUMN "status" SET DEFAULT 'pending';;
ALTER TABLE "properia"."partners" ALTER COLUMN "is_active" SET DEFAULT false;;
ALTER TABLE "properia"."advertisers" ADD COLUMN IF NOT EXISTS "professional_registration_type" "properia"."professional_registration_type";;
ALTER TABLE "properia"."advertisers" ADD COLUMN IF NOT EXISTS "business_address_line_1" text;;
ALTER TABLE "properia"."advertisers" ADD COLUMN IF NOT EXISTS "business_postal_code" text;;
ALTER TABLE "properia"."advertisers" ADD COLUMN IF NOT EXISTS "business_city" text;;
ALTER TABLE "properia"."advertisers" ADD COLUMN IF NOT EXISTS "business_district" text;;
ALTER TABLE "properia"."advertisers" ADD COLUMN IF NOT EXISTS "business_country" varchar(2);;
ALTER TABLE "properia"."advertisers" ADD COLUMN IF NOT EXISTS "tax_number_verified_at" timestamp with time zone;;
ALTER TABLE "properia"."advertisers" ADD COLUMN IF NOT EXISTS "professional_registration_verified_at" timestamp with time zone;;
ALTER TABLE "properia"."listing_energy" ADD COLUMN IF NOT EXISTS "energy_certificate_status" text;;
ALTER TABLE "properia"."listing_energy" ADD COLUMN IF NOT EXISTS "energy_certificate_exemption_reason" text;;
ALTER TABLE "properia"."partner_leads" ADD COLUMN IF NOT EXISTS "listing_id" uuid;;
ALTER TABLE "properia"."partner_leads" ADD COLUMN IF NOT EXISTS "user_id" uuid;;
ALTER TABLE "properia"."partner_leads" ADD COLUMN IF NOT EXISTS "contact_name" text;;
ALTER TABLE "properia"."partner_leads" ADD COLUMN IF NOT EXISTS "contact_email" varchar(320);;
ALTER TABLE "properia"."partner_leads" ADD COLUMN IF NOT EXISTS "contact_phone" text;;
ALTER TABLE "properia"."partner_leads" ADD COLUMN IF NOT EXISTS "loan_amount" numeric(14, 2);;
ALTER TABLE "properia"."partner_leads" ADD COLUMN IF NOT EXISTS "consent_text" text;;
ALTER TABLE "properia"."partner_leads" ADD COLUMN IF NOT EXISTS "dispatch_attempts" integer DEFAULT 0 NOT NULL;;
ALTER TABLE "properia"."partner_leads" ADD COLUMN IF NOT EXISTS "dispatched_at" timestamp with time zone;;
ALTER TABLE "properia"."partners" ADD COLUMN IF NOT EXISTS "dispatch_type" text DEFAULT 'none' NOT NULL;;
ALTER TABLE "properia"."partners" ADD COLUMN IF NOT EXISTS "api_endpoint" text;;
ALTER TABLE "properia"."partners" ADD COLUMN IF NOT EXISTS "supported_product_types" jsonb DEFAULT '[]'::jsonb NOT NULL;;
ALTER TABLE "properia"."visits" ADD COLUMN IF NOT EXISTS "meeting_provider" "properia"."visit_meeting_provider";;
ALTER TABLE "properia"."visits" ADD COLUMN IF NOT EXISTS "external_calendar_event_id" text;;
ALTER TABLE "properia"."visits" ADD COLUMN IF NOT EXISTS "meeting_created_at" timestamp with time zone;;
ALTER TABLE "properia"."visits" ADD COLUMN IF NOT EXISTS "meeting_sync_status" "properia"."visit_meeting_sync_status";;
ALTER TABLE "properia"."visits" ADD COLUMN IF NOT EXISTS "outcome" text;;
ALTER TABLE "properia"."visits" ADD COLUMN IF NOT EXISTS "outcome_notes" text;;
ALTER TABLE "properia"."advertiser_calendar_connections" ADD CONSTRAINT "advertiser_calendar_connections_advertiser_id_advertisers_id_fk" FOREIGN KEY ("advertiser_id") REFERENCES "properia"."advertisers"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."advertiser_calendar_connections" ADD CONSTRAINT "advertiser_calendar_connections_connected_by_user_id_app_users_id_fk" FOREIGN KEY ("connected_by_user_id") REFERENCES "properia"."app_users"("id") ON DELETE set null ON UPDATE no action;;
ALTER TABLE "properia"."advertiser_credit_transactions" ADD CONSTRAINT "advertiser_credit_transactions_advertiser_id_advertisers_id_fk" FOREIGN KEY ("advertiser_id") REFERENCES "properia"."advertisers"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."advertiser_credit_transactions" ADD CONSTRAINT "advertiser_credit_transactions_lead_id_leads_id_fk" FOREIGN KEY ("lead_id") REFERENCES "properia"."leads"("id") ON DELETE set null ON UPDATE no action;;
ALTER TABLE "properia"."advertiser_integrations" ADD CONSTRAINT "advertiser_integrations_advertiser_id_advertisers_id_fk" FOREIGN KEY ("advertiser_id") REFERENCES "properia"."advertisers"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."advertiser_visit_availability_blocks" ADD CONSTRAINT "advertiser_visit_availability_blocks_advertiser_id_advertisers_id_fk" FOREIGN KEY ("advertiser_id") REFERENCES "properia"."advertisers"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."advertiser_visit_availability_blocks" ADD CONSTRAINT "advertiser_visit_availability_blocks_created_by_user_id_app_users_id_fk" FOREIGN KEY ("created_by_user_id") REFERENCES "properia"."app_users"("id") ON DELETE set null ON UPDATE no action;;
ALTER TABLE "properia"."advertiser_visit_availability_rules" ADD CONSTRAINT "advertiser_visit_availability_rules_advertiser_id_advertisers_id_fk" FOREIGN KEY ("advertiser_id") REFERENCES "properia"."advertisers"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."advertiser_visit_availability_settings" ADD CONSTRAINT "advertiser_visit_availability_settings_advertiser_id_advertisers_id_fk" FOREIGN KEY ("advertiser_id") REFERENCES "properia"."advertisers"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."chat_conversations" ADD CONSTRAINT "chat_conversations_advertiser_id_advertisers_id_fk" FOREIGN KEY ("advertiser_id") REFERENCES "properia"."advertisers"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."chat_conversations" ADD CONSTRAINT "chat_conversations_listing_id_listings_id_fk" FOREIGN KEY ("listing_id") REFERENCES "properia"."listings"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."chat_conversations" ADD CONSTRAINT "chat_conversations_lead_id_leads_id_fk" FOREIGN KEY ("lead_id") REFERENCES "properia"."leads"("id") ON DELETE set null ON UPDATE no action;;
ALTER TABLE "properia"."chat_conversations" ADD CONSTRAINT "chat_conversations_buyer_user_id_app_users_id_fk" FOREIGN KEY ("buyer_user_id") REFERENCES "properia"."app_users"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."chat_messages" ADD CONSTRAINT "chat_messages_conversation_id_chat_conversations_id_fk" FOREIGN KEY ("conversation_id") REFERENCES "properia"."chat_conversations"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."chat_messages" ADD CONSTRAINT "chat_messages_advertiser_id_advertisers_id_fk" FOREIGN KEY ("advertiser_id") REFERENCES "properia"."advertisers"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."chat_messages" ADD CONSTRAINT "chat_messages_listing_id_listings_id_fk" FOREIGN KEY ("listing_id") REFERENCES "properia"."listings"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."chat_messages" ADD CONSTRAINT "chat_messages_lead_id_leads_id_fk" FOREIGN KEY ("lead_id") REFERENCES "properia"."leads"("id") ON DELETE set null ON UPDATE no action;;
ALTER TABLE "properia"."chat_messages" ADD CONSTRAINT "chat_messages_sender_user_id_app_users_id_fk" FOREIGN KEY ("sender_user_id") REFERENCES "properia"."app_users"("id") ON DELETE set null ON UPDATE no action;;
ALTER TABLE "properia"."chat_participants" ADD CONSTRAINT "chat_participants_conversation_id_chat_conversations_id_fk" FOREIGN KEY ("conversation_id") REFERENCES "properia"."chat_conversations"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."chat_participants" ADD CONSTRAINT "chat_participants_advertiser_id_advertisers_id_fk" FOREIGN KEY ("advertiser_id") REFERENCES "properia"."advertisers"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."chat_participants" ADD CONSTRAINT "chat_participants_user_id_app_users_id_fk" FOREIGN KEY ("user_id") REFERENCES "properia"."app_users"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."crm_import_batches" ADD CONSTRAINT "crm_import_batches_advertiser_id_advertisers_id_fk" FOREIGN KEY ("advertiser_id") REFERENCES "properia"."advertisers"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."crm_import_batches" ADD CONSTRAINT "crm_import_batches_created_by_user_id_app_users_id_fk" FOREIGN KEY ("created_by_user_id") REFERENCES "properia"."app_users"("id") ON DELETE set null ON UPDATE no action;;
ALTER TABLE "properia"."crm_import_items" ADD CONSTRAINT "crm_import_items_batch_id_crm_import_batches_id_fk" FOREIGN KEY ("batch_id") REFERENCES "properia"."crm_import_batches"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."crm_import_items" ADD CONSTRAINT "crm_import_items_advertiser_id_advertisers_id_fk" FOREIGN KEY ("advertiser_id") REFERENCES "properia"."advertisers"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."crm_import_items" ADD CONSTRAINT "crm_import_items_lead_id_leads_id_fk" FOREIGN KEY ("lead_id") REFERENCES "properia"."leads"("id") ON DELETE set null ON UPDATE no action;;
ALTER TABLE "properia"."crm_import_items" ADD CONSTRAINT "crm_import_items_listing_id_listings_id_fk" FOREIGN KEY ("listing_id") REFERENCES "properia"."listings"("id") ON DELETE set null ON UPDATE no action;;
ALTER TABLE "properia"."decision_dossiers" ADD CONSTRAINT "decision_dossiers_listing_id_listings_id_fk" FOREIGN KEY ("listing_id") REFERENCES "properia"."listings"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."decision_dossiers" ADD CONSTRAINT "decision_dossiers_user_id_app_users_id_fk" FOREIGN KEY ("user_id") REFERENCES "properia"."app_users"("id") ON DELETE set null ON UPDATE no action;;
ALTER TABLE "properia"."lead_reveals" ADD CONSTRAINT "lead_reveals_advertiser_id_advertisers_id_fk" FOREIGN KEY ("advertiser_id") REFERENCES "properia"."advertisers"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."lead_reveals" ADD CONSTRAINT "lead_reveals_lead_id_leads_id_fk" FOREIGN KEY ("lead_id") REFERENCES "properia"."leads"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."moderation_decisions" ADD CONSTRAINT "moderation_decisions_listing_id_listings_id_fk" FOREIGN KEY ("listing_id") REFERENCES "properia"."listings"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."moderation_decisions" ADD CONSTRAINT "moderation_decisions_advertiser_id_advertisers_id_fk" FOREIGN KEY ("advertiser_id") REFERENCES "properia"."advertisers"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."moderation_decisions" ADD CONSTRAINT "moderation_decisions_actor_user_id_app_users_id_fk" FOREIGN KEY ("actor_user_id") REFERENCES "properia"."app_users"("id") ON DELETE set null ON UPDATE no action;;
ALTER TABLE "properia"."system_audit_events" ADD CONSTRAINT "system_audit_events_actor_user_id_app_users_id_fk" FOREIGN KEY ("actor_user_id") REFERENCES "properia"."app_users"("id") ON DELETE set null ON UPDATE no action;;
ALTER TABLE "properia"."system_audit_events" ADD CONSTRAINT "system_audit_events_advertiser_id_advertisers_id_fk" FOREIGN KEY ("advertiser_id") REFERENCES "properia"."advertisers"("id") ON DELETE set null ON UPDATE no action;;
ALTER TABLE "properia"."system_audit_events" ADD CONSTRAINT "system_audit_events_listing_id_listings_id_fk" FOREIGN KEY ("listing_id") REFERENCES "properia"."listings"("id") ON DELETE set null ON UPDATE no action;;
CREATE INDEX IF NOT EXISTS "idx_advertiser_calendar_connections_status" ON "properia"."advertiser_calendar_connections" USING btree ("status","updated_at");;
CREATE INDEX IF NOT EXISTS "idx_advertiser_calendar_connections_connected_by_user" ON "properia"."advertiser_calendar_connections" USING btree ("connected_by_user_id","updated_at");;
CREATE INDEX IF NOT EXISTS "idx_advertiser_credit_txns_advertiser" ON "properia"."advertiser_credit_transactions" USING btree ("advertiser_id","created_at");;
CREATE INDEX IF NOT EXISTS "idx_advertiser_credit_txns_stripe" ON "properia"."advertiser_credit_transactions" USING btree ("stripe_checkout_session_id");;
CREATE INDEX IF NOT EXISTS "idx_advertiser_integrations_advertiser" ON "properia"."advertiser_integrations" USING btree ("advertiser_id");;
CREATE INDEX IF NOT EXISTS "idx_advertiser_integrations_token" ON "properia"."advertiser_integrations" USING btree ("inbound_token");;
CREATE INDEX IF NOT EXISTS "idx_visit_availability_blocks_advertiser" ON "properia"."advertiser_visit_availability_blocks" USING btree ("advertiser_id","starts_at");;
CREATE INDEX IF NOT EXISTS "idx_visit_availability_rules_advertiser" ON "properia"."advertiser_visit_availability_rules" USING btree ("advertiser_id","weekday");;
CREATE INDEX IF NOT EXISTS "idx_chat_conversations_buyer_updated" ON "properia"."chat_conversations" USING btree ("buyer_user_id","updated_at");;
CREATE INDEX IF NOT EXISTS "idx_chat_conversations_advertiser_updated" ON "properia"."chat_conversations" USING btree ("advertiser_id","updated_at");;
CREATE INDEX IF NOT EXISTS "idx_chat_messages_conversation_created" ON "properia"."chat_messages" USING btree ("conversation_id","created_at");;
CREATE INDEX IF NOT EXISTS "idx_chat_messages_advertiser_created" ON "properia"."chat_messages" USING btree ("advertiser_id","created_at");;
CREATE INDEX IF NOT EXISTS "idx_chat_participants_user" ON "properia"."chat_participants" USING btree ("user_id","updated_at");;
CREATE INDEX IF NOT EXISTS "idx_chat_participants_advertiser" ON "properia"."chat_participants" USING btree ("advertiser_id","updated_at");;
CREATE INDEX IF NOT EXISTS "idx_crm_import_batches_advertiser" ON "properia"."crm_import_batches" USING btree ("advertiser_id","created_at");;
CREATE INDEX IF NOT EXISTS "idx_crm_import_batches_status" ON "properia"."crm_import_batches" USING btree ("status","created_at");;
CREATE INDEX IF NOT EXISTS "idx_crm_import_items_batch" ON "properia"."crm_import_items" USING btree ("batch_id","created_at");;
CREATE INDEX IF NOT EXISTS "idx_crm_import_items_advertiser" ON "properia"."crm_import_items" USING btree ("advertiser_id","created_at");;
CREATE INDEX IF NOT EXISTS "idx_crm_import_items_match_status" ON "properia"."crm_import_items" USING btree ("match_status","created_at");;
CREATE INDEX IF NOT EXISTS "idx_crm_import_items_external_lead" ON "properia"."crm_import_items" USING btree ("external_lead_id");;
CREATE INDEX IF NOT EXISTS "idx_decision_dossiers_user_listing" ON "properia"."decision_dossiers" USING btree ("user_id","listing_id","created_at");;
CREATE INDEX IF NOT EXISTS "idx_decision_dossiers_listing_created" ON "properia"."decision_dossiers" USING btree ("listing_id","created_at");;
CREATE INDEX IF NOT EXISTS "idx_lead_reveals_advertiser" ON "properia"."lead_reveals" USING btree ("advertiser_id","revealed_at");;
CREATE INDEX IF NOT EXISTS "idx_moderation_decisions_target" ON "properia"."moderation_decisions" USING btree ("target_type","target_id","created_at");;
CREATE INDEX IF NOT EXISTS "idx_moderation_decisions_listing" ON "properia"."moderation_decisions" USING btree ("listing_id","created_at");;
CREATE INDEX IF NOT EXISTS "idx_moderation_decisions_advertiser" ON "properia"."moderation_decisions" USING btree ("advertiser_id","created_at");;
CREATE INDEX IF NOT EXISTS "idx_moderation_decisions_actor" ON "properia"."moderation_decisions" USING btree ("actor_user_id","created_at");;
CREATE INDEX IF NOT EXISTS "idx_oms_name_window" ON "properia"."operational_metrics_snapshots" USING btree ("metric_name","window_start");;
CREATE INDEX IF NOT EXISTS "idx_sae_category_created" ON "properia"."system_audit_events" USING btree ("event_category","created_at");;
CREATE INDEX IF NOT EXISTS "idx_sae_action_created" ON "properia"."system_audit_events" USING btree ("action","created_at");;
CREATE INDEX IF NOT EXISTS "idx_sae_actor_created" ON "properia"."system_audit_events" USING btree ("actor_user_id","created_at");;
CREATE INDEX IF NOT EXISTS "idx_sae_advertiser_created" ON "properia"."system_audit_events" USING btree ("advertiser_id","created_at");;
CREATE INDEX IF NOT EXISTS "idx_sae_listing_created" ON "properia"."system_audit_events" USING btree ("listing_id","created_at");;
CREATE INDEX IF NOT EXISTS "idx_sae_severity_created" ON "properia"."system_audit_events" USING btree ("severity","created_at");;
ALTER TABLE "properia"."partner_leads" ADD CONSTRAINT "partner_leads_listing_id_listings_id_fk" FOREIGN KEY ("listing_id") REFERENCES "properia"."listings"("id") ON DELETE set null ON UPDATE no action;;
ALTER TABLE "properia"."partner_leads" ADD CONSTRAINT "partner_leads_user_id_app_users_id_fk" FOREIGN KEY ("user_id") REFERENCES "properia"."app_users"("id") ON DELETE set null ON UPDATE no action;;
ALTER TABLE "properia"."partner_leads" ADD CONSTRAINT "partner_leads_partner_id_partners_id_fk" FOREIGN KEY ("partner_id") REFERENCES "properia"."partners"("id") ON DELETE set null ON UPDATE no action;;
ALTER TABLE "properia"."partner_leads" ADD CONSTRAINT "partner_leads_lead_id_leads_id_fk" FOREIGN KEY ("lead_id") REFERENCES "properia"."leads"("id") ON DELETE set null ON UPDATE no action;;
CREATE INDEX IF NOT EXISTS "idx_partner_leads_status" ON "properia"."partner_leads" USING btree ("status","created_at");;
CREATE INDEX IF NOT EXISTS "idx_partner_leads_partner" ON "properia"."partner_leads" USING btree ("partner_id","product_type","created_at");;
CREATE INDEX IF NOT EXISTS "idx_partner_leads_user" ON "properia"."partner_leads" USING btree ("user_id","created_at");