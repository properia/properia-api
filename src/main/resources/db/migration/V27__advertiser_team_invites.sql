CREATE TABLE IF NOT EXISTS "properia"."advertiser_team_invites" (
  "id" uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  "advertiser_id" uuid NOT NULL REFERENCES "properia"."advertisers"("id") ON DELETE CASCADE,
  "invited_by_user_id" uuid NOT NULL REFERENCES "properia"."app_users"("id") ON DELETE CASCADE,
  "email" varchar(320) NOT NULL,
  "membership_role" "properia"."advertiser_membership_role" NOT NULL,
  "token" text NOT NULL,
  "accepted_at" timestamp with time zone,
  "accepted_by_user_id" uuid REFERENCES "properia"."app_users"("id") ON DELETE SET NULL,
  "expires_at" timestamp with time zone NOT NULL,
  "created_at" timestamp with time zone NOT NULL DEFAULT now(),
  "updated_at" timestamp with time zone NOT NULL DEFAULT now(),
  CONSTRAINT "advertiser_team_invites_token_unique" UNIQUE ("token")
);

CREATE INDEX IF NOT EXISTS "idx_advertiser_team_invites_advertiser"
  ON "properia"."advertiser_team_invites" ("advertiser_id");

CREATE INDEX IF NOT EXISTS "idx_advertiser_team_invites_email"
  ON "properia"."advertiser_team_invites" ("email");

CREATE INDEX IF NOT EXISTS "idx_advertiser_team_invites_token"
  ON "properia"."advertiser_team_invites" ("token");

-- visits.buyer_user_id was patched in bootstrap-db.ts but never added to a migration
ALTER TABLE "properia"."visits"
  ADD COLUMN IF NOT EXISTS "buyer_user_id" uuid REFERENCES "properia"."app_users"("id") ON DELETE SET NULL;
