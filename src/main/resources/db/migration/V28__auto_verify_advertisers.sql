-- Auto-approve advertisers in pending_review (removed manual admin approval requirement)
UPDATE properia.advertisers
SET verification_status = 'verified_basic',
    is_active           = true,
    updated_at          = now()
WHERE verification_status = 'pending_review';
