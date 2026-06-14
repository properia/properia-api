-- Create advertiser_team_members table if not exists
CREATE TABLE IF NOT EXISTS "properia"."advertiser_team_members" (
    "id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
    "advertiser_id" uuid NOT NULL REFERENCES "properia"."advertisers" ("id") ON DELETE CASCADE,
    "user_id" uuid NOT NULL REFERENCES "properia"."app_users" ("id") ON DELETE CASCADE,
    "role" text NOT NULL DEFAULT 'editor',
    "is_owner" boolean DEFAULT false NOT NULL,
    "created_at" timestamp with time zone DEFAULT now() NOT NULL,
    UNIQUE("advertiser_id", "user_id")
);

-- Create index for faster lookups
CREATE INDEX IF NOT EXISTS idx_advertiser_team_members_advertiser_id
  ON "properia"."advertiser_team_members" ("advertiser_id");

CREATE INDEX IF NOT EXISTS idx_advertiser_team_members_user_id
  ON "properia"."advertiser_team_members" ("user_id");
