-- Auth action tokens (email verification, password reset, email change)
CREATE TABLE IF NOT EXISTS "properia"."auth_action_tokens" (
    "id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
    "user_id" uuid REFERENCES "properia"."app_users"("id") ON DELETE CASCADE,
    "email" varchar(320) NOT NULL,
    "purpose" text NOT NULL,
    "token_hash" text NOT NULL,
    "expires_at" timestamptz NOT NULL,
    "consumed_at" timestamptz,
    "new_email" varchar(320),
    "metadata" jsonb NOT NULL DEFAULT '{}'::jsonb,
    "created_at" timestamptz NOT NULL DEFAULT now(),
    "updated_at" timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS "auth_action_tokens_token_hash_unique"
    ON "properia"."auth_action_tokens" ("token_hash");

CREATE INDEX IF NOT EXISTS "idx_auth_action_tokens_user_purpose"
    ON "properia"."auth_action_tokens" ("user_id", "purpose", "expires_at");

CREATE INDEX IF NOT EXISTS "idx_auth_action_tokens_email_purpose"
    ON "properia"."auth_action_tokens" ("email", "purpose", "expires_at");

CREATE INDEX IF NOT EXISTS "idx_auth_action_tokens_expires"
    ON "properia"."auth_action_tokens" ("expires_at");
