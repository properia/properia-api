ALTER TABLE properia.listing_energy
  ADD COLUMN IF NOT EXISTS energy_certificate_status text,
  ADD COLUMN IF NOT EXISTS energy_certificate_exemption_reason text;

UPDATE properia.listing_energy
SET energy_certificate_status = CASE
  WHEN energy_certificate_exemption_reason IS NOT NULL THEN 'exempt'
  WHEN energy_certificate_rating IS NOT NULL
    OR energy_certificate_number IS NOT NULL
    OR energy_certificate_valid_until IS NOT NULL THEN 'declared'
  ELSE NULL
END
WHERE energy_certificate_status IS NULL;
