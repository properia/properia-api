-- ============================================================
-- PROPERIA — Demo Seed S01
-- Cria dados completos para demo/gravação da área do anunciante
-- Todos os registos usam UUIDs fixos para fácil limpeza
-- Senha de todos os utilizadores: Demo@2026
-- ============================================================

-- Create listing_images table if it doesn't exist
CREATE TABLE IF NOT EXISTS properia.listing_images (
  id UUID NOT NULL DEFAULT gen_random_uuid(),
  listing_id UUID NOT NULL,
  url TEXT NOT NULL,
  position INTEGER NOT NULL DEFAULT 0,
  caption TEXT,
  metadata JSONB NOT NULL DEFAULT '{}',
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
  PRIMARY KEY (id),
  FOREIGN KEY (listing_id) REFERENCES properia.listings(id) ON DELETE CASCADE
);

-- ──────────────────────────────────────────────────────────
-- 1. APP_USERS — Equipa + Compradores
-- ──────────────────────────────────────────────────────────

-- Owner da agência
INSERT INTO properia.app_users (id, email, full_name, role, avatar_url, locale, is_active, preferences, consents, created_at, updated_at)
VALUES (
  'a0000001-0000-0000-0000-000000000001',
  'raphaeliarussi@properia.pt',
  'Raphael Iarussi',
  'agency_admin',
  'https://images.unsplash.com/photo-1560250097-0b93528c311a?w=200&h=200&fit=crop&crop=face',
  'pt-PT',
  true,
  '{"notifications": {"email": true, "push": true}, "theme": "light"}'::jsonb,
  '{"terms_privacy": true, "marketing": false}'::jsonb,
  NOW() - INTERVAL '6 months',
  NOW()
) ON CONFLICT DO NOTHING;

-- Agente 1 — Ana Silva
INSERT INTO properia.app_users (id, email, full_name, role, avatar_url, locale, is_active, preferences, consents, created_at, updated_at)
VALUES (
  'a0000001-0000-0000-0000-000000000002',
  'ana.silva@properia.pt',
  'Ana Silva',
  'agent',
  'https://images.unsplash.com/photo-1573496359142-b8d87734a5a2?w=200&h=200&fit=crop&crop=face',
  'pt-PT',
  true,
  '{"notifications": {"email": true, "push": true}}'::jsonb,
  '{"terms_privacy": true}'::jsonb,
  NOW() - INTERVAL '4 months',
  NOW()
) ON CONFLICT DO NOTHING;

-- Agente 2 — João Santos
INSERT INTO properia.app_users (id, email, full_name, role, avatar_url, locale, is_active, preferences, consents, created_at, updated_at)
VALUES (
  'a0000001-0000-0000-0000-000000000003',
  'joao.santos@properia.pt',
  'João Santos',
  'agent',
  'https://images.unsplash.com/photo-1472099645785-5658abf4ff4e?w=200&h=200&fit=crop&crop=face',
  'pt-PT',
  true,
  '{"notifications": {"email": true, "push": false}}'::jsonb,
  '{"terms_privacy": true}'::jsonb,
  NOW() - INTERVAL '3 months',
  NOW()
) ON CONFLICT DO NOTHING;

-- Comprador 1 — Maria Comprador (lead quente)
INSERT INTO properia.app_users (id, email, phone, full_name, role, locale, is_active, preferences, consents, created_at, updated_at)
VALUES (
  'a0000001-0000-0000-0000-000000000010',
  'maria.comprador@gmail.com',
  '+351 912 345 678',
  'Maria Fernandes',
  'buyer',
  'pt-PT',
  true,
  '{}'::jsonb,
  '{"terms_privacy": true, "marketing": false}'::jsonb,
  NOW() - INTERVAL '2 months',
  NOW()
) ON CONFLICT DO NOTHING;

-- Comprador 2 — Carlos Investidor
INSERT INTO properia.app_users (id, email, phone, full_name, role, locale, is_active, preferences, consents, created_at, updated_at)
VALUES (
  'a0000001-0000-0000-0000-000000000011',
  'carlos.investidor@gmail.com',
  '+351 966 543 210',
  'Carlos Mendes',
  'buyer',
  'pt-PT',
  true,
  '{}'::jsonb,
  '{"terms_privacy": true, "marketing": true}'::jsonb,
  NOW() - INTERVAL '5 weeks',
  NOW()
) ON CONFLICT DO NOTHING;

-- Comprador 3 — Sofia Família
INSERT INTO properia.app_users (id, email, phone, full_name, role, locale, is_active, preferences, consents, created_at, updated_at)
VALUES (
  'a0000001-0000-0000-0000-000000000012',
  'sofia.familia@gmail.com',
  '+351 934 789 012',
  'Sofia Rodrigues',
  'buyer',
  'pt-PT',
  true,
  '{}'::jsonb,
  '{"terms_privacy": true}'::jsonb,
  NOW() - INTERVAL '3 weeks',
  NOW()
) ON CONFLICT DO NOTHING;


-- ──────────────────────────────────────────────────────────
-- 2. USER_AUTH_IDENTITIES — Credenciais de login
--    Senha: Demo@2026
-- ──────────────────────────────────────────────────────────

INSERT INTO properia.user_auth_identities (user_id, provider, provider_user_id, email, email_verified, password_hash, password_algorithm, created_at, updated_at)
VALUES
  ('a0000001-0000-0000-0000-000000000001', 'local', 'raphaeliarussi@properia.pt',   'raphaeliarussi@properia.pt',   true, '$2b$10$mK8gKXVMd715HgGnlzGrAO9CirIxUPiNvjLjaTH8jVLPXd4yKsZfK', 'bcrypt', NOW(), NOW()),
  ('a0000001-0000-0000-0000-000000000002', 'local', 'ana.silva@properia.pt',         'ana.silva@properia.pt',         true, '$2b$10$5JonGG.Yz8XENRPPH.Y4Uuz42j2BzHdpeMg7MQ2CvtoJVDDW3lVem', 'bcrypt', NOW(), NOW()),
  ('a0000001-0000-0000-0000-000000000003', 'local', 'joao.santos@properia.pt',       'joao.santos@properia.pt',       true, '$2b$10$iCsxoAhIVa4YT.KKrmZ7M.NF4YFmKdVzZ.WUDR/.iH3iI2882LsuK', 'bcrypt', NOW(), NOW()),
  ('a0000001-0000-0000-0000-000000000010', 'local', 'maria.comprador@gmail.com',     'maria.comprador@gmail.com',     true, '$2b$10$GC3GPLm/FwCwiTWk2DTWx.PGJuvT1im7TS0Ubn5XaWenrJ1HdfJXe', 'bcrypt', NOW(), NOW()),
  ('a0000001-0000-0000-0000-000000000011', 'local', 'carlos.investidor@gmail.com',   'carlos.investidor@gmail.com',   true, '$2b$10$1Y5FTteXmAK9BDKO2yuaBuygRThvgA1K5SxM4mCnLfXEKUpe/uR1i', 'bcrypt', NOW(), NOW()),
  ('a0000001-0000-0000-0000-000000000012', 'local', 'sofia.familia@gmail.com',       'sofia.familia@gmail.com',       true, '$2b$10$umci2EbPNbu5Gu5PKKfXGOzuLoyzSpILQIqRwONV6EhwRaJS1Bgga', 'bcrypt', NOW(), NOW())
ON CONFLICT DO NOTHING;


-- ──────────────────────────────────────────────────────────
-- 3. ADVERTISER — Agência Properia Demo
-- ──────────────────────────────────────────────────────────

INSERT INTO properia.advertisers (
  id, advertiser_type, legal_name, brand_name, slug,
  tax_number, license_number, email, phone, website_url, logo_url,
  plan_code, is_active, created_at, updated_at
) VALUES
  ('b0000001-0000-0000-0000-000000000001', 'agency', 'Properia Imobiliária Demo Lda.', 'Properia Demo', 'properia-demo', '500123456', 'AMI-28473', 'contacto@properia.pt', '+351 210 987 654', 'https://properia.pt', 'https://images.unsplash.com/photo-1560518883-ce09059eeffa?w=400&h=200&fit=crop', 'business', true, NOW() - INTERVAL '6 months', NOW()),
  ('b0000001-0000-0000-0000-000000000002', 'agency', 'LisbonPlace Imobiliária Lda.', 'LisbonPlace', 'lisbon-place', '513654789', 'AMI-31256', 'info@lisboaplace.pt', '+351 213 456 789', 'https://lisboaplace.pt', 'https://images.unsplash.com/photo-1454165804606-c3d57bc86b40?w=400&h=200&fit=crop', 'professional', true, NOW() - INTERVAL '8 months', NOW()),
  ('b0000001-0000-0000-0000-000000000003', 'agency', 'Prime Residencial Porto Lda.', 'Prime Residencial', 'prime-residencial', '502987654', 'AMI-29845', 'contact@prime-residencial.pt', '+351 223 456 789', 'https://primeresidencial.pt', 'https://images.unsplash.com/photo-1452883896987-cdcefc25b635?w=400&h=200&fit=crop', 'business', true, NOW() - INTERVAL '10 months', NOW()),
  ('b0000001-0000-0000-0000-000000000004', 'agency', 'Espaço Premium Imóveis Lda.', 'Espaço Premium', 'espaco-premium', '508321456', 'AMI-30567', 'hello@espacopremium.pt', '+351 216 789 456', 'https://espacopremium.pt', 'https://images.unsplash.com/photo-1486406146926-c627a92ad1ab?w=400&h=200&fit=crop', 'professional', true, NOW() - INTERVAL '7 months', NOW()),
  ('b0000001-0000-0000-0000-000000000005', 'agency', 'Cascais Living Imobiliária Lda.', 'Cascais Living', 'cascais-living', '512123456', 'AMI-28956', 'office@cascaisliving.pt', '+351 214 567 890', 'https://cascaisliving.pt', 'https://images.unsplash.com/photo-1523821741446-edb429443ee4?w=400&h=200&fit=crop', 'business', true, NOW() - INTERVAL '5 months', NOW()),
  ('b0000001-0000-0000-0000-000000000006', 'agency', 'Sintra Homes Consultores Lda.', 'Sintra Homes', 'sintra-homes', '514789123', 'AMI-31089', 'team@sinthrahomes.pt', '+351 219 234 567', 'https://sinthrahomes.pt', 'https://images.unsplash.com/photo-1460317442991-0ec209397118?w=400&h=200&fit=crop', 'professional', true, NOW() - INTERVAL '4 months', NOW())
ON CONFLICT DO NOTHING;


-- ──────────────────────────────────────────────────────────
-- 4. ADVERTISER_USERS — Membros da equipa
-- ──────────────────────────────────────────────────────────

INSERT INTO properia.advertiser_users (advertiser_id, user_id, membership_role, created_at)
VALUES
  ('b0000001-0000-0000-0000-000000000001', 'a0000001-0000-0000-0000-000000000001', 'owner',  NOW() - INTERVAL '6 months'),
  ('b0000001-0000-0000-0000-000000000001', 'a0000001-0000-0000-0000-000000000002', 'admin',  NOW() - INTERVAL '4 months'),
  ('b0000001-0000-0000-0000-000000000001', 'a0000001-0000-0000-0000-000000000003', 'sales',  NOW() - INTERVAL '3 months')
ON CONFLICT DO NOTHING;


-- ──────────────────────────────────────────────────────────
-- 5. ADVERTISER_ONBOARDING — Marcado como concluído
-- ──────────────────────────────────────────────────────────

INSERT INTO properia.advertiser_onboarding (
  advertiser_id, owner_user_id, status, step_current, completed_steps,
  advertiser_type_selected, service_districts, property_specialties, accepts_online_visits,
  submitted_at, reviewed_at, created_at, updated_at
) VALUES (
  'b0000001-0000-0000-0000-000000000001',
  'a0000001-0000-0000-0000-000000000001',
  'active',
  'done',
  '["intent","basic_profile","commercial_identity","market_scope","first_listing","done"]'::jsonb,
  'agency',
  '["Lisboa","Setúbal"]'::jsonb,
  '["apartment","house","penthouse"]'::jsonb,
  true,
  NOW() - INTERVAL '5 months 25 days',
  NOW() - INTERVAL '5 months 20 days',
  NOW() - INTERVAL '6 months',
  NOW()
) ON CONFLICT DO NOTHING;


-- ──────────────────────────────────────────────────────────
-- 6. LISTING — T3 Apartamento em Lisboa, Príncipe Real
-- ──────────────────────────────────────────────────────────

INSERT INTO properia.listings (
  id, public_id, advertiser_id, owner_user_id,
  source_type, status, business_type, property_type,
  condition_declared, condition_final,
  furnished_declared, furnished_final,
  title, title_normalized,
  description_raw,
  price_amount, price_currency,
  bedrooms, bathrooms, suites, garage_spaces, parking_spaces,
  usable_area_m2, gross_area_m2,
  floor_number, total_floors,
  construction_year, renovation_year,
  energy_rating, sun_exposure,
  city, district, parish, neighborhood, postal_code, country_code,
  latitude, longitude, geohash,
  is_immediately_available,
  has_elevator, has_balcony, has_terrace, has_garden, has_pool,
  has_storage_room, has_garage, has_private_parking,
  has_equipped_kitchen, has_open_kitchen, has_office_space,
  has_built_in_closets, has_natural_light, has_quiet_orientation,
  has_accessibility_features, has_fireplace, has_air_conditioning,
  has_double_glazing, has_sea_view, has_city_view, has_green_view,
  hero_image_url,
  listing_quality_score, completeness_score,
  visibility_status, is_featured, is_premium,
  first_published_at, published_at, data_entry_at,
  needs_geocoding, needs_poi_refresh, needs_vision_refresh,
  needs_profile_rescore, needs_summary_refresh,
  created_at, updated_at
) VALUES (
  'c0000001-0000-0000-0000-000000000001',
  'LX-PR-T3-001',
  'b0000001-0000-0000-0000-000000000001',
  'a0000001-0000-0000-0000-000000000001',
  'manual',
  'published',
  'sale',
  'apartment',
  'remodeled',
  'remodeled',
  'semi_furnished',
  'semi_furnished',
  'Apartamento T3 Remodelado com Vista para o Jardim — Príncipe Real',
  'apartamento t3 remodelado com vista para o jardim principe real',
  'Magnífico apartamento T3 completamente remodelado no coração do Príncipe Real, um dos bairros mais emblemáticos e desejados de Lisboa. O imóvel, situado num edifício pombalino rehabilitado, combina a charme da arquitectura histórica com acabamentos contemporâneos de elevada qualidade.

O apartamento dispõe de ampla sala de estar com lareira e acesso a varanda com vista para o jardim interior, cozinha totalmente equipada de conceito aberto, três quartos (sendo uma suite principal com walk-in closet), duas casas de banho completas e uma lavandaria independente.

Características de destaque: pavimento em soalho de carvalho europeu, cozinha com electrodomésticos Smeg, casa de banho principal em mármore de Estremoz, janelas em caixilharia de alumínio termolacado com vidro duplo, ar condicionado multi-split, e sistema domótico integrado.

O edifício beneficia de elevador recém-instalado, arrecadação privativa na cave e lugar de estacionamento coberto em parque próximo incluído no negócio.

A localização é privilegiada: a 5 minutos a pé do Jardim do Príncipe Real, próximo do Mercado da Ribeira, Chiado, Bairro Alto, museus e excelente rede de transportes públicos.',
  875000.00,
  'EUR',
  3, 2.0, 1, 0, 1,
  135.00, 158.00,
  3, 6,
  1910, 2022,
  'A',
  'south',
  'Lisboa', 'Lisboa', 'Misericórdia', 'Príncipe Real', '1200-001', 'PT',
  38.71520, -9.14800, 'eycs3e',
  true,
  true,  -- elevator
  true,  -- balcony
  false, -- terrace
  false, -- garden
  false, -- pool
  true,  -- storage_room
  false, -- garage
  true,  -- private_parking
  true,  -- equipped_kitchen
  true,  -- open_kitchen
  true,  -- office_space
  true,  -- built_in_closets
  true,  -- natural_light
  true,  -- quiet_orientation
  true,  -- accessibility
  true,  -- fireplace
  true,  -- air_conditioning
  true,  -- double_glazing
  false, -- sea_view
  true,  -- city_view
  true,  -- green_view
  'https://images.unsplash.com/photo-1512917774080-9c393c0f2e38?w=1200&h=800&fit=crop',
  92.00, 95.00,
  'featured', true, true,
  NOW() - INTERVAL '45 days',
  NOW() - INTERVAL '45 days',
  NOW() - INTERVAL '50 days',
  false, false, false, false, false,
  NOW() - INTERVAL '50 days',
  NOW()
) ON CONFLICT DO NOTHING;


-- ──────────────────────────────────────────────────────────
-- 7. LISTING_LOCATION — Localização detalhada
-- ──────────────────────────────────────────────────────────

INSERT INTO properia.listing_location (
  listing_id, country, country_code, district, municipality, city,
  parish, neighborhood, street, street_number,
  postal_code, postal_code_suffix, full_address, display_address,
  latitude, longitude, geohash, geocoding_provider, geocoding_confidence,
  location_precision, hide_exact_location, created_at, updated_at
) VALUES (
  'c0000001-0000-0000-0000-000000000001',
  'Portugal', 'PT', 'Lisboa', 'Lisboa', 'Lisboa',
  'Misericórdia', 'Príncipe Real', 'Rua Dom Pedro V', '82',
  '1200-001', '002',
  'Rua Dom Pedro V 82, 1200-001 Lisboa',
  'Príncipe Real, Lisboa',
  38.71520, -9.14800, 'eycs3e',
  'nominatim', 0.97,
  'street', false,
  NOW(), NOW()
) ON CONFLICT DO NOTHING;


-- ──────────────────────────────────────────────────────────
-- 8. LISTING_PRICING — Preços e detalhes financeiros
-- ──────────────────────────────────────────────────────────

INSERT INTO properia.listing_pricing (
  listing_id, list_price, price_currency, price_period,
  condo_fee, price_per_m2,
  negotiable, accepts_financing,
  broker_commission_percentage,
  market_position_score, price_segment,
  created_at, updated_at
) VALUES (
  'c0000001-0000-0000-0000-000000000001',
  875000.00, 'EUR', 'sale',
  280.00, 6481.48,
  true, true,
  3.00,
  72.00, 'premium',
  NOW(), NOW()
) ON CONFLICT DO NOTHING;


-- ──────────────────────────────────────────────────────────
-- 9. LISTING_PRICE_HISTORY — Histórico de preço
-- ──────────────────────────────────────────────────────────

INSERT INTO properia.listing_price_history (listing_id, price_amount, price_currency, recorded_at)
VALUES
  ('c0000001-0000-0000-0000-000000000001', 925000.00, 'EUR', NOW() - INTERVAL '50 days'),
  ('c0000001-0000-0000-0000-000000000001', 900000.00, 'EUR', NOW() - INTERVAL '30 days'),
  ('c0000001-0000-0000-0000-000000000001', 875000.00, 'EUR', NOW() - INTERVAL '10 days');


-- ──────────────────────────────────────────────────────────
-- 10. LISTING_DIMENSIONS — Áreas detalhadas
-- ──────────────────────────────────────────────────────────

INSERT INTO properia.listing_dimensions (
  listing_id, usable_area_m2, gross_area_m2, private_area_m2,
  balcony_area_m2, storage_area_m2, ceiling_height_m,
  rooms_total, bedrooms, bathrooms, suites, living_rooms, office_rooms,
  created_at, updated_at
) VALUES (
  'c0000001-0000-0000-0000-000000000001',
  135.00, 158.00, 128.50,
  8.50, 12.00, 3.20,
  7, 3, 2, 1, 1, 1,
  NOW(), NOW()
) ON CONFLICT DO NOTHING;


-- ──────────────────────────────────────────────────────────
-- 11. LISTING_ENERGY — Eficiência energética
-- ──────────────────────────────────────────────────────────

INSERT INTO properia.listing_energy (
  listing_id, energy_certificate_rating, energy_certificate_number,
  energy_certificate_valid_until,
  heating_type, cooling_type, water_heating_type,
  window_type, insulation_type,
  solar_exposure_score,
  created_at, updated_at
) VALUES (
  'c0000001-0000-0000-0000-000000000001',
  'A', 'SCE-2022-LX-47291',
  '2032-06-30',
  'heat_pump', 'multi_split_ac', 'heat_pump',
  'double_glazed_aluminum', 'thermal_exterior',
  88.0,
  NOW(), NOW()
) ON CONFLICT DO NOTHING;


-- ──────────────────────────────────────────────────────────
-- 12. LISTING_FEATURES — Tags e características
-- ──────────────────────────────────────────────────────────

INSERT INTO properia.listing_features (
  listing_id, feature_flags, feature_tags, view_tags, lifestyle_tags, premium_signals,
  created_at, updated_at
) VALUES (
  'c0000001-0000-0000-0000-000000000001',
  '{"renovated_2022": true, "pombaline_building": true, "private_parking": true}'::jsonb,
  '["home_automation","walk_in_closet","marble_bathroom","oak_flooring","smeg_appliances"]'::jsonb,
  '["jardim","cidade","bairro_historico"]'::jsonb,
  '["historic_charm","urban_lifestyle","premium_finishes","work_from_home"]'::jsonb,
  '["high_ceilings","exposed_wood_beams","period_building","fully_renovated"]'::jsonb,
  NOW(), NOW()
) ON CONFLICT DO NOTHING;


-- ──────────────────────────────────────────────────────────
-- 13. LISTING_COMMERCIAL — Configurações de contacto e tour
-- ──────────────────────────────────────────────────────────

INSERT INTO properia.listing_commercial (
  listing_id, exclusive_listing, open_house_available, online_visit_available,
  visit_booking_enabled,
  youtube_tour_url,
  show_phone, show_whatsapp, show_chat,
  created_at, updated_at
) VALUES (
  'c0000001-0000-0000-0000-000000000001',
  true, true, true,
  true,
  'https://www.youtube.com/watch?v=dQw4w9WgXcQ',
  true, true, true,
  NOW(), NOW()
) ON CONFLICT DO NOTHING;


-- ──────────────────────────────────────────────────────────
-- 14. LISTING_MEDIA — Fotos reais do imóvel
-- ──────────────────────────────────────────────────────────

INSERT INTO properia.listing_media (id, listing_id, media_type, source_type, url, thumbnail_url, sort_order, is_cover, room_hint, created_at, updated_at)
VALUES
  -- Capa principal
  (gen_random_uuid(), 'c0000001-0000-0000-0000-000000000001', 'image', 'external',
   'https://images.unsplash.com/photo-1502672260266-1c1ef2d93688?w=1200&h=800&fit=crop',
   'https://images.unsplash.com/photo-1502672260266-1c1ef2d93688?w=400&h=267&fit=crop',
   1, true, 'living_room', NOW(), NOW()),
  -- Sala de estar
  (gen_random_uuid(), 'c0000001-0000-0000-0000-000000000001', 'image', 'external',
   'https://images.unsplash.com/photo-1586023492125-27b2c045efd7?w=1200&h=800&fit=crop',
   'https://images.unsplash.com/photo-1586023492125-27b2c045efd7?w=400&h=267&fit=crop',
   2, false, 'living_room', NOW(), NOW()),
  -- Cozinha
  (gen_random_uuid(), 'c0000001-0000-0000-0000-000000000001', 'image', 'external',
   'https://images.unsplash.com/photo-1556909114-f6e7ad7d3136?w=1200&h=800&fit=crop',
   'https://images.unsplash.com/photo-1556909114-f6e7ad7d3136?w=400&h=267&fit=crop',
   3, false, 'kitchen', NOW(), NOW()),
  -- Quarto principal
  (gen_random_uuid(), 'c0000001-0000-0000-0000-000000000001', 'image', 'external',
   'https://images.unsplash.com/photo-1540518614846-7eded433c457?w=1200&h=800&fit=crop',
   'https://images.unsplash.com/photo-1540518614846-7eded433c457?w=400&h=267&fit=crop',
   4, false, 'bedroom', NOW(), NOW()),
  -- Casa de banho
  (gen_random_uuid(), 'c0000001-0000-0000-0000-000000000001', 'image', 'external',
   'https://images.unsplash.com/photo-1552321554-5fefe8c9ef14?w=1200&h=800&fit=crop',
   'https://images.unsplash.com/photo-1552321554-5fefe8c9ef14?w=400&h=267&fit=crop',
   5, false, 'bathroom', NOW(), NOW()),
  -- Varanda/exterior
  (gen_random_uuid(), 'c0000001-0000-0000-0000-000000000001', 'image', 'external',
   'https://images.unsplash.com/photo-1600585154340-be6161a56a0c?w=1200&h=800&fit=crop',
   'https://images.unsplash.com/photo-1600585154340-be6161a56a0c?w=400&h=267&fit=crop',
   6, false, 'exterior', NOW(), NOW()),
  -- Fachada edifício
  (gen_random_uuid(), 'c0000001-0000-0000-0000-000000000001', 'image', 'external',
   'https://images.unsplash.com/photo-1564013799919-ab600027ffc6?w=1200&h=800&fit=crop',
   'https://images.unsplash.com/photo-1564013799919-ab600027ffc6?w=400&h=267&fit=crop',
   7, false, 'facade', NOW(), NOW()),
  -- Quarto 2
  (gen_random_uuid(), 'c0000001-0000-0000-0000-000000000001', 'image', 'external',
   'https://images.unsplash.com/photo-1555041469-a586c61ea9bc?w=1200&h=800&fit=crop',
   'https://images.unsplash.com/photo-1555041469-a586c61ea9bc?w=400&h=267&fit=crop',
   8, false, 'bedroom', NOW(), NOW());


-- ──────────────────────────────────────────────────────────
-- 15. LISTING_VISIBILITY — Destaque premium
-- ──────────────────────────────────────────────────────────

INSERT INTO properia.listing_visibility (
  listing_id, visibility_status, is_featured, is_sponsored, is_boosted,
  ranking_multiplier, quality_rank_score, freshness_score,
  created_at, updated_at
) VALUES (
  'c0000001-0000-0000-0000-000000000001',
  'featured', true, false, false,
  1.5, 92.0, 78.0,
  NOW(), NOW()
) ON CONFLICT DO NOTHING;


-- ──────────────────────────────────────────────────────────
-- 16. LISTING_POI_SNAPSHOTS — Pontos de interesse próximos
-- ──────────────────────────────────────────────────────────

INSERT INTO properia.listing_poi_snapshots (
  id, listing_id, radius_m, source, snapshot_version, processed_at,
  schools_count, health_count, transport_count, supermarket_count,
  restaurant_count, cafe_count, park_count, pharmacy_count,
  bank_count, gym_count, beach_count
) VALUES (
  'd0000001-0000-0000-0000-000000000001',
  'c0000001-0000-0000-0000-000000000001',
  700, 'overpass', 1, NOW(),
  4, 3, 12, 5,
  24, 18, 3, 4,
  6, 2, 0
) ON CONFLICT DO NOTHING;


-- ──────────────────────────────────────────────────────────
-- 17. LISTING_ZONE_SCORES — Scores de zona
-- ──────────────────────────────────────────────────────────

INSERT INTO properia.listing_zone_scores (
  listing_id, poi_snapshot_id,
  quiet_score, lifestyle_score, family_score, mobility_score, convenience_score,
  senior_score, investment_area_score, green_score, walkability_score, nightlife_score,
  zone_label_primary, zone_label_secondary,
  zone_summary_short,
  generated_by, created_at, updated_at
) VALUES (
  'c0000001-0000-0000-0000-000000000001',
  'd0000001-0000-0000-0000-000000000001',
  68.0, 94.0, 72.0, 92.0, 95.0,
  75.0, 88.0, 71.0, 96.0, 82.0,
  'Bairro histórico premium',
  'Excelente mobilidade urbana',
  'Príncipe Real combina charme histórico com vida urbana activa. Restaurantes, cafés e lojas de design a poucos passos.',
  'rules', NOW(), NOW()
) ON CONFLICT DO NOTHING;


-- ──────────────────────────────────────────────────────────
-- 18. LISTING_AI_SUMMARIES — Textos gerados por IA
-- ──────────────────────────────────────────────────────────

INSERT INTO properia.listing_ai_summaries (
  listing_id,
  summary_card,
  summary_detail,
  lifestyle_summary,
  zone_summary,
  buyer_fit_summary,
  seo_meta_title,
  seo_meta_description,
  generated_at, prompt_version,
  created_at, updated_at
) VALUES (
  'c0000001-0000-0000-0000-000000000001',
  'T3 renovado com classe A em Príncipe Real — 135 m², suite, lareira e lugar de garagem. Edifício pombalino com elevador.',
  'Apartamento de topo em edifício pombalino rehabilitado, completamente renovado em 2022 com materiais premium. Pé direito de 3,20m, soalho em carvalho europeu, suite com walk-in closet e casa de banho em mármore. Cozinha equipada Smeg de conceito aberto. Varanda sul com vista jardim. Sistema domótico e ar condicionado multi-split.',
  'Viver no Príncipe Real é ter Lisboa a seus pés. Acorde com sol a entrar pela varanda sul, caminhe até ao mercado biológico ao sábado, almoce no Cantinho do Avillez e chegue ao trabalho no Chiado a pé em 8 minutos.',
  'O Príncipe Real é o coração cultural de Lisboa. Com um walkability score de 96/100, tem à porta museus, galerias, restaurantes premiados e o jardim mais aprazível da cidade. A linha de metro do Rato fica a 4 minutos a pé.',
  'Ideal para famílias com crianças em idade escolar (3 colégios no raio de 700m), profissionais liberais que valorizam localização e qualidade de vida, e investidores com visão de longo prazo — a zona apresenta valorização média de 7% ao ano.',
  'Apartamento T3 Remodelado Príncipe Real Lisboa | 875.000 €',
  'T3 completamente renovado no Príncipe Real, Lisboa. 135 m², suite, classe energética A, varanda sul, lugar de garagem. Edifício pombalino rehabilitado.',
  NOW(), 1,
  NOW(), NOW()
) ON CONFLICT DO NOTHING;


-- ──────────────────────────────────────────────────────────
-- 19. LEADS — CRM com pipeline diverso
-- ──────────────────────────────────────────────────────────

-- Lead 1: Comprador quente — visita agendada
INSERT INTO properia.leads (
  id, listing_id, user_id, advertiser_id,
  source, stage, intent_type,
  message, contact_name, contact_email, contact_phone,
  score, assigned_to,
  metadata, created_at, updated_at
) VALUES (
  'e0000001-0000-0000-0000-000000000001',
  'c0000001-0000-0000-0000-000000000001',
  'a0000001-0000-0000-0000-000000000010',
  'b0000001-0000-0000-0000-000000000001',
  'listing_detail', 'visit_scheduled', 'buy',
  'Tenho muito interesse neste apartamento. Gostaria de agendar uma visita para esta semana, de preferência à tarde. Estou pré-aprovado no banco para até 950.000€.',
  'Maria Fernandes',
  'maria.comprador@gmail.com',
  '+351 912 345 678',
  91.0,
  'a0000001-0000-0000-0000-000000000002',
  '{
    "qualification": {"timeline": "immediate", "budget": "over_500k"},
    "bank_pre_approved": true,
    "max_budget": 950000,
    "notes": "Pré-aprovação confirmada. Muito motivada.",
    "last_contact": "2026-06-09"
  }'::jsonb,
  NOW() - INTERVAL '12 days',
  NOW() - INTERVAL '1 day'
) ON CONFLICT DO NOTHING;

-- Lead 2: Investidor qualificado — em proposta
INSERT INTO properia.leads (
  id, listing_id, user_id, advertiser_id,
  source, stage, intent_type,
  message, contact_name, contact_email, contact_phone,
  score, assigned_to,
  metadata, created_at, updated_at
) VALUES (
  'e0000001-0000-0000-0000-000000000002',
  'c0000001-0000-0000-0000-000000000001',
  'a0000001-0000-0000-0000-000000000011',
  'b0000001-0000-0000-0000-000000000001',
  'chat', 'proposal', 'invest',
  'Sou investidor imobiliário com carteira em Lisboa. Este apartamento interessa-me como activo de arrendamento premium de curta duração (AL). Qual a rentabilidade estimada?',
  'Carlos Mendes',
  'carlos.investidor@gmail.com',
  '+351 966 543 210',
  83.0,
  'a0000001-0000-0000-0000-000000000001',
  '{
    "qualification": {"timeline": "3_6_months", "budget": "over_500k"},
    "investment_type": "al_short_term",
    "estimated_yield_interest": "5-7%",
    "notes": "Tem portfólio de 4 imóveis em Lisboa. Financiamento a 60%.",
    "last_contact": "2026-06-08"
  }'::jsonb,
  NOW() - INTERVAL '18 days',
  NOW() - INTERVAL '2 days'
) ON CONFLICT DO NOTHING;

-- Lead 3: Família jovem — contacto recente
INSERT INTO properia.leads (
  id, listing_id, user_id, advertiser_id,
  source, stage, intent_type,
  message, contact_name, contact_email, contact_phone,
  score, assigned_to,
  metadata, created_at, updated_at
) VALUES (
  'e0000001-0000-0000-0000-000000000003',
  'c0000001-0000-0000-0000-000000000001',
  'a0000001-0000-0000-0000-000000000012',
  'b0000001-0000-0000-0000-000000000001',
  'listing_detail', 'contacted', 'buy',
  'Somos um casal com 2 filhos à procura de T3 em Lisboa. Este apartamento parece corresponder ao que procuramos. Podemos visitar ao fim de semana?',
  'Sofia Rodrigues',
  'sofia.familia@gmail.com',
  '+351 934 789 012',
  74.0,
  'a0000001-0000-0000-0000-000000000003',
  '{
    "qualification": {"timeline": "3_6_months", "budget": "over_500k"},
    "family_size": 4,
    "needs_school_nearby": true,
    "notes": "Procuram escola próxima. Orçamento máximo 900k.",
    "last_contact": "2026-06-10"
  }'::jsonb,
  NOW() - INTERVAL '5 days',
  NOW() - INTERVAL '1 day'
) ON CONFLICT DO NOTHING;

-- Lead 4: Lead frio — sem resposta
INSERT INTO properia.leads (
  id, listing_id, advertiser_id,
  source, stage, intent_type,
  message, contact_name, contact_email,
  score,
  metadata, created_at, updated_at
) VALUES (
  'e0000001-0000-0000-0000-000000000004',
  'c0000001-0000-0000-0000-000000000001',
  'b0000001-0000-0000-0000-000000000001',
  'listing_card', 'new', 'buy',
  NULL,
  'Utilizador Anónimo',
  'anonimo.interessado@hotmail.com',
  42.0,
  '{
    "qualification": {"timeline": "over_1_year", "budget": "300_500k"},
    "notes": "Lead de clique no card. Sem mensagem."
  }'::jsonb,
  NOW() - INTERVAL '2 days',
  NOW() - INTERVAL '2 days'
) ON CONFLICT DO NOTHING;


-- ──────────────────────────────────────────────────────────
-- 20. VISITS — Visitas agendadas
-- ──────────────────────────────────────────────────────────

-- Visita confirmada — Maria (lead 1)
INSERT INTO properia.visits (
  id, lead_id, listing_id, advertiser_id,
  mode, status,
  starts_at, ends_at,
  notes,
  created_at, updated_at
) VALUES (
  'f0000001-0000-0000-0000-000000000001',
  'e0000001-0000-0000-0000-000000000001',
  'c0000001-0000-0000-0000-000000000001',
  'b0000001-0000-0000-0000-000000000001',
  'onsite', 'confirmed',
  NOW() + INTERVAL '2 days' + INTERVAL '15 hours',
  NOW() + INTERVAL '2 days' + INTERVAL '16 hours',
  'Cliente pré-aprovada até 950k. Confirmar disponibilidade de estacionamento vizinho.',
  NOW() - INTERVAL '10 days',
  NOW() - INTERVAL '1 day'
) ON CONFLICT DO NOTHING;

-- Visita online — Carlos (lead 2)
INSERT INTO properia.visits (
  id, lead_id, listing_id, advertiser_id,
  mode, status,
  starts_at, ends_at,
  meeting_url, notes,
  created_at, updated_at
) VALUES (
  'f0000001-0000-0000-0000-000000000002',
  'e0000001-0000-0000-0000-000000000002',
  'c0000001-0000-0000-0000-000000000001',
  'b0000001-0000-0000-0000-000000000001',
  'online', 'confirmed',
  NOW() + INTERVAL '4 days' + INTERVAL '11 hours',
  NOW() + INTERVAL '4 days' + INTERVAL '11 hours' + INTERVAL '45 minutes',
  'https://meet.google.com/abc-defg-hij',
  'Investidor — foco em potencial AL. Preparar dados de rentabilidade da zona.',
  NOW() - INTERVAL '15 days',
  NOW() - INTERVAL '3 days'
) ON CONFLICT DO NOTHING;

-- Visita completada (histórico)
INSERT INTO properia.visits (
  id, listing_id, advertiser_id,
  mode, status,
  starts_at, ends_at, notes,
  created_at, updated_at
) VALUES (
  'f0000001-0000-0000-0000-000000000003',
  'c0000001-0000-0000-0000-000000000001',
  'b0000001-0000-0000-0000-000000000001',
  'onsite', 'completed',
  NOW() - INTERVAL '20 days' + INTERVAL '14 hours',
  NOW() - INTERVAL '20 days' + INTERVAL '15 hours',
  'Visita correu bem. Cliente interessado mas a considerar outras opções.',
  NOW() - INTERVAL '22 days',
  NOW() - INTERVAL '20 days'
) ON CONFLICT DO NOTHING;


-- ──────────────────────────────────────────────────────────
-- 21. CHAT — Conversas e mensagens
-- ──────────────────────────────────────────────────────────

-- Conversa 1 — Maria + Agência
INSERT INTO properia.chat_conversations (
  id, advertiser_id, listing_id, lead_id, buyer_user_id,
  status, last_message_at, last_message_preview,
  metadata, created_at, updated_at
) VALUES (
  'cc000001-0000-0000-0000-000000000001',
  'b0000001-0000-0000-0000-000000000001',
  'c0000001-0000-0000-0000-000000000001',
  'e0000001-0000-0000-0000-000000000001',
  'a0000001-0000-0000-0000-000000000010',
  'active',
  NOW() - INTERVAL '1 day',
  'Perfeito, até amanhã às 15h então!',
  '{}'::jsonb,
  NOW() - INTERVAL '12 days',
  NOW() - INTERVAL '1 day'
) ON CONFLICT DO NOTHING;

-- Participantes conversa 1
INSERT INTO properia.chat_participants (conversation_id, advertiser_id, user_id, role, last_read_at, metadata, created_at, updated_at)
VALUES
  ('cc000001-0000-0000-0000-000000000001', NULL, 'a0000001-0000-0000-0000-000000000010', 'buyer', NOW() - INTERVAL '1 day', '{}'::jsonb, NOW() - INTERVAL '12 days', NOW()),
  ('cc000001-0000-0000-0000-000000000001', 'b0000001-0000-0000-0000-000000000001', NULL, 'advertiser_member', NOW() - INTERVAL '1 day', '{}'::jsonb, NOW() - INTERVAL '12 days', NOW())
ON CONFLICT DO NOTHING;

-- Mensagens conversa 1
INSERT INTO properia.chat_messages (id, conversation_id, advertiser_id, listing_id, lead_id, sender_type, sender_user_id, message_type, body, metadata, created_at)
VALUES
  (gen_random_uuid(), 'cc000001-0000-0000-0000-000000000001', 'b0000001-0000-0000-0000-000000000001', 'c0000001-0000-0000-0000-000000000001', 'e0000001-0000-0000-0000-000000000001',
   'buyer', 'a0000001-0000-0000-0000-000000000010', 'text',
   'Boa tarde! Vi o vosso apartamento no Príncipe Real e fiquei muito interessada. Tenho pré-aprovação bancária até 950.000€. Seria possível visitar esta semana?',
   '{}'::jsonb, NOW() - INTERVAL '12 days'),
  (gen_random_uuid(), 'cc000001-0000-0000-0000-000000000001', 'b0000001-0000-0000-0000-000000000001', 'c0000001-0000-0000-0000-000000000001', 'e0000001-0000-0000-0000-000000000001',
   'advertiser_member', 'a0000001-0000-0000-0000-000000000002', 'text',
   'Olá Maria! Fico muito contente com o seu interesse. Temos disponibilidade na quinta-feira dia 13 às 15h ou sexta às 10h. Qual prefere?',
   '{}'::jsonb, NOW() - INTERVAL '11 days' - INTERVAL '4 hours'),
  (gen_random_uuid(), 'cc000001-0000-0000-0000-000000000001', 'b0000001-0000-0000-0000-000000000001', 'c0000001-0000-0000-0000-000000000001', 'e0000001-0000-0000-0000-000000000001',
   'buyer', 'a0000001-0000-0000-0000-000000000010', 'text',
   'Quinta às 15h é perfeito para mim! Tenho também o meu marido que gostaria de vir.',
   '{}'::jsonb, NOW() - INTERVAL '11 days' - INTERVAL '2 hours'),
  (gen_random_uuid(), 'cc000001-0000-0000-0000-000000000001', 'b0000001-0000-0000-0000-000000000001', 'c0000001-0000-0000-0000-000000000001', 'e0000001-0000-0000-0000-000000000001',
   'advertiser_member', 'a0000001-0000-0000-0000-000000000002', 'text',
   'Óptimo! Está confirmado para quinta-feira às 15h. Enviarei a morada exacta e indicações de estacionamento. Até lá, qualquer questão não hesite em contactar-me.',
   '{}'::jsonb, NOW() - INTERVAL '11 days' - INTERVAL '1 hour'),
  (gen_random_uuid(), 'cc000001-0000-0000-0000-000000000001', 'b0000001-0000-0000-0000-000000000001', 'c0000001-0000-0000-0000-000000000001', 'e0000001-0000-0000-0000-000000000001',
   'buyer', 'a0000001-0000-0000-0000-000000000010', 'text',
   'Perfeito, até amanhã às 15h então!',
   '{}'::jsonb, NOW() - INTERVAL '1 day');

-- Conversa 2 — Carlos investidor
INSERT INTO properia.chat_conversations (
  id, advertiser_id, listing_id, lead_id, buyer_user_id,
  status, last_message_at, last_message_preview,
  metadata, created_at, updated_at
) VALUES (
  'cc000001-0000-0000-0000-000000000002',
  'b0000001-0000-0000-0000-000000000001',
  'c0000001-0000-0000-0000-000000000001',
  'e0000001-0000-0000-0000-000000000002',
  'a0000001-0000-0000-0000-000000000011',
  'active',
  NOW() - INTERVAL '2 days',
  'Vou analisar os números e respondo brevemente.',
  '{}'::jsonb,
  NOW() - INTERVAL '18 days',
  NOW() - INTERVAL '2 days'
) ON CONFLICT DO NOTHING;

-- Participantes conversa 2
INSERT INTO properia.chat_participants (conversation_id, advertiser_id, user_id, role, last_read_at, metadata, created_at, updated_at)
VALUES
  ('cc000001-0000-0000-0000-000000000002', NULL, 'a0000001-0000-0000-0000-000000000011', 'buyer', NOW() - INTERVAL '2 days', '{}'::jsonb, NOW() - INTERVAL '18 days', NOW()),
  ('cc000001-0000-0000-0000-000000000002', 'b0000001-0000-0000-0000-000000000001', NULL, 'advertiser_member', NOW() - INTERVAL '2 days', '{}'::jsonb, NOW() - INTERVAL '18 days', NOW())
ON CONFLICT DO NOTHING;

-- Mensagens conversa 2
INSERT INTO properia.chat_messages (id, conversation_id, advertiser_id, listing_id, lead_id, sender_type, sender_user_id, message_type, body, metadata, created_at)
VALUES
  (gen_random_uuid(), 'cc000001-0000-0000-0000-000000000002', 'b0000001-0000-0000-0000-000000000001', 'c0000001-0000-0000-0000-000000000001', 'e0000001-0000-0000-0000-000000000002',
   'buyer', 'a0000001-0000-0000-0000-000000000011', 'text',
   'Boa noite. Tenho interesse neste imóvel como investimento em AL. Qual a rentabilidade bruta que projectam para este apartamento? Existe licença AL já atribuída?',
   '{}'::jsonb, NOW() - INTERVAL '18 days'),
  (gen_random_uuid(), 'cc000001-0000-0000-0000-000000000002', 'b0000001-0000-0000-0000-000000000001', 'c0000001-0000-0000-0000-000000000001', 'e0000001-0000-0000-0000-000000000002',
   'advertiser_member', 'a0000001-0000-0000-0000-000000000001', 'text',
   'Boa noite Carlos! Apartamentos similares no Príncipe Real têm yields brutas entre 5.5-7.2% em AL de curta duração. A licença AL está em processo de renovação — deverá ser emitida antes do escritório. Podemos marcar uma visita virtual para discutir os números em detalhe?',
   '{}'::jsonb, NOW() - INTERVAL '17 days' - INTERVAL '10 hours'),
  (gen_random_uuid(), 'cc000001-0000-0000-0000-000000000002', 'b0000001-0000-0000-0000-000000000001', 'c0000001-0000-0000-0000-000000000001', 'e0000001-0000-0000-0000-000000000002',
   'buyer', 'a0000001-0000-0000-0000-000000000011', 'text',
   'Vou analisar os números e respondo brevemente.',
   '{}'::jsonb, NOW() - INTERVAL '2 days');


-- ──────────────────────────────────────────────────────────
-- 22. LISTING_IMAGES — Galeria de imagens
-- ──────────────────────────────────────────────────────────

INSERT INTO properia.listing_images (listing_id, url, position, caption, metadata, created_at)
VALUES
  ('c0000001-0000-0000-0000-000000000001', 'https://images.unsplash.com/photo-1512917774080-9c393c0f2e38?w=1200&h=900&fit=crop', 0, 'Sala de estar com lareira', '{}'::jsonb, NOW()),
  ('c0000001-0000-0000-0000-000000000001', 'https://images.unsplash.com/photo-1556909114-f6e7ad7d3136?w=1200&h=900&fit=crop', 1, 'Cozinha moderna equipada', '{}'::jsonb, NOW()),
  ('c0000001-0000-0000-0000-000000000001', 'https://images.unsplash.com/photo-1631679706909-1844bbd54340?w=1200&h=900&fit=crop', 2, 'Quarto principal com suite', '{}'::jsonb, NOW()),
  ('c0000001-0000-0000-0000-000000000001', 'https://images.unsplash.com/photo-1552321554-5fefe8c9ef14?w=1200&h=900&fit=crop', 3, 'Casa de banho em mármore', '{}'::jsonb, NOW()),
  ('c0000001-0000-0000-0000-000000000001', 'https://images.unsplash.com/photo-1552540618-8b1a33a30f25?w=1200&h=900&fit=crop', 4, 'Varanda com vista para o jardim', '{}'::jsonb, NOW()),
  ('c0000001-0000-0000-0000-000000000001', 'https://images.unsplash.com/photo-1522708323590-d24dbb6b0267?w=1200&h=900&fit=crop', 5, 'Quarto secundário', '{}'::jsonb, NOW())
ON CONFLICT DO NOTHING;


-- ──────────────────────────────────────────────────────────
-- 23. LISTING_AI_VISION — Scores de análise visual
-- ──────────────────────────────────────────────────────────

INSERT INTO properia.listing_ai_vision (
  listing_id, version, provider, model,
  processed_at, style_primary, style_secondary,
  condition_ai, condition_confidence,
  quality_score, light_quality_score, spaciousness_score,
  layout_quality_score, premium_score, luxury_score,
  family_friendly_score, home_office_score,
  needs_human_review,
  raw_response, created_at, updated_at
) VALUES (
  'c0000001-0000-0000-0000-000000000001',
  1, 'anthropic', 'claude-3-5-sonnet',
  NOW() - INTERVAL '44 days',
  'contemporary', 'heritage',
  'remodeled', 0.96,
  94.0, 91.0, 88.0,
  90.0, 89.0, 76.0,
  82.0, 85.0,
  false,
  '{"processed": true}'::jsonb,
  NOW(), NOW()
) ON CONFLICT DO NOTHING;

-- ──────────────────────────────────────────────────────────
-- RESUMO DO SEED
-- ──────────────────────────────────────────────────────────
-- Utilizadores criados (senha: Demo@2026):
--   raphaeliarussi@properia.pt  → owner/agency_admin
--   ana.silva@properia.pt       → admin da agência
--   joao.santos@properia.pt     → sales da agência
--   maria.comprador@gmail.com   → comprador (lead quente)
--   carlos.investidor@gmail.com → investidor (em proposta)
--   sofia.familia@gmail.com     → família (contactada)
--
-- Agências (6 total):
--   • Properia Demo (properia-demo)
--   • LisbonPlace (lisbon-place)
--   • Prime Residencial (prime-residencial)
--   • Espaço Premium (espaco-premium)
--   • Cascais Living (cascais-living)
--   • Sintra Homes (sintra-homes)
--
-- Imóvel:     T3 Príncipe Real, Lisboa — 875.000€ (public_id: LX-PR-T3-001)
-- Galeria:    6 imagens de qualidade (sala, cozinha, quartos, casa-banho, varanda)
-- Leads:      4 leads em estágios variados (new → visit_scheduled)
-- Visitas:    2 futuras (onsite + online) + 1 completada
-- Chat:       2 conversas activas com mensagens
-- Histórico:  3 entradas de preço (925k → 900k → 875k)
-- ──────────────────────────────────────────────────────────
