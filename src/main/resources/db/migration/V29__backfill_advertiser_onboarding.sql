-- Create advertiser_onboarding rows for advertisers that don't have one yet
INSERT INTO properia.advertiser_onboarding
  (advertiser_id, owner_user_id, status, step_current, completed_steps,
   advertiser_type_selected, service_districts, property_specialties,
   accepts_online_visits, created_at, updated_at)
SELECT
  a.id,
  au.user_id,
  'active',
  'done',
  '["basic_profile","commercial_identity","market_scope","first_listing"]'::jsonb,
  a.advertiser_type,
  '[]'::jsonb,
  '[]'::jsonb,
  false,
  now(),
  now()
FROM properia.advertisers a
JOIN properia.advertiser_users au ON au.advertiser_id = a.id AND au.membership_role = 'owner'
WHERE NOT EXISTS (
  SELECT 1 FROM properia.advertiser_onboarding ao WHERE ao.advertiser_id = a.id
);
