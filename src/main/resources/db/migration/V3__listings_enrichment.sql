CREATE TABLE "properia"."saved_listings" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"user_id" uuid NOT NULL,
	"listing_id" uuid NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "saved_listings_user_listing_unique" UNIQUE("user_id","listing_id")
);
;
ALTER TABLE "properia"."saved_listings" ADD CONSTRAINT "saved_listings_user_id_app_users_id_fk" FOREIGN KEY ("user_id") REFERENCES "properia"."app_users"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."saved_listings" ADD CONSTRAINT "saved_listings_listing_id_listings_id_fk" FOREIGN KEY ("listing_id") REFERENCES "properia"."listings"("id") ON DELETE cascade ON UPDATE no action;;
CREATE INDEX "idx_saved_listings_user_id" ON "properia"."saved_listings" USING btree ("user_id","created_at");