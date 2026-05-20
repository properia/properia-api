CREATE TYPE "properia"."advertiser_membership_role" AS ENUM('owner', 'admin', 'editor', 'viewer', 'sales');;
CREATE TYPE "properia"."advertiser_onboarding_status" AS ENUM('not_started', 'identity_started', 'company_started', 'verification_pending', 'active', 'suspended', 'rejected');;
CREATE TYPE "properia"."advertiser_onboarding_step" AS ENUM('intent', 'basic_profile', 'commercial_identity', 'market_scope', 'first_listing', 'done');;
CREATE TYPE "properia"."advertiser_verification_status" AS ENUM('unverified', 'pending_review', 'verified_basic', 'verified_business', 'suspended');;
CREATE TYPE "properia"."auth_provider" AS ENUM('local', 'google');;
CREATE TYPE "properia"."consent_purpose" AS ENUM('terms_privacy', 'lead_visit_operational', 'marketing', 'personalization', 'cookie_preferences');;
CREATE TYPE "properia"."crm_audit_entity_type" AS ENUM('lead', 'visit');;
CREATE TYPE "properia"."data_request_status" AS ENUM('requested', 'in_review', 'completed', 'rejected');;
CREATE TYPE "properia"."data_request_type" AS ENUM('export', 'deletion');;
CREATE TYPE "properia"."listing_report_status" AS ENUM('open', 'reviewed', 'dismissed');;
CREATE TYPE "properia"."market_benchmark_granularity" AS ENUM('country', 'district', 'municipality', 'parish', 'neighborhood');;
CREATE TYPE "properia"."market_benchmark_source" AS ENUM('ine', 'dados_gov', 'internal', 'manual');;
CREATE TYPE "properia"."moderation_decision" AS ENUM('approved', 'rejected', 'paused', 'suspended');;
CREATE TYPE "properia"."password_algorithm" AS ENUM('argon2id', 'bcrypt', 'scrypt');;
CREATE TYPE "properia"."reference_rate_provider" AS ENUM('ecb', 'manual', 'internal');;
CREATE TABLE "properia"."advertiser_onboarding" (
	"advertiser_id" uuid PRIMARY KEY NOT NULL,
	"owner_user_id" uuid NOT NULL,
	"status" "properia"."advertiser_onboarding_status" DEFAULT 'not_started' NOT NULL,
	"step_current" "properia"."advertiser_onboarding_step" DEFAULT 'intent' NOT NULL,
	"completed_steps" jsonb DEFAULT '[]'::jsonb NOT NULL,
	"advertiser_type_selected" "properia"."advertiser_type",
	"service_districts" jsonb DEFAULT '[]'::jsonb NOT NULL,
	"property_specialties" jsonb DEFAULT '[]'::jsonb NOT NULL,
	"accepts_online_visits" boolean DEFAULT false NOT NULL,
	"verification_notes" text,
	"submitted_at" timestamp with time zone,
	"reviewed_at" timestamp with time zone,
	"reviewed_by_user_id" uuid,
	"rejection_reason" text,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL
);
;
CREATE TABLE "properia"."crm_audit_events" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"advertiser_id" uuid NOT NULL,
	"actor_user_id" uuid,
	"entity_type" "properia"."crm_audit_entity_type" NOT NULL,
	"lead_id" uuid,
	"visit_id" uuid,
	"action" text NOT NULL,
	"payload" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL
);
;
CREATE TABLE "properia"."listing_affordability_snapshots" (
	"listing_id" uuid PRIMARY KEY NOT NULL,
	"reference_rate_id" uuid,
	"entry_pct" numeric(5, 4) NOT NULL,
	"term_years" integer NOT NULL,
	"annual_rate_pct" numeric(8, 4) NOT NULL,
	"financed_amount" numeric(14, 2),
	"monthly_payment" numeric(14, 2),
	"affordability_score" numeric(5, 2),
	"effort_label" text,
	"summary_short" text,
	"computed_at" timestamp with time zone,
	"model_version" integer DEFAULT 1 NOT NULL,
	"metadata" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL
);
;
CREATE TABLE "properia"."listing_reports" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"listing_id" uuid NOT NULL,
	"reporter_user_id" uuid,
	"status" "properia"."listing_report_status" DEFAULT 'open' NOT NULL,
	"reason" text NOT NULL,
	"details" text,
	"reviewed_at" timestamp with time zone,
	"reviewed_by_user_id" uuid,
	"resolution_notes" text,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL
);
;
CREATE TABLE "properia"."listing_value_summaries" (
	"listing_id" uuid PRIMARY KEY NOT NULL,
	"benchmark_id" uuid,
	"benchmark_label" text,
	"benchmark_sample_size" integer DEFAULT 0 NOT NULL,
	"benchmark_price_per_m2" numeric(12, 2),
	"benchmark_price_total" numeric(14, 2),
	"price_per_m2" numeric(12, 2),
	"delta_pct" numeric(8, 4),
	"value_score" numeric(5, 2),
	"value_label" text,
	"value_summary_short" text,
	"confidence" text,
	"computed_at" timestamp with time zone,
	"model_version" integer DEFAULT 1 NOT NULL,
	"metadata" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL
);
;
CREATE TABLE "properia"."market_price_benchmarks" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"source" "properia"."market_benchmark_source" NOT NULL,
	"source_label" text,
	"source_dataset_id" text,
	"benchmark_date" date NOT NULL,
	"valid_from" date,
	"valid_to" date,
	"business_type" "properia"."business_type" NOT NULL,
	"property_type" "properia"."property_type" NOT NULL,
	"bedrooms_min" integer,
	"bedrooms_max" integer,
	"granularity" "properia"."market_benchmark_granularity" NOT NULL,
	"country_code" text DEFAULT 'PT' NOT NULL,
	"district" text,
	"municipality" text,
	"parish" text,
	"neighborhood" text,
	"benchmark_label" text NOT NULL,
	"sample_size" integer DEFAULT 0 NOT NULL,
	"median_price_per_m2" numeric(12, 2),
	"avg_price_per_m2" numeric(12, 2),
	"p25_price_per_m2" numeric(12, 2),
	"p75_price_per_m2" numeric(12, 2),
	"median_price_total" numeric(14, 2),
	"avg_price_total" numeric(14, 2),
	"metadata" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"is_active" boolean DEFAULT true NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL
);
;
CREATE TABLE "properia"."reference_rates" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"provider" "properia"."reference_rate_provider" NOT NULL,
	"rate_key" text NOT NULL,
	"rate_label" text NOT NULL,
	"effective_date" date NOT NULL,
	"rate_pct" numeric(8, 4) NOT NULL,
	"currency_code" text DEFAULT 'EUR' NOT NULL,
	"tenor_months" integer,
	"metadata" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"is_active" boolean DEFAULT true NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "reference_rates_provider_key_effective_unique" UNIQUE("provider","rate_key","effective_date")
);
;
CREATE TABLE "properia"."saved_searches" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"user_id" uuid NOT NULL,
	"name" text NOT NULL,
	"params" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"is_active" boolean DEFAULT true NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL
);
;
CREATE TABLE "properia"."user_auth_identities" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"user_id" uuid NOT NULL,
	"provider" "properia"."auth_provider" NOT NULL,
	"provider_user_id" text NOT NULL,
	"email" varchar(320) NOT NULL,
	"email_verified" boolean DEFAULT false NOT NULL,
	"password_hash" text,
	"password_algorithm" "properia"."password_algorithm",
	"last_login_at" timestamp with time zone,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "user_auth_identities_provider_unique" UNIQUE("provider","provider_user_id")
);
;
CREATE TABLE "properia"."user_consent_events" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"user_id" uuid,
	"email_snapshot" varchar(320) NOT NULL,
	"purpose" "properia"."consent_purpose" NOT NULL,
	"granted" boolean NOT NULL,
	"text_version" text NOT NULL,
	"source" text NOT NULL,
	"ip_address" text,
	"user_agent" text,
	"metadata" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL
);
;
CREATE TABLE "properia"."user_data_requests" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"user_id" uuid NOT NULL,
	"email_snapshot" varchar(320) NOT NULL,
	"request_type" "properia"."data_request_type" NOT NULL,
	"status" "properia"."data_request_status" DEFAULT 'requested' NOT NULL,
	"source" text NOT NULL,
	"notes" text,
	"reviewed_at" timestamp with time zone,
	"reviewed_by_user_id" uuid,
	"resolution_notes" text,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL
);
;
CREATE TABLE "properia"."user_sessions" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"user_id" uuid NOT NULL,
	"session_token_hash" text NOT NULL,
	"ip_address" text,
	"user_agent" text,
	"active_advertiser_id" uuid,
	"expires_at" timestamp with time zone NOT NULL,
	"revoked_at" timestamp with time zone,
	"last_seen_at" timestamp with time zone,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "user_sessions_token_hash_unique" UNIQUE("session_token_hash")
);
;
ALTER TABLE "properia"."advertiser_users" ALTER COLUMN "membership_role" SET DATA TYPE "properia"."advertiser_membership_role" USING "membership_role"::"properia"."advertiser_membership_role";;
ALTER TABLE "properia"."advertisers" ADD COLUMN "verification_status" "properia"."advertiser_verification_status" DEFAULT 'unverified' NOT NULL;;
ALTER TABLE "properia"."advertisers" ADD COLUMN "verification_submitted_at" timestamp with time zone;;
ALTER TABLE "properia"."advertisers" ADD COLUMN "verification_reviewed_at" timestamp with time zone;;
ALTER TABLE "properia"."advertisers" ADD COLUMN "verification_reviewed_by_user_id" uuid;;
ALTER TABLE "properia"."advertisers" ADD COLUMN "verification_notes" text;;
ALTER TABLE "properia"."advertisers" ADD COLUMN "suspension_reason" text;;
ALTER TABLE "properia"."app_users" ADD COLUMN "email_verified_at" timestamp with time zone;;
ALTER TABLE "properia"."app_users" ADD COLUMN "phone_verified_at" timestamp with time zone;;
ALTER TABLE "properia"."app_users" ADD COLUMN "last_login_at" timestamp with time zone;;
ALTER TABLE "properia"."app_users" ADD COLUMN "session_version" integer DEFAULT 1 NOT NULL;;
ALTER TABLE "properia"."listings" ADD COLUMN "review_notes" text;;
ALTER TABLE "properia"."listings" ADD COLUMN "suspicious_flags" jsonb DEFAULT '[]'::jsonb NOT NULL;;
ALTER TABLE "properia"."listings" ADD COLUMN "reported_count" integer DEFAULT 0 NOT NULL;;
ALTER TABLE "properia"."listings" ADD COLUMN "reviewed_at" timestamp with time zone;;
ALTER TABLE "properia"."listings" ADD COLUMN "reviewed_by_user_id" uuid;;
ALTER TABLE "properia"."listings" ADD COLUMN "moderation_decision" "properia"."moderation_decision";;
ALTER TABLE "properia"."visits" ADD COLUMN "status_reason" text;;
ALTER TABLE "properia"."advertiser_onboarding" ADD CONSTRAINT "advertiser_onboarding_advertiser_id_advertisers_id_fk" FOREIGN KEY ("advertiser_id") REFERENCES "properia"."advertisers"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."advertiser_onboarding" ADD CONSTRAINT "advertiser_onboarding_owner_user_id_app_users_id_fk" FOREIGN KEY ("owner_user_id") REFERENCES "properia"."app_users"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."advertiser_onboarding" ADD CONSTRAINT "advertiser_onboarding_reviewed_by_user_id_app_users_id_fk" FOREIGN KEY ("reviewed_by_user_id") REFERENCES "properia"."app_users"("id") ON DELETE set null ON UPDATE no action;;
ALTER TABLE "properia"."crm_audit_events" ADD CONSTRAINT "crm_audit_events_advertiser_id_advertisers_id_fk" FOREIGN KEY ("advertiser_id") REFERENCES "properia"."advertisers"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."crm_audit_events" ADD CONSTRAINT "crm_audit_events_actor_user_id_app_users_id_fk" FOREIGN KEY ("actor_user_id") REFERENCES "properia"."app_users"("id") ON DELETE set null ON UPDATE no action;;
ALTER TABLE "properia"."crm_audit_events" ADD CONSTRAINT "crm_audit_events_lead_id_leads_id_fk" FOREIGN KEY ("lead_id") REFERENCES "properia"."leads"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."crm_audit_events" ADD CONSTRAINT "crm_audit_events_visit_id_visits_id_fk" FOREIGN KEY ("visit_id") REFERENCES "properia"."visits"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."listing_affordability_snapshots" ADD CONSTRAINT "listing_affordability_snapshots_listing_id_listings_id_fk" FOREIGN KEY ("listing_id") REFERENCES "properia"."listings"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."listing_affordability_snapshots" ADD CONSTRAINT "listing_affordability_snapshots_reference_rate_id_reference_rates_id_fk" FOREIGN KEY ("reference_rate_id") REFERENCES "properia"."reference_rates"("id") ON DELETE set null ON UPDATE no action;;
ALTER TABLE "properia"."listing_reports" ADD CONSTRAINT "listing_reports_listing_id_listings_id_fk" FOREIGN KEY ("listing_id") REFERENCES "properia"."listings"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."listing_reports" ADD CONSTRAINT "listing_reports_reporter_user_id_app_users_id_fk" FOREIGN KEY ("reporter_user_id") REFERENCES "properia"."app_users"("id") ON DELETE set null ON UPDATE no action;;
ALTER TABLE "properia"."listing_reports" ADD CONSTRAINT "listing_reports_reviewed_by_user_id_app_users_id_fk" FOREIGN KEY ("reviewed_by_user_id") REFERENCES "properia"."app_users"("id") ON DELETE set null ON UPDATE no action;;
ALTER TABLE "properia"."listing_value_summaries" ADD CONSTRAINT "listing_value_summaries_listing_id_listings_id_fk" FOREIGN KEY ("listing_id") REFERENCES "properia"."listings"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."listing_value_summaries" ADD CONSTRAINT "listing_value_summaries_benchmark_id_market_price_benchmarks_id_fk" FOREIGN KEY ("benchmark_id") REFERENCES "properia"."market_price_benchmarks"("id") ON DELETE set null ON UPDATE no action;;
ALTER TABLE "properia"."saved_searches" ADD CONSTRAINT "saved_searches_user_id_app_users_id_fk" FOREIGN KEY ("user_id") REFERENCES "properia"."app_users"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."user_auth_identities" ADD CONSTRAINT "user_auth_identities_user_id_app_users_id_fk" FOREIGN KEY ("user_id") REFERENCES "properia"."app_users"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."user_consent_events" ADD CONSTRAINT "user_consent_events_user_id_app_users_id_fk" FOREIGN KEY ("user_id") REFERENCES "properia"."app_users"("id") ON DELETE set null ON UPDATE no action;;
ALTER TABLE "properia"."user_data_requests" ADD CONSTRAINT "user_data_requests_user_id_app_users_id_fk" FOREIGN KEY ("user_id") REFERENCES "properia"."app_users"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."user_data_requests" ADD CONSTRAINT "user_data_requests_reviewed_by_user_id_app_users_id_fk" FOREIGN KEY ("reviewed_by_user_id") REFERENCES "properia"."app_users"("id") ON DELETE set null ON UPDATE no action;;
ALTER TABLE "properia"."user_sessions" ADD CONSTRAINT "user_sessions_user_id_app_users_id_fk" FOREIGN KEY ("user_id") REFERENCES "properia"."app_users"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."user_sessions" ADD CONSTRAINT "user_sessions_active_advertiser_id_advertisers_id_fk" FOREIGN KEY ("active_advertiser_id") REFERENCES "properia"."advertisers"("id") ON DELETE set null ON UPDATE no action;;
CREATE INDEX "idx_advertiser_onboarding_owner" ON "properia"."advertiser_onboarding" USING btree ("owner_user_id");;
CREATE INDEX "idx_advertiser_onboarding_status" ON "properia"."advertiser_onboarding" USING btree ("status");;
CREATE INDEX "idx_crm_audit_events_advertiser" ON "properia"."crm_audit_events" USING btree ("advertiser_id","created_at");;
CREATE INDEX "idx_crm_audit_events_lead" ON "properia"."crm_audit_events" USING btree ("lead_id","created_at");;
CREATE INDEX "idx_crm_audit_events_visit" ON "properia"."crm_audit_events" USING btree ("visit_id","created_at");;
CREATE INDEX "idx_listing_reports_listing" ON "properia"."listing_reports" USING btree ("listing_id");;
CREATE INDEX "idx_listing_reports_status" ON "properia"."listing_reports" USING btree ("status");;
CREATE INDEX "idx_listing_reports_reporter" ON "properia"."listing_reports" USING btree ("reporter_user_id");;
CREATE INDEX "idx_market_price_benchmarks_segment" ON "properia"."market_price_benchmarks" USING btree ("business_type","property_type","granularity","benchmark_date");;
CREATE INDEX "idx_market_price_benchmarks_location" ON "properia"."market_price_benchmarks" USING btree ("country_code","district","municipality","parish");;
CREATE INDEX "idx_reference_rates_provider_key_active" ON "properia"."reference_rates" USING btree ("provider","rate_key","is_active","effective_date");;
CREATE INDEX "idx_saved_searches_user_id" ON "properia"."saved_searches" USING btree ("user_id","is_active","created_at");;
CREATE INDEX "idx_user_auth_identities_user" ON "properia"."user_auth_identities" USING btree ("user_id");;
CREATE INDEX "idx_user_auth_identities_email" ON "properia"."user_auth_identities" USING btree ("email");;
CREATE INDEX "idx_user_consent_events_user" ON "properia"."user_consent_events" USING btree ("user_id");;
CREATE INDEX "idx_user_consent_events_purpose" ON "properia"."user_consent_events" USING btree ("purpose");;
CREATE INDEX "idx_user_consent_events_created_at" ON "properia"."user_consent_events" USING btree ("created_at");;
CREATE INDEX "idx_user_data_requests_user" ON "properia"."user_data_requests" USING btree ("user_id");;
CREATE INDEX "idx_user_data_requests_type" ON "properia"."user_data_requests" USING btree ("request_type");;
CREATE INDEX "idx_user_data_requests_status" ON "properia"."user_data_requests" USING btree ("status");;
CREATE INDEX "idx_user_sessions_user" ON "properia"."user_sessions" USING btree ("user_id");;
CREATE INDEX "idx_user_sessions_expires_at" ON "properia"."user_sessions" USING btree ("expires_at");;
ALTER TABLE "properia"."advertisers" ADD CONSTRAINT "advertisers_verification_reviewed_by_user_id_app_users_id_fk" FOREIGN KEY ("verification_reviewed_by_user_id") REFERENCES "properia"."app_users"("id") ON DELETE set null ON UPDATE no action;;
ALTER TABLE "properia"."listings" ADD CONSTRAINT "listings_reviewed_by_user_id_app_users_id_fk" FOREIGN KEY ("reviewed_by_user_id") REFERENCES "properia"."app_users"("id") ON DELETE set null ON UPDATE no action;;
CREATE INDEX "idx_advertisers_verification_status" ON "properia"."advertisers" USING btree ("verification_status");;
CREATE INDEX "idx_listings_reported_count" ON "properia"."listings" USING btree ("reported_count");
