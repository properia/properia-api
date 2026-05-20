DO $$
BEGIN
  CREATE TYPE "properia"."chat_conversation_status" AS ENUM('active', 'closed', 'blocked');
EXCEPTION
  WHEN duplicate_object THEN NULL;
END $$;;

DO $$
BEGIN
  CREATE TYPE "properia"."chat_participant_role" AS ENUM('buyer', 'advertiser_member');
EXCEPTION
  WHEN duplicate_object THEN NULL;
END $$;;

DO $$
BEGIN
  CREATE TYPE "properia"."chat_message_sender_type" AS ENUM('buyer', 'advertiser_member', 'system');
EXCEPTION
  WHEN duplicate_object THEN NULL;
END $$;;

DO $$
BEGIN
  CREATE TYPE "properia"."chat_message_type" AS ENUM('text', 'system');
EXCEPTION
  WHEN duplicate_object THEN NULL;
END $$;;

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
  "updated_at" timestamp with time zone DEFAULT now() NOT NULL
);;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conname = 'chat_conversations_advertiser_id_fkey'
  ) THEN
    ALTER TABLE "properia"."chat_conversations"
      ADD CONSTRAINT "chat_conversations_advertiser_id_fkey"
      FOREIGN KEY ("advertiser_id") REFERENCES "properia"."advertisers"("id") ON DELETE CASCADE;
  END IF;
END $$;;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conname = 'chat_conversations_listing_id_fkey'
  ) THEN
    ALTER TABLE "properia"."chat_conversations"
      ADD CONSTRAINT "chat_conversations_listing_id_fkey"
      FOREIGN KEY ("listing_id") REFERENCES "properia"."listings"("id") ON DELETE CASCADE;
  END IF;
END $$;;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conname = 'chat_conversations_lead_id_fkey'
  ) THEN
    ALTER TABLE "properia"."chat_conversations"
      ADD CONSTRAINT "chat_conversations_lead_id_fkey"
      FOREIGN KEY ("lead_id") REFERENCES "properia"."leads"("id") ON DELETE SET NULL;
  END IF;
END $$;;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conname = 'chat_conversations_buyer_user_id_fkey'
  ) THEN
    ALTER TABLE "properia"."chat_conversations"
      ADD CONSTRAINT "chat_conversations_buyer_user_id_fkey"
      FOREIGN KEY ("buyer_user_id") REFERENCES "properia"."app_users"("id") ON DELETE CASCADE;
  END IF;
END $$;;

CREATE UNIQUE INDEX IF NOT EXISTS "chat_conversations_advertiser_listing_buyer_unique"
  ON "properia"."chat_conversations" ("advertiser_id", "listing_id", "buyer_user_id");;

CREATE INDEX IF NOT EXISTS "idx_chat_conversations_buyer_updated"
  ON "properia"."chat_conversations" ("buyer_user_id", "updated_at");;

CREATE INDEX IF NOT EXISTS "idx_chat_conversations_advertiser_updated"
  ON "properia"."chat_conversations" ("advertiser_id", "updated_at");;

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
  CONSTRAINT "chat_participants_role_subject_check" CHECK (
    (
      "role" = 'buyer' AND "user_id" IS NOT NULL AND "advertiser_id" IS NULL
    ) OR (
      "role" = 'advertiser_member' AND "advertiser_id" IS NOT NULL
    )
  )
);;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conname = 'chat_participants_conversation_id_fkey'
  ) THEN
    ALTER TABLE "properia"."chat_participants"
      ADD CONSTRAINT "chat_participants_conversation_id_fkey"
      FOREIGN KEY ("conversation_id") REFERENCES "properia"."chat_conversations"("id") ON DELETE CASCADE;
  END IF;
END $$;;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conname = 'chat_participants_advertiser_id_fkey'
  ) THEN
    ALTER TABLE "properia"."chat_participants"
      ADD CONSTRAINT "chat_participants_advertiser_id_fkey"
      FOREIGN KEY ("advertiser_id") REFERENCES "properia"."advertisers"("id") ON DELETE CASCADE;
  END IF;
END $$;;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conname = 'chat_participants_user_id_fkey'
  ) THEN
    ALTER TABLE "properia"."chat_participants"
      ADD CONSTRAINT "chat_participants_user_id_fkey"
      FOREIGN KEY ("user_id") REFERENCES "properia"."app_users"("id") ON DELETE CASCADE;
  END IF;
END $$;;

CREATE UNIQUE INDEX IF NOT EXISTS "chat_participants_conversation_role_unique"
  ON "properia"."chat_participants" ("conversation_id", "role");;

CREATE INDEX IF NOT EXISTS "idx_chat_participants_user"
  ON "properia"."chat_participants" ("user_id", "updated_at");;

CREATE INDEX IF NOT EXISTS "idx_chat_participants_advertiser"
  ON "properia"."chat_participants" ("advertiser_id", "updated_at");;

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
);;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conname = 'chat_messages_conversation_id_fkey'
  ) THEN
    ALTER TABLE "properia"."chat_messages"
      ADD CONSTRAINT "chat_messages_conversation_id_fkey"
      FOREIGN KEY ("conversation_id") REFERENCES "properia"."chat_conversations"("id") ON DELETE CASCADE;
  END IF;
END $$;;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conname = 'chat_messages_advertiser_id_fkey'
  ) THEN
    ALTER TABLE "properia"."chat_messages"
      ADD CONSTRAINT "chat_messages_advertiser_id_fkey"
      FOREIGN KEY ("advertiser_id") REFERENCES "properia"."advertisers"("id") ON DELETE CASCADE;
  END IF;
END $$;;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conname = 'chat_messages_listing_id_fkey'
  ) THEN
    ALTER TABLE "properia"."chat_messages"
      ADD CONSTRAINT "chat_messages_listing_id_fkey"
      FOREIGN KEY ("listing_id") REFERENCES "properia"."listings"("id") ON DELETE CASCADE;
  END IF;
END $$;;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conname = 'chat_messages_lead_id_fkey'
  ) THEN
    ALTER TABLE "properia"."chat_messages"
      ADD CONSTRAINT "chat_messages_lead_id_fkey"
      FOREIGN KEY ("lead_id") REFERENCES "properia"."leads"("id") ON DELETE SET NULL;
  END IF;
END $$;;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conname = 'chat_messages_sender_user_id_fkey'
  ) THEN
    ALTER TABLE "properia"."chat_messages"
      ADD CONSTRAINT "chat_messages_sender_user_id_fkey"
      FOREIGN KEY ("sender_user_id") REFERENCES "properia"."app_users"("id") ON DELETE SET NULL;
  END IF;
END $$;;

CREATE INDEX IF NOT EXISTS "idx_chat_messages_conversation_created"
  ON "properia"."chat_messages" ("conversation_id", "created_at");;

CREATE INDEX IF NOT EXISTS "idx_chat_messages_advertiser_created"
  ON "properia"."chat_messages" ("advertiser_id", "created_at");;
