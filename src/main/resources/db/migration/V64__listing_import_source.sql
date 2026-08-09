-- Distingue imóveis criados manualmente dos importados por CSV/feed XML
-- (components/advertiser/advertiser-listing-import-page.tsx), para exibir
-- um badge de origem no card do imóvel.
ALTER TABLE properia.listings
    ADD COLUMN import_source varchar(16) NOT NULL DEFAULT 'manual'
    CONSTRAINT listings_import_source_check CHECK (import_source IN ('manual', 'import'));
