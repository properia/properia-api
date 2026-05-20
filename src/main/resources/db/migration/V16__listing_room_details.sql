CREATE TABLE "properia"."listing_room_details" (
  "listing_id" uuid PRIMARY KEY NOT NULL,
  "has_private_bathroom" boolean DEFAULT false NOT NULL,
  "bills_included" boolean DEFAULT false NOT NULL,
  "internet_included" boolean DEFAULT false NOT NULL,
  "has_shared_kitchen" boolean DEFAULT true NOT NULL,
  "total_rooms_in_house" integer,
  "current_occupants" integer,
  "min_stay_months" integer,
  "couple_allowed" boolean DEFAULT true NOT NULL,
  "is_exterior_room" boolean DEFAULT false NOT NULL,
  "house_rules_text" text,
  "created_at" timestamp with time zone DEFAULT now() NOT NULL,
  "updated_at" timestamp with time zone DEFAULT now() NOT NULL
);
;
ALTER TABLE "properia"."listing_room_details"
  ADD CONSTRAINT "listing_room_details_listing_id_listings_id_fk"
  FOREIGN KEY ("listing_id") REFERENCES "properia"."listings"("id") ON DELETE cascade ON UPDATE no action;
