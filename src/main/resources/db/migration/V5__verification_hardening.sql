DO $$
BEGIN
  CREATE TYPE "properia"."professional_registration_type" AS ENUM('ami', 'commercial_reference');
EXCEPTION
  WHEN duplicate_object THEN NULL;
END $$;;

ALTER TABLE "properia"."advertisers"
  ADD COLUMN IF NOT EXISTS "professional_registration_type" "properia"."professional_registration_type",
  ADD COLUMN IF NOT EXISTS "business_address_line_1" text,
  ADD COLUMN IF NOT EXISTS "business_postal_code" text,
  ADD COLUMN IF NOT EXISTS "business_city" text,
  ADD COLUMN IF NOT EXISTS "business_district" text,
  ADD COLUMN IF NOT EXISTS "business_country" varchar(2),
  ADD COLUMN IF NOT EXISTS "tax_number_verified_at" timestamp with time zone,
  ADD COLUMN IF NOT EXISTS "professional_registration_verified_at" timestamp with time zone;;
