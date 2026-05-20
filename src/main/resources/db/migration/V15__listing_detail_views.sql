CREATE TABLE "properia"."listing_detail_views" (
  "id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
  "listing_id" uuid NOT NULL,
  "user_id" uuid,
  "session_key" text NOT NULL,
  "created_at" timestamp with time zone DEFAULT now() NOT NULL,
  "updated_at" timestamp with time zone DEFAULT now() NOT NULL
);
;
ALTER TABLE "properia"."listing_detail_views"
  ADD CONSTRAINT "listing_detail_views_listing_id_listings_id_fk"
  FOREIGN KEY ("listing_id") REFERENCES "properia"."listings"("id") ON DELETE cascade ON UPDATE no action;
;
ALTER TABLE "properia"."listing_detail_views"
  ADD CONSTRAINT "listing_detail_views_user_id_app_users_id_fk"
  FOREIGN KEY ("user_id") REFERENCES "properia"."app_users"("id") ON DELETE set null ON UPDATE no action;
;
CREATE UNIQUE INDEX "listing_detail_views_listing_session_unique"
  ON "properia"."listing_detail_views" USING btree ("listing_id","session_key");
;
CREATE INDEX "idx_listing_detail_views_listing_created"
  ON "properia"."listing_detail_views" USING btree ("listing_id","created_at");
