ALTER TABLE "properia"."visits"
ADD COLUMN IF NOT EXISTS "outcome" text;

ALTER TABLE "properia"."visits"
ADD COLUMN IF NOT EXISTS "outcome_notes" text;
