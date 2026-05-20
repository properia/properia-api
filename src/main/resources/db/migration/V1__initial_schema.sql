CREATE SCHEMA IF NOT EXISTS "properia";
;
CREATE TYPE "properia"."advertiser_type" AS ENUM('private_owner', 'consultant', 'agency', 'promoter', 'developer', 'bank_asset_manager');;
CREATE TYPE "properia"."business_type" AS ENUM('sale', 'rent', 'holiday_rent', 'transfer');;
CREATE TYPE "properia"."condition_status" AS ENUM('new', 'remodeled', 'used_good', 'used_regular', 'to_renovate', 'shell_core', 'under_construction');;
CREATE TYPE "properia"."furnished_status" AS ENUM('furnished', 'semi_furnished', 'unfurnished');;
CREATE TYPE "properia"."intent_type" AS ENUM('buy', 'rent', 'invest', 'valuation', 'credit', 'insurance');;
CREATE TYPE "properia"."job_status" AS ENUM('queued', 'running', 'completed', 'failed', 'cancelled');;
CREATE TYPE "properia"."lead_source" AS ENUM('listing_detail', 'listing_card', 'contact_request', 'visit_request', 'chat', 'partner_form', 'manual');;
CREATE TYPE "properia"."lead_stage" AS ENUM('new', 'qualified', 'contacted', 'visit_scheduled', 'proposal', 'won', 'lost');;
CREATE TYPE "properia"."listing_source_type" AS ENUM('manual', 'import_feed', 'crm_sync', 'portal_sync', 'api');;
CREATE TYPE "properia"."listing_status" AS ENUM('draft', 'pending_review', 'published', 'paused', 'sold', 'rented', 'archived');;
CREATE TYPE "properia"."location_precision" AS ENUM('exact', 'street', 'neighborhood', 'parish', 'municipality');;
CREATE TYPE "properia"."media_source_type" AS ENUM('upload', 'external', 'youtube');;
CREATE TYPE "properia"."media_type" AS ENUM('image', 'floorplan', 'video', 'virtual_tour', 'youtube');;
CREATE TYPE "properia"."price_period" AS ENUM('sale', 'month', 'week', 'day');;
CREATE TYPE "properia"."profile_score_grade" AS ENUM('excellent', 'good', 'fair', 'weak');;
CREATE TYPE "properia"."profile_type" AS ENUM('family', 'senior', 'investment', 'home_office', 'young_professional', 'urban_life', 'family_routine', 'remote_work', 'space_privacy', 'second_home', 'custom');;
CREATE TYPE "properia"."property_type" AS ENUM('apartment', 'house', 'studio', 'penthouse', 'duplex', 'loft', 'townhouse', 'semi_detached_house', 'villa', 'room', 'land', 'commercial', 'office', 'shop', 'warehouse', 'industrial', 'garage', 'farm', 'hotel', 'building');;
CREATE TYPE "properia"."room_hint" AS ENUM('kitchen', 'living_room', 'bedroom', 'bathroom', 'facade', 'exterior', 'terrace', 'floorplan', 'other');;
CREATE TYPE "properia"."user_role" AS ENUM('buyer', 'seller', 'agent', 'agency_admin', 'promoter', 'staff', 'partner_admin', 'platform_admin');;
CREATE TYPE "properia"."visibility_status" AS ENUM('organic', 'featured', 'sponsored', 'boosted');;
CREATE TYPE "properia"."visit_mode" AS ENUM('onsite', 'online');;
CREATE TYPE "properia"."visit_status" AS ENUM('requested', 'confirmed', 'completed', 'cancelled', 'no_show');;
CREATE TABLE "properia"."advertiser_users" (
	"advertiser_id" uuid NOT NULL,
	"user_id" uuid NOT NULL,
	"membership_role" text NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "advertiser_users_advertiser_id_user_id_pk" PRIMARY KEY("advertiser_id","user_id")
);
;
CREATE TABLE "properia"."advertisers" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"advertiser_type" "properia"."advertiser_type" NOT NULL,
	"legal_name" text NOT NULL,
	"brand_name" text,
	"slug" text,
	"tax_number" text,
	"license_number" text,
	"email" varchar(320),
	"phone" text,
	"website_url" text,
	"logo_url" text,
	"plan_code" text,
	"billing_metadata" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"settings" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"is_active" boolean DEFAULT true NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "advertisers_slug_unique" UNIQUE("slug")
);
;
CREATE TABLE "properia"."app_users" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"email" varchar(320) NOT NULL,
	"phone" text,
	"full_name" text NOT NULL,
	"role" "properia"."user_role" DEFAULT 'buyer' NOT NULL,
	"avatar_url" text,
	"locale" text DEFAULT 'pt-PT' NOT NULL,
	"is_active" boolean DEFAULT true NOT NULL,
	"preferences" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"consents" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "app_users_email_unique" UNIQUE("email")
);
;
CREATE TABLE "properia"."job_executions" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"job_type" text NOT NULL,
	"entity_type" text NOT NULL,
	"entity_id" uuid NOT NULL,
	"status" "properia"."job_status" DEFAULT 'queued' NOT NULL,
	"attempts" integer DEFAULT 0 NOT NULL,
	"payload_hash" text,
	"payload" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"error_message" text,
	"started_at" timestamp with time zone,
	"finished_at" timestamp with time zone,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL
);
;
CREATE TABLE "properia"."leads" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"listing_id" uuid NOT NULL,
	"user_id" uuid,
	"advertiser_id" uuid NOT NULL,
	"source" "properia"."lead_source" NOT NULL,
	"stage" "properia"."lead_stage" DEFAULT 'new' NOT NULL,
	"intent_type" "properia"."intent_type" NOT NULL,
	"message" text,
	"contact_name" text,
	"contact_email" varchar(320),
	"contact_phone" text,
	"score" numeric(5, 2),
	"assigned_to" uuid,
	"metadata" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL
);
;
CREATE TABLE "properia"."listing_ai_summaries" (
	"listing_id" uuid PRIMARY KEY NOT NULL,
	"summary_card" text,
	"summary_detail" text,
	"lifestyle_summary" text,
	"zone_summary" text,
	"buyer_fit_summary" text,
	"admin_internal_summary" text,
	"seo_meta_title" text,
	"seo_meta_description" text,
	"generated_at" timestamp with time zone,
	"prompt_version" integer DEFAULT 1 NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL
);
;
CREATE TABLE "properia"."listing_ai_vision" (
	"listing_id" uuid PRIMARY KEY NOT NULL,
	"version" integer DEFAULT 1 NOT NULL,
	"provider" text NOT NULL,
	"model" text NOT NULL,
	"processed_at" timestamp with time zone,
	"styles_detected" jsonb DEFAULT '[]'::jsonb NOT NULL,
	"style_primary" text,
	"style_secondary" text,
	"condition_ai" "properia"."condition_status",
	"condition_confidence" numeric(5, 2),
	"quality_score" numeric(5, 2),
	"furniture_detected" jsonb DEFAULT '[]'::jsonb NOT NULL,
	"rooms_detected" jsonb DEFAULT '[]'::jsonb NOT NULL,
	"materials_detected" jsonb DEFAULT '[]'::jsonb NOT NULL,
	"signals_detected" jsonb DEFAULT '[]'::jsonb NOT NULL,
	"light_quality_score" numeric(5, 2),
	"spaciousness_score" numeric(5, 2),
	"layout_quality_score" numeric(5, 2),
	"premium_score" numeric(5, 2),
	"family_friendly_score" numeric(5, 2),
	"home_office_score" numeric(5, 2),
	"luxury_score" numeric(5, 2),
	"needs_human_review" boolean DEFAULT false NOT NULL,
	"raw_response" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL
);
;
CREATE TABLE "properia"."listing_audit" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"listing_id" uuid NOT NULL,
	"event_type" text NOT NULL,
	"changed_by" uuid,
	"change_source" text NOT NULL,
	"payload_before" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"payload_after" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL
);
;
CREATE TABLE "properia"."listing_commercial" (
	"listing_id" uuid PRIMARY KEY NOT NULL,
	"exclusive_listing" boolean DEFAULT false NOT NULL,
	"open_house_available" boolean DEFAULT false NOT NULL,
	"online_visit_available" boolean DEFAULT false NOT NULL,
	"visit_booking_enabled" boolean DEFAULT true NOT NULL,
	"youtube_tour_url" text,
	"virtual_tour_url" text,
	"floorplan_url" text,
	"brochure_url" text,
	"lead_response_time_avg_minutes" integer,
	"contact_channel_preferences" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"show_phone" boolean DEFAULT true NOT NULL,
	"show_whatsapp" boolean DEFAULT false NOT NULL,
	"show_chat" boolean DEFAULT true NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL
);
;
CREATE TABLE "properia"."listing_dimensions" (
	"listing_id" uuid PRIMARY KEY NOT NULL,
	"usable_area_m2" numeric(10, 2),
	"gross_area_m2" numeric(10, 2),
	"private_area_m2" numeric(10, 2),
	"construction_area_m2" numeric(10, 2),
	"lot_area_m2" numeric(10, 2),
	"balcony_area_m2" numeric(10, 2),
	"terrace_area_m2" numeric(10, 2),
	"garden_area_m2" numeric(10, 2),
	"storage_area_m2" numeric(10, 2),
	"ceiling_height_m" numeric(6, 2),
	"rooms_total" integer,
	"bedrooms" integer,
	"bathrooms" numeric(4, 1),
	"suites" integer,
	"living_rooms" integer,
	"office_rooms" integer,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL
);
;
CREATE TABLE "properia"."listing_energy" (
	"listing_id" uuid PRIMARY KEY NOT NULL,
	"energy_certificate_rating" text,
	"energy_certificate_number" text,
	"energy_certificate_valid_until" date,
	"heating_type" text,
	"cooling_type" text,
	"water_heating_type" text,
	"energy_efficiency_notes" text,
	"window_type" text,
	"insulation_type" text,
	"solar_exposure_score" numeric(5, 2),
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL
);
;
CREATE TABLE "properia"."listing_features" (
	"listing_id" uuid PRIMARY KEY NOT NULL,
	"feature_flags" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"feature_tags" jsonb DEFAULT '[]'::jsonb NOT NULL,
	"view_tags" jsonb DEFAULT '[]'::jsonb NOT NULL,
	"lifestyle_tags" jsonb DEFAULT '[]'::jsonb NOT NULL,
	"accessibility_tags" jsonb DEFAULT '[]'::jsonb NOT NULL,
	"premium_signals" jsonb DEFAULT '[]'::jsonb NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL
);
;
CREATE TABLE "properia"."listing_location" (
	"listing_id" uuid PRIMARY KEY NOT NULL,
	"country" text,
	"country_code" text DEFAULT 'PT' NOT NULL,
	"district" text,
	"district_code" text,
	"municipality" text,
	"municipality_code" text,
	"city" text,
	"parish" text,
	"parish_code" text,
	"neighborhood" text,
	"street" text,
	"street_number" text,
	"postal_code" text,
	"postal_code_suffix" text,
	"full_address" text,
	"display_address" text,
	"latitude" double precision,
	"longitude" double precision,
	"geohash" text,
	"geocoding_provider" text,
	"geocoding_confidence" numeric(5, 2),
	"location_precision" "properia"."location_precision",
	"hide_exact_location" boolean DEFAULT false NOT NULL,
	"map_visibility_radius_m" integer,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL
);
;
CREATE TABLE "properia"."listing_media" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"listing_id" uuid NOT NULL,
	"media_type" "properia"."media_type" NOT NULL,
	"source_type" "properia"."media_source_type" NOT NULL,
	"url" text NOT NULL,
	"thumbnail_url" text,
	"storage_key" text,
	"mime_type" text,
	"width" integer,
	"height" integer,
	"duration_seconds" integer,
	"sort_order" integer DEFAULT 0 NOT NULL,
	"is_cover" boolean DEFAULT false NOT NULL,
	"caption" text,
	"room_hint" "properia"."room_hint" DEFAULT 'other' NOT NULL,
	"is_processed_by_ai" boolean DEFAULT false NOT NULL,
	"checksum" text,
	"metadata" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL
);
;
CREATE TABLE "properia"."listing_poi_snapshots" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"listing_id" uuid NOT NULL,
	"radius_m" integer DEFAULT 700 NOT NULL,
	"source" text DEFAULT 'overpass' NOT NULL,
	"snapshot_version" integer DEFAULT 1 NOT NULL,
	"processed_at" timestamp with time zone DEFAULT now() NOT NULL,
	"schools_count" integer DEFAULT 0 NOT NULL,
	"health_count" integer DEFAULT 0 NOT NULL,
	"transport_count" integer DEFAULT 0 NOT NULL,
	"supermarket_count" integer DEFAULT 0 NOT NULL,
	"restaurant_count" integer DEFAULT 0 NOT NULL,
	"cafe_count" integer DEFAULT 0 NOT NULL,
	"park_count" integer DEFAULT 0 NOT NULL,
	"pharmacy_count" integer DEFAULT 0 NOT NULL,
	"bank_count" integer DEFAULT 0 NOT NULL,
	"gym_count" integer DEFAULT 0 NOT NULL,
	"beach_count" integer DEFAULT 0 NOT NULL,
	"culture_count" integer DEFAULT 0 NOT NULL,
	"safety_count" integer DEFAULT 0 NOT NULL,
	"nearest_school_m" integer,
	"nearest_health_m" integer,
	"nearest_transport_m" integer,
	"nearest_supermarket_m" integer,
	"nearest_restaurant_m" integer,
	"nearest_cafe_m" integer,
	"nearest_park_m" integer,
	"nearest_pharmacy_m" integer,
	"nearest_bank_m" integer,
	"nearest_gym_m" integer,
	"categories_summary" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"nearest_pois" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"poi_density" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"raw_overpass" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL
);
;
CREATE TABLE "properia"."listing_pricing" (
	"listing_id" uuid PRIMARY KEY NOT NULL,
	"list_price" numeric(14, 2),
	"rental_price" numeric(14, 2),
	"price_currency" text DEFAULT 'EUR' NOT NULL,
	"price_period" "properia"."price_period" DEFAULT 'sale' NOT NULL,
	"condo_fee" numeric(12, 2),
	"property_tax_annual" numeric(12, 2),
	"municipal_tax_estimate" numeric(12, 2),
	"maintenance_cost_estimate" numeric(12, 2),
	"price_per_m2" numeric(12, 2),
	"negotiable" boolean DEFAULT false NOT NULL,
	"accepts_exchange" boolean DEFAULT false NOT NULL,
	"accepts_financing" boolean DEFAULT false NOT NULL,
	"deposit_required" numeric(12, 2),
	"broker_commission_percentage" numeric(5, 2),
	"market_position_score" numeric(5, 2),
	"price_segment" text,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL
);
;
CREATE TABLE "properia"."listing_profile_scores" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"listing_id" uuid NOT NULL,
	"profile_id" uuid NOT NULL,
	"profile_type" "properia"."profile_type" NOT NULL,
	"score" numeric(5, 2) NOT NULL,
	"grade" "properia"."profile_score_grade" NOT NULL,
	"top_strengths" jsonb DEFAULT '[]'::jsonb NOT NULL,
	"top_weaknesses" jsonb DEFAULT '[]'::jsonb NOT NULL,
	"score_breakdown" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"explanation_short" text,
	"explanation_long" text,
	"computed_at" timestamp with time zone DEFAULT now() NOT NULL,
	"rules_version" integer DEFAULT 1 NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "listing_profile_scores_listing_profile_unique" UNIQUE("listing_id","profile_id")
);
;
CREATE TABLE "properia"."listing_visibility" (
	"listing_id" uuid PRIMARY KEY NOT NULL,
	"visibility_status" "properia"."visibility_status" DEFAULT 'organic' NOT NULL,
	"is_featured" boolean DEFAULT false NOT NULL,
	"is_sponsored" boolean DEFAULT false NOT NULL,
	"is_boosted" boolean DEFAULT false NOT NULL,
	"boost_starts_at" timestamp with time zone,
	"boost_ends_at" timestamp with time zone,
	"ranking_multiplier" numeric(6, 3),
	"quality_rank_score" numeric(5, 2),
	"freshness_score" numeric(5, 2),
	"engagement_score" numeric(5, 2),
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL
);
;
CREATE TABLE "properia"."listing_zone_scores" (
	"listing_id" uuid PRIMARY KEY NOT NULL,
	"poi_snapshot_id" uuid,
	"quiet_score" numeric(5, 2),
	"lifestyle_score" numeric(5, 2),
	"family_score" numeric(5, 2),
	"mobility_score" numeric(5, 2),
	"convenience_score" numeric(5, 2),
	"senior_score" numeric(5, 2),
	"investment_area_score" numeric(5, 2),
	"green_score" numeric(5, 2),
	"walkability_score" numeric(5, 2),
	"nightlife_score" numeric(5, 2),
	"zone_label_primary" text,
	"zone_label_secondary" text,
	"zone_summary_short" text,
	"zone_summary_long" text,
	"score_rationale" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"generated_by" text DEFAULT 'rules' NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL
);
;
CREATE TABLE "properia"."listings" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"public_id" text NOT NULL,
	"advertiser_id" uuid NOT NULL,
	"owner_user_id" uuid,
	"external_source" text,
	"external_listing_id" text,
	"source_type" "properia"."listing_source_type" DEFAULT 'manual' NOT NULL,
	"status" "properia"."listing_status" DEFAULT 'draft' NOT NULL,
	"business_type" "properia"."business_type" NOT NULL,
	"property_type" "properia"."property_type" NOT NULL,
	"property_subtype" text,
	"condition_declared" "properia"."condition_status",
	"condition_final" "properia"."condition_status",
	"furnished_declared" "properia"."furnished_status",
	"furnished_final" "properia"."furnished_status",
	"title" text NOT NULL,
	"title_normalized" text NOT NULL,
	"description_raw" text,
	"description_normalized" text,
	"description_short" text,
	"price_amount" numeric(14, 2),
	"price_currency" text DEFAULT 'EUR' NOT NULL,
	"bedrooms" integer DEFAULT 0 NOT NULL,
	"bathrooms" numeric(4, 1) DEFAULT '0' NOT NULL,
	"suites" integer DEFAULT 0 NOT NULL,
	"garage_spaces" integer DEFAULT 0 NOT NULL,
	"parking_spaces" integer DEFAULT 0 NOT NULL,
	"usable_area_m2" numeric(10, 2),
	"gross_area_m2" numeric(10, 2),
	"lot_area_m2" numeric(10, 2),
	"floor_number" integer,
	"total_floors" integer,
	"construction_year" integer,
	"renovation_year" integer,
	"energy_rating" text,
	"sun_exposure" text,
	"city" text,
	"district" text,
	"parish" text,
	"neighborhood" text,
	"postal_code" text,
	"country_code" text DEFAULT 'PT' NOT NULL,
	"latitude" double precision,
	"longitude" double precision,
	"geohash" text,
	"available_from" date,
	"is_immediately_available" boolean DEFAULT false NOT NULL,
	"has_elevator" boolean DEFAULT false NOT NULL,
	"has_balcony" boolean DEFAULT false NOT NULL,
	"has_terrace" boolean DEFAULT false NOT NULL,
	"has_garden" boolean DEFAULT false NOT NULL,
	"has_pool" boolean DEFAULT false NOT NULL,
	"has_storage_room" boolean DEFAULT false NOT NULL,
	"has_garage" boolean DEFAULT false NOT NULL,
	"has_private_parking" boolean DEFAULT false NOT NULL,
	"has_equipped_kitchen" boolean DEFAULT false NOT NULL,
	"has_open_kitchen" boolean DEFAULT false NOT NULL,
	"has_office_space" boolean DEFAULT false NOT NULL,
	"has_built_in_closets" boolean DEFAULT false NOT NULL,
	"has_natural_light" boolean DEFAULT false NOT NULL,
	"has_quiet_orientation" boolean DEFAULT false NOT NULL,
	"has_accessibility_features" boolean DEFAULT false NOT NULL,
	"has_fireplace" boolean DEFAULT false NOT NULL,
	"has_air_conditioning" boolean DEFAULT false NOT NULL,
	"has_double_glazing" boolean DEFAULT false NOT NULL,
	"has_solar_panels" boolean DEFAULT false NOT NULL,
	"has_home_automation" boolean DEFAULT false NOT NULL,
	"has_sea_view" boolean DEFAULT false NOT NULL,
	"has_river_view" boolean DEFAULT false NOT NULL,
	"has_city_view" boolean DEFAULT false NOT NULL,
	"has_green_view" boolean DEFAULT false NOT NULL,
	"hero_image_url" text,
	"cover_quality_score" numeric(5, 2),
	"listing_quality_score" numeric(5, 2),
	"completeness_score" numeric(5, 2),
	"visibility_status" "properia"."visibility_status" DEFAULT 'organic' NOT NULL,
	"is_featured" boolean DEFAULT false NOT NULL,
	"is_premium" boolean DEFAULT false NOT NULL,
	"data_entry_at" timestamp with time zone DEFAULT now() NOT NULL,
	"first_published_at" timestamp with time zone,
	"published_at" timestamp with time zone,
	"last_synced_at" timestamp with time zone,
	"last_enriched_at" timestamp with time zone,
	"needs_geocoding" boolean DEFAULT true NOT NULL,
	"needs_poi_refresh" boolean DEFAULT true NOT NULL,
	"needs_vision_refresh" boolean DEFAULT true NOT NULL,
	"needs_profile_rescore" boolean DEFAULT true NOT NULL,
	"needs_summary_refresh" boolean DEFAULT true NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "listings_public_id_unique" UNIQUE("public_id"),
	CONSTRAINT "listings_price_non_negative" CHECK ("properia"."listings"."price_amount" >= 0)
);
;
CREATE TABLE "properia"."partner_leads" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"partner_id" uuid NOT NULL,
	"lead_id" uuid NOT NULL,
	"product_type" text NOT NULL,
	"status" text NOT NULL,
	"revenue_model" text,
	"payout_value" numeric(12, 2),
	"metadata" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "partner_leads_unique" UNIQUE("partner_id","lead_id","product_type")
);
;
CREATE TABLE "properia"."partners" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"name" text NOT NULL,
	"partner_type" text NOT NULL,
	"contact_email" varchar(320),
	"webhook_url" text,
	"revenue_model" text,
	"is_active" boolean DEFAULT true NOT NULL,
	"settings" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL
);
;
CREATE TABLE "properia"."profiles" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"owner_user_id" uuid,
	"profile_type" "properia"."profile_type" NOT NULL,
	"name" text NOT NULL,
	"description" text,
	"icon" text,
	"color" text,
	"rules_json" jsonb DEFAULT '{}'::jsonb NOT NULL,
	"is_system" boolean DEFAULT false NOT NULL,
	"is_active" boolean DEFAULT true NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL
);
;
CREATE TABLE "properia"."visits" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"lead_id" uuid,
	"listing_id" uuid NOT NULL,
	"advertiser_id" uuid NOT NULL,
	"mode" "properia"."visit_mode" NOT NULL,
	"status" "properia"."visit_status" DEFAULT 'requested' NOT NULL,
	"starts_at" timestamp with time zone NOT NULL,
	"ends_at" timestamp with time zone,
	"meeting_url" text,
	"notes" text,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL
);
;
ALTER TABLE "properia"."advertiser_users" ADD CONSTRAINT "advertiser_users_advertiser_id_advertisers_id_fk" FOREIGN KEY ("advertiser_id") REFERENCES "properia"."advertisers"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."advertiser_users" ADD CONSTRAINT "advertiser_users_user_id_app_users_id_fk" FOREIGN KEY ("user_id") REFERENCES "properia"."app_users"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."leads" ADD CONSTRAINT "leads_listing_id_listings_id_fk" FOREIGN KEY ("listing_id") REFERENCES "properia"."listings"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."leads" ADD CONSTRAINT "leads_user_id_app_users_id_fk" FOREIGN KEY ("user_id") REFERENCES "properia"."app_users"("id") ON DELETE set null ON UPDATE no action;;
ALTER TABLE "properia"."leads" ADD CONSTRAINT "leads_advertiser_id_advertisers_id_fk" FOREIGN KEY ("advertiser_id") REFERENCES "properia"."advertisers"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."leads" ADD CONSTRAINT "leads_assigned_to_app_users_id_fk" FOREIGN KEY ("assigned_to") REFERENCES "properia"."app_users"("id") ON DELETE set null ON UPDATE no action;;
ALTER TABLE "properia"."listing_ai_summaries" ADD CONSTRAINT "listing_ai_summaries_listing_id_listings_id_fk" FOREIGN KEY ("listing_id") REFERENCES "properia"."listings"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."listing_ai_vision" ADD CONSTRAINT "listing_ai_vision_listing_id_listings_id_fk" FOREIGN KEY ("listing_id") REFERENCES "properia"."listings"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."listing_audit" ADD CONSTRAINT "listing_audit_listing_id_listings_id_fk" FOREIGN KEY ("listing_id") REFERENCES "properia"."listings"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."listing_audit" ADD CONSTRAINT "listing_audit_changed_by_app_users_id_fk" FOREIGN KEY ("changed_by") REFERENCES "properia"."app_users"("id") ON DELETE set null ON UPDATE no action;;
ALTER TABLE "properia"."listing_commercial" ADD CONSTRAINT "listing_commercial_listing_id_listings_id_fk" FOREIGN KEY ("listing_id") REFERENCES "properia"."listings"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."listing_dimensions" ADD CONSTRAINT "listing_dimensions_listing_id_listings_id_fk" FOREIGN KEY ("listing_id") REFERENCES "properia"."listings"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."listing_energy" ADD CONSTRAINT "listing_energy_listing_id_listings_id_fk" FOREIGN KEY ("listing_id") REFERENCES "properia"."listings"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."listing_features" ADD CONSTRAINT "listing_features_listing_id_listings_id_fk" FOREIGN KEY ("listing_id") REFERENCES "properia"."listings"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."listing_location" ADD CONSTRAINT "listing_location_listing_id_listings_id_fk" FOREIGN KEY ("listing_id") REFERENCES "properia"."listings"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."listing_media" ADD CONSTRAINT "listing_media_listing_id_listings_id_fk" FOREIGN KEY ("listing_id") REFERENCES "properia"."listings"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."listing_poi_snapshots" ADD CONSTRAINT "listing_poi_snapshots_listing_id_listings_id_fk" FOREIGN KEY ("listing_id") REFERENCES "properia"."listings"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."listing_pricing" ADD CONSTRAINT "listing_pricing_listing_id_listings_id_fk" FOREIGN KEY ("listing_id") REFERENCES "properia"."listings"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."listing_profile_scores" ADD CONSTRAINT "listing_profile_scores_listing_id_listings_id_fk" FOREIGN KEY ("listing_id") REFERENCES "properia"."listings"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."listing_profile_scores" ADD CONSTRAINT "listing_profile_scores_profile_id_profiles_id_fk" FOREIGN KEY ("profile_id") REFERENCES "properia"."profiles"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."listing_visibility" ADD CONSTRAINT "listing_visibility_listing_id_listings_id_fk" FOREIGN KEY ("listing_id") REFERENCES "properia"."listings"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."listing_zone_scores" ADD CONSTRAINT "listing_zone_scores_listing_id_listings_id_fk" FOREIGN KEY ("listing_id") REFERENCES "properia"."listings"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."listing_zone_scores" ADD CONSTRAINT "listing_zone_scores_poi_snapshot_id_listing_poi_snapshots_id_fk" FOREIGN KEY ("poi_snapshot_id") REFERENCES "properia"."listing_poi_snapshots"("id") ON DELETE set null ON UPDATE no action;;
ALTER TABLE "properia"."listings" ADD CONSTRAINT "listings_advertiser_id_advertisers_id_fk" FOREIGN KEY ("advertiser_id") REFERENCES "properia"."advertisers"("id") ON DELETE restrict ON UPDATE no action;;
ALTER TABLE "properia"."listings" ADD CONSTRAINT "listings_owner_user_id_app_users_id_fk" FOREIGN KEY ("owner_user_id") REFERENCES "properia"."app_users"("id") ON DELETE set null ON UPDATE no action;;
ALTER TABLE "properia"."partner_leads" ADD CONSTRAINT "partner_leads_partner_id_partners_id_fk" FOREIGN KEY ("partner_id") REFERENCES "properia"."partners"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."partner_leads" ADD CONSTRAINT "partner_leads_lead_id_leads_id_fk" FOREIGN KEY ("lead_id") REFERENCES "properia"."leads"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."profiles" ADD CONSTRAINT "profiles_owner_user_id_app_users_id_fk" FOREIGN KEY ("owner_user_id") REFERENCES "properia"."app_users"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."visits" ADD CONSTRAINT "visits_lead_id_leads_id_fk" FOREIGN KEY ("lead_id") REFERENCES "properia"."leads"("id") ON DELETE set null ON UPDATE no action;;
ALTER TABLE "properia"."visits" ADD CONSTRAINT "visits_listing_id_listings_id_fk" FOREIGN KEY ("listing_id") REFERENCES "properia"."listings"("id") ON DELETE cascade ON UPDATE no action;;
ALTER TABLE "properia"."visits" ADD CONSTRAINT "visits_advertiser_id_advertisers_id_fk" FOREIGN KEY ("advertiser_id") REFERENCES "properia"."advertisers"("id") ON DELETE cascade ON UPDATE no action;;
CREATE INDEX "idx_job_executions_entity" ON "properia"."job_executions" USING btree ("entity_type","entity_id","status");;
CREATE INDEX "idx_job_executions_job_type_status" ON "properia"."job_executions" USING btree ("job_type","status","created_at");;
CREATE INDEX "idx_leads_advertiser_stage" ON "properia"."leads" USING btree ("advertiser_id","stage","created_at");;
CREATE INDEX "idx_listing_media_listing_sort" ON "properia"."listing_media" USING btree ("listing_id","sort_order");;
CREATE INDEX "idx_listing_poi_snapshots_listing_processed" ON "properia"."listing_poi_snapshots" USING btree ("listing_id","processed_at");;
CREATE INDEX "idx_listing_profile_scores_listing_score" ON "properia"."listing_profile_scores" USING btree ("listing_id","score");;
CREATE INDEX "idx_listing_profile_scores_profile_score" ON "properia"."listing_profile_scores" USING btree ("profile_id","score");;
CREATE INDEX "idx_listings_status" ON "properia"."listings" USING btree ("status");;
CREATE INDEX "idx_listings_business_type" ON "properia"."listings" USING btree ("business_type");;
CREATE INDEX "idx_listings_property_type" ON "properia"."listings" USING btree ("property_type");;
CREATE INDEX "idx_listings_price_amount" ON "properia"."listings" USING btree ("price_amount");;
CREATE INDEX "idx_listings_city" ON "properia"."listings" USING btree ("city");;
CREATE INDEX "idx_listings_district" ON "properia"."listings" USING btree ("district");;
CREATE INDEX "idx_listings_parish" ON "properia"."listings" USING btree ("parish");;
CREATE INDEX "idx_listings_geohash" ON "properia"."listings" USING btree ("geohash");;
CREATE INDEX "idx_listings_advertiser_id" ON "properia"."listings" USING btree ("advertiser_id");;
CREATE INDEX "idx_listings_data_entry_at" ON "properia"."listings" USING btree ("data_entry_at");;
CREATE INDEX "idx_visits_advertiser_starts" ON "properia"."visits" USING btree ("advertiser_id","starts_at");
