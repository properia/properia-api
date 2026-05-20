DO $$ BEGIN CREATE TYPE "properia"."moderation_target_type" AS ENUM('listing', 'advertiser'); EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE TYPE "properia"."moderation_reason_category" AS ENUM('duplicado', 'fraude', 'conteudo_incorreto', 'conteudo_proibido', 'spam', 'outro', 'documentacao', 'identidade_nao_verificada', 'violacao_termos', 'qualidade_insuficiente'); EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN CREATE TYPE "properia"."moderation_decision_source" AS ENUM('manual', 'assisted'); EXCEPTION WHEN duplicate_object THEN NULL; END $$;

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
);;

DO $$ BEGIN
  ALTER TABLE "properia"."moderation_decisions"
  ADD CONSTRAINT "moderation_decisions_listing_id_listings_id_fk"
  FOREIGN KEY ("listing_id") REFERENCES "properia"."listings"("id") ON DELETE cascade ON UPDATE no action;
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;
DO $$ BEGIN
  ALTER TABLE "properia"."moderation_decisions"
  ADD CONSTRAINT "moderation_decisions_advertiser_id_advertisers_id_fk"
  FOREIGN KEY ("advertiser_id") REFERENCES "properia"."advertisers"("id") ON DELETE cascade ON UPDATE no action;
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;
DO $$ BEGIN
  ALTER TABLE "properia"."moderation_decisions"
  ADD CONSTRAINT "moderation_decisions_actor_user_id_app_users_id_fk"
  FOREIGN KEY ("actor_user_id") REFERENCES "properia"."app_users"("id") ON DELETE set null ON UPDATE no action;
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

CREATE INDEX IF NOT EXISTS "idx_moderation_decisions_target"
  ON "properia"."moderation_decisions" USING btree ("target_type","target_id","created_at");;
CREATE INDEX IF NOT EXISTS "idx_moderation_decisions_listing"
  ON "properia"."moderation_decisions" USING btree ("listing_id","created_at");;
CREATE INDEX IF NOT EXISTS "idx_moderation_decisions_advertiser"
  ON "properia"."moderation_decisions" USING btree ("advertiser_id","created_at");;
CREATE INDEX IF NOT EXISTS "idx_moderation_decisions_actor"
  ON "properia"."moderation_decisions" USING btree ("actor_user_id","created_at");;
