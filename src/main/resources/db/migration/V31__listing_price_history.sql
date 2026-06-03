-- Histórico de preço por anúncio
CREATE TABLE properia."listing_price_history" (
    "id"            uuid        PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
    "listing_id"    uuid        NOT NULL REFERENCES properia."listings"("id") ON DELETE CASCADE,
    "price_amount"  numeric(14,2) NOT NULL,
    "price_currency" text       NOT NULL DEFAULT 'EUR',
    "recorded_at"   timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX "idx_listing_price_history_listing_date"
    ON properia."listing_price_history" ("listing_id", "recorded_at" ASC);
