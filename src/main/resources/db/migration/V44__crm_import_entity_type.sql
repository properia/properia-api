-- Add entity_type and review_required_rows columns to crm_import_batches
ALTER TABLE properia.crm_import_batches
  ADD COLUMN IF NOT EXISTS entity_type text NOT NULL DEFAULT 'lead',
  ADD COLUMN IF NOT EXISTS review_required_rows integer NOT NULL DEFAULT 0;
