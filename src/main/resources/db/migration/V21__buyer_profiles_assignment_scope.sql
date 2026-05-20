DO $$
BEGIN
  CREATE TYPE "properia"."buyer_consent_status" AS ENUM ('pending', 'active', 'revoked', 'expired');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;
;
DO $$
BEGIN
  CREATE TYPE "properia"."buyer_urgency" AS ENUM ('exploring', 'active', 'ready_to_close');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;
;
DO $$
BEGIN
  CREATE TYPE "properia"."buyer_budget_approval" AS ENUM ('none', 'in_progress', 'approved');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;
;
DO $$
BEGIN
  CREATE TYPE "properia"."buyer_situation" AS ENUM ('buyer_only', 'also_selling');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;
;
DO $$
BEGIN
  CREATE TYPE "properia"."buyer_profile_status" AS ENUM ('active', 'paused', 'closed');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;
;
DO $$
BEGIN
  CREATE TYPE "properia"."buyer_close_reason" AS ENUM ('bought_here', 'bought_elsewhere', 'gave_up', 'other');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;
;
DO $$
BEGIN
  CREATE TYPE "properia"."buyer_budget_bracket" AS ENUM (
    'under_100k', '100_150k', '150_200k', '200_250k', '250_300k',
    '300_400k', '400_500k', '500_750k', '750k_1m', 'over_1m'
  );
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;
;
DO $$
BEGIN
  CREATE TYPE "properia"."buyer_match_status" AS ENUM ('new', 'notified', 'shown', 'dismissed');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;
;
CREATE TABLE IF NOT EXISTS "properia"."buyer_profiles" (
  "id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
  "advertiser_id" uuid NOT NULL REFERENCES "properia"."advertisers"("id") ON DELETE CASCADE,
  "assigned_to_user_id" uuid REFERENCES "properia"."app_users"("id") ON DELETE SET NULL,
  "name" text NOT NULL,
  "email" varchar(320),
  "phone" varchar(30),
  "consent_status" "properia"."buyer_consent_status" DEFAULT 'pending' NOT NULL,
  "consent_token" uuid DEFAULT gen_random_uuid() NOT NULL,
  "consent_accepted_at" timestamp with time zone,
  "consent_expires_at" timestamp with time zone,
  "consent_ip_address" varchar(45),
  "consent_text_version" varchar(20) DEFAULT '1.0',
  "criteria" jsonb DEFAULT '{}'::jsonb NOT NULL,
  "urgency" "properia"."buyer_urgency" DEFAULT 'exploring' NOT NULL,
  "budget_bracket" "properia"."buyer_budget_bracket",
  "budget_approval" "properia"."buyer_budget_approval" DEFAULT 'none' NOT NULL,
  "situation" "properia"."buyer_situation" DEFAULT 'buyer_only' NOT NULL,
  "status" "properia"."buyer_profile_status" DEFAULT 'active' NOT NULL,
  "close_reason" "properia"."buyer_close_reason",
  "last_contacted_at" timestamp with time zone,
  "next_follow_up_at" timestamp with time zone,
  "internal_notes" text,
  "created_at" timestamp with time zone DEFAULT now() NOT NULL,
  "updated_at" timestamp with time zone DEFAULT now() NOT NULL,
  CONSTRAINT "buyer_profiles_consent_token_unique" UNIQUE ("consent_token")
);
;
CREATE INDEX IF NOT EXISTS "idx_buyer_profiles_advertiser_status"
  ON "properia"."buyer_profiles" ("advertiser_id", "status", "created_at");
;
CREATE INDEX IF NOT EXISTS "idx_buyer_profiles_consent_token"
  ON "properia"."buyer_profiles" ("consent_token");
;
CREATE TABLE IF NOT EXISTS "properia"."buyer_listing_matches" (
  "id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
  "buyer_profile_id" uuid NOT NULL REFERENCES "properia"."buyer_profiles"("id") ON DELETE CASCADE,
  "listing_id" uuid NOT NULL REFERENCES "properia"."listings"("id") ON DELETE CASCADE,
  "advertiser_id" uuid NOT NULL REFERENCES "properia"."advertisers"("id") ON DELETE CASCADE,
  "match_score" integer NOT NULL,
  "matched_criteria" jsonb DEFAULT '[]'::jsonb NOT NULL,
  "status" "properia"."buyer_match_status" DEFAULT 'new' NOT NULL,
  "created_at" timestamp with time zone DEFAULT now() NOT NULL,
  "updated_at" timestamp with time zone DEFAULT now() NOT NULL,
  CONSTRAINT "uq_buyer_listing_match" UNIQUE ("buyer_profile_id", "listing_id")
);
;
CREATE INDEX IF NOT EXISTS "idx_buyer_listing_matches_profile"
  ON "properia"."buyer_listing_matches" ("buyer_profile_id", "status");
;
CREATE INDEX IF NOT EXISTS "idx_buyer_listing_matches_advertiser"
  ON "properia"."buyer_listing_matches" ("advertiser_id", "status", "created_at");
;
ALTER TABLE "properia"."buyer_profiles"
  ADD COLUMN IF NOT EXISTS "assigned_to_user_id" uuid REFERENCES "properia"."app_users"("id") ON DELETE SET NULL;
;

UPDATE "properia"."buyer_profiles" bp
SET "assigned_to_user_id" = ao."owner_user_id"
FROM "properia"."advertiser_onboarding" ao
WHERE ao."advertiser_id" = bp."advertiser_id"
  AND bp."assigned_to_user_id" IS NULL
  AND ao."owner_user_id" IS NOT NULL;
;

CREATE INDEX IF NOT EXISTS "idx_buyer_profiles_assigned_to"
  ON "properia"."buyer_profiles" ("assigned_to_user_id", "status", "created_at");
