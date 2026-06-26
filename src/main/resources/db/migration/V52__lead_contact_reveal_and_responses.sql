-- Corrige dois endpoints que falhavam com 500 por falta de schema:
--   1. POST /api/advertiser/leads/{id}/reveal  → usa leads.contact_revealed_at (inexistente)
--   2. POST /api/advertiser/leads/{id}/responses → INSERT em lead_responses (inexistente)

-- ── 1. Revelar contacto do lead ─────────────────────────────────────────────
ALTER TABLE "properia"."leads"
  ADD COLUMN IF NOT EXISTS "contact_revealed_at" timestamp with time zone;

-- ── 2. Registo de respostas comerciais a um lead ────────────────────────────
CREATE TABLE IF NOT EXISTS "properia"."lead_responses" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"lead_id" uuid NOT NULL,
	"advertiser_id" uuid NOT NULL,
	"actor_user_id" uuid,
	"response_type" text NOT NULL DEFAULT 'call',
	"note" text,
	"outcome" text,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE "properia"."lead_responses"
  ADD CONSTRAINT "lead_responses_lead_id_leads_id_fk"
  FOREIGN KEY ("lead_id") REFERENCES "properia"."leads"("id")
  ON DELETE cascade ON UPDATE no action;

ALTER TABLE "properia"."lead_responses"
  ADD CONSTRAINT "lead_responses_advertiser_id_advertisers_id_fk"
  FOREIGN KEY ("advertiser_id") REFERENCES "properia"."advertisers"("id")
  ON DELETE cascade ON UPDATE no action;

ALTER TABLE "properia"."lead_responses"
  ADD CONSTRAINT "lead_responses_actor_user_id_app_users_id_fk"
  FOREIGN KEY ("actor_user_id") REFERENCES "properia"."app_users"("id")
  ON DELETE set null ON UPDATE no action;

CREATE INDEX IF NOT EXISTS "idx_lead_responses_lead_created"
  ON "properia"."lead_responses" USING btree ("lead_id", "created_at");
CREATE INDEX IF NOT EXISTS "idx_lead_responses_advertiser_created"
  ON "properia"."lead_responses" USING btree ("advertiser_id", "created_at");
