ALTER TABLE "properia"."visits"
  ADD COLUMN IF NOT EXISTS "buyer_confirmed_at" timestamp with time zone,
  ADD COLUMN IF NOT EXISTS "buyer_confirmation_requested_at" timestamp with time zone;

CREATE TABLE IF NOT EXISTS "properia"."visit_email_verifications" (
  "id" uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  "user_id" uuid NOT NULL REFERENCES "properia"."app_users"("id") ON DELETE cascade,
  "email" varchar(320) NOT NULL,
  "code_hash" text NOT NULL,
  "expires_at" timestamp with time zone NOT NULL,
  "consumed_at" timestamp with time zone,
  "last_sent_at" timestamp with time zone NOT NULL,
  "failed_attempts" integer NOT NULL DEFAULT 0,
  "created_at" timestamp with time zone NOT NULL DEFAULT now(),
  "updated_at" timestamp with time zone NOT NULL DEFAULT now(),
  CONSTRAINT "visit_email_verifications_user_unique" UNIQUE ("user_id")
);

CREATE INDEX IF NOT EXISTS "idx_visit_email_verifications_expires"
  ON "properia"."visit_email_verifications" ("expires_at");
