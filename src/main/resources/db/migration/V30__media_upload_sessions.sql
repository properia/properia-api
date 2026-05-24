-- Upload sessions for direct-to-storage media uploads
CREATE TABLE IF NOT EXISTS properia.media_upload_sessions (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     uuid NOT NULL,
    listing_id  uuid REFERENCES properia.listings(id) ON DELETE SET NULL,
    object_key  text NOT NULL,
    content_type text NOT NULL DEFAULT 'image/jpeg',
    file_name   text,
    status      text NOT NULL DEFAULT 'pending',
    expires_at  timestamp with time zone NOT NULL,
    created_at  timestamp with time zone NOT NULL DEFAULT now(),
    updated_at  timestamp with time zone NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_media_upload_sessions_user ON properia.media_upload_sessions (user_id);
CREATE INDEX IF NOT EXISTS idx_media_upload_sessions_listing ON properia.media_upload_sessions (listing_id);

-- Add file_name to listing_media (not in original schema)
ALTER TABLE properia.listing_media ADD COLUMN IF NOT EXISTS file_name text;

-- Ensure source_type has a default so direct uploads don't need to specify it
ALTER TABLE properia.listing_media ALTER COLUMN source_type SET DEFAULT 'upload';
