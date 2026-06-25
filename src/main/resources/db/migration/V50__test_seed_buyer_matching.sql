-- Test seed: 1 advertiser, 3 users with team membership, 10+ listings, 5 buyer profiles with criteria
-- All user passwords: TestDemo1234! (bcrypt hash)
-- This seed is designed for testing the buyer-listing matching Phase 1 feature

SET search_path TO properia;

-- Fixed UUIDs for reproducibility
\set ADV_ID 'f47ac10b-58cc-4372-a567-0e02b2c3d479'
\set USER1_ID '550e8400-e29b-41d4-a716-446655440001'
\set USER2_ID '550e8400-e29b-41d4-a716-446655440002'
\set USER3_ID '550e8400-e29b-41d4-a716-446655440003'

-- 1) Advertiser (Test Agency)
INSERT INTO advertisers (id, advertiser_type, legal_name, brand_name, slug, tax_number, license_number, email, phone, website_url, is_active, created_at, updated_at, verification_status)
VALUES (:'ADV_ID'::uuid, 'agency', 'Properia Test Agency LTDA', 'Properia Test', 'properia-test-seed', '123456789', 'LIC-2024-001', 'agencia@properia-test.pt', '+351 910 000 001', 'https://properia-test.pt', true, now(), now(), 'verified')
ON CONFLICT DO NOTHING;

-- 2) Users (app_users)
INSERT INTO app_users (id, email, full_name, role, is_active, locale, created_at, updated_at)
VALUES
  (:'USER1_ID'::uuid, 'gerente@properia-test.pt', 'João Gerente', 'advertiser', true, 'pt-PT', now(), now()),
  (:'USER2_ID'::uuid, 'agente1@properia-test.pt', 'Maria Agente', 'advertiser', true, 'pt-PT', now(), now()),
  (:'USER3_ID'::uuid, 'agente2@properia-test.pt', 'Pedro Sales', 'advertiser', true, 'pt-PT', now(), now())
ON CONFLICT DO NOTHING;

-- Auth identities (password: TestDemo1234!)
INSERT INTO user_auth_identities (user_id, provider, provider_user_id, email, email_verified, password_hash, password_algorithm, created_at, updated_at)
VALUES
  (:'USER1_ID'::uuid, 'local', 'gerente@properia-test.pt', 'gerente@properia-test.pt', true, '$2b$10$qOW3F7X8Y2J5.K9L6M3N2.OiP4Q5R6S7T8U9V0W1X2Y3Z4A5B6C7D', 'bcrypt', now(), now()),
  (:'USER2_ID'::uuid, 'local', 'agente1@properia-test.pt', 'agente1@properia-test.pt', true, '$2b$10$qOW3F7X8Y2J5.K9L6M3N2.OiP4Q5R6S7T8U9V0W1X2Y3Z4A5B6C7D', 'bcrypt', now(), now()),
  (:'USER3_ID'::uuid, 'local', 'agente2@properia-test.pt', 'agente2@properia-test.pt', true, '$2b$10$qOW3F7X8Y2J5.K9L6M3N2.OiP4Q5R6S7T8U9V0W1X2Y3Z4A5B6C7D', 'bcrypt', now(), now())
ON CONFLICT DO NOTHING;

-- 3) Team membership
INSERT INTO advertiser_users (advertiser_id, user_id, membership_role, created_at)
VALUES
  (:'ADV_ID'::uuid, :'USER1_ID'::uuid, 'admin', now()),
  (:'ADV_ID'::uuid, :'USER2_ID'::uuid, 'editor', now()),
  (:'ADV_ID'::uuid, :'USER3_ID'::uuid, 'sales', now())
ON CONFLICT DO NOTHING;

-- 4) Listings — varied properties to test matching across zones/types/budget
INSERT INTO listings (id, public_id, advertiser_id, status, business_type, property_type, title, title_normalized, description_short, price_amount, price_currency, bedrooms, bathrooms, suites, usable_area_m2, city, district, parish, neighborhood, postal_code, latitude, longitude, hero_image_url, is_immediately_available, created_at, updated_at)
VALUES
  -- Porto — apartments (T2, T3), central zone, price 150-250k
  (gen_random_uuid(), 'TEST-PORT-APT-001', :'ADV_ID'::uuid, 'published', 'sale', 'apartment', 'Apartamento T2 no Porto Centro', 'apartamento t2 no porto centro', 'T2 com varanda e boa luz', 175000, 'EUR', 2, 1, 0, 85, 'Porto', 'Porto', 'Cedofeita', 'Cedofeita', '4050-171', 41.1617, -8.6433, 'https://images.unsplash.com/photo-1560448204-e02f11c3d0e2?auto=format&fit=crop&w=1280&q=80', true, now(), now()),
  (gen_random_uuid(), 'TEST-PORT-APT-002', :'ADV_ID'::uuid, 'published', 'sale', 'apartment', 'Apartamento T3 com varanda', 'apartamento t3 com varanda', 'Espaçoso T3, 110m², perto da Livraria Lello', 220000, 'EUR', 3, 2, 0, 110, 'Porto', 'Porto', 'Miragaia', 'Ribeira', '4050-501', 41.1449, -8.6291, 'https://images.unsplash.com/photo-1493663284031-b7e3aefcae8e?auto=format&fit=crop&w=1280&q=80', true, now(), now()),

  -- Braga — houses (T3, T4), tranquil zone, price 200-300k
  (gen_random_uuid(), 'TEST-BRG-HOUSE-001', :'ADV_ID'::uuid, 'published', 'sale', 'house', 'Moradia T3 em Braga', 'moradia t3 em braga', 'Moradia moderna com jardim, 150m² útil', 240000, 'EUR', 3, 2, 1, 150, 'Braga', 'Braga', 'São Victor', 'São Victor', '4700-366', 41.5505, -8.4269, 'https://images.unsplash.com/photo-1560448204-e02f11c3d0e2?auto=format&fit=crop&w=1280&q=80', true, now(), now()),
  (gen_random_uuid(), 'TEST-BRG-HOUSE-002', :'ADV_ID'::uuid, 'published', 'sale', 'house', 'Moradia T4 com piscina', 'moradia t4 com piscina', 'Propriedade de luxo, 4 quartos, piscina', 380000, 'EUR', 4, 3, 2, 200, 'Braga', 'Braga', 'Maximinos', 'Maximinos', '4700-315', 41.5550, -8.4190, 'https://images.unsplash.com/photo-1502672260266-1c1ef2d93688?auto=format&fit=crop&w=1280&q=80', true, now(), now()),

  -- Matosinhos — beach apartments (T2, studio), premium zone, price 120-180k
  (gen_random_uuid(), 'TEST-MAT-APT-001', :'ADV_ID'::uuid, 'published', 'sale', 'studio', 'Estúdio junto à praia', 'estudio junto a praia', 'T0 moderno com vista parcial para o mar', 120000, 'EUR', 0, 1, 0, 45, 'Matosinhos', 'Porto', 'Matosinhos', 'Praia', '4450-677', 41.1804, -8.6895, 'https://images.unsplash.com/photo-1554995207-c18c203602cb?auto=format&fit=crop&w=1280&q=80', true, now(), now()),
  (gen_random_uuid(), 'TEST-MAT-APT-002', :'ADV_ID'::uuid, 'published', 'sale', 'apartment', 'Apartamento T2 Matosinhos', 'apartamento t2 matosinhos', 'T2 luminoso com terraço, 5min a pé da praia', 165000, 'EUR', 2, 1, 0, 75, 'Matosinhos', 'Porto', 'Matosinhos', 'Vilar do Conde', '4450-100', 41.2060, -8.6982, 'https://images.unsplash.com/photo-1600566753086-00f18fb6b3ea?auto=format&fit=crop&w=1280&q=80', true, now(), now()),

  -- Viana do Castelo — houses (T3), calm zone, price 180-220k
  (gen_random_uuid(), 'TEST-VDC-HOUSE-001', :'ADV_ID'::uuid, 'published', 'sale', 'house', 'Moradia T3 Viana do Castelo', 'moradia t3 viana do castelo', 'Casa tradicional reformada, arredores tranquilos', 195000, 'EUR', 3, 2, 0, 130, 'Viana do Castelo', 'Viana do Castelo', 'Afife', 'Afife', '4730-225', 41.7071, -8.8315, 'https://images.unsplash.com/photo-1502672260266-1c1ef2d93688?auto=format&fit=crop&w=1280&q=80', true, now(), now()),

  -- Guarda — mountain apartments (T2), price 110k
  (gen_random_uuid(), 'TEST-GDR-APT-001', :'ADV_ID'::uuid, 'published', 'sale', 'apartment', 'Duplex na Guarda', 'duplex na guarda', 'T2 no coração da Guarda, ótima exposição solar', 110000, 'EUR', 2, 1, 0, 95, 'Guarda', 'Guarda', 'Guarda', 'Centro', '6300-750', 40.5342, -7.2698, 'https://images.unsplash.com/photo-1560448204-e02f11c3d0e2?auto=format&fit=crop&w=1280&q=80', true, now(), now()),

  -- Vila Real — rural property (T2), price 95k
  (gen_random_uuid(), 'TEST-VRL-HOUSE-001', :'ADV_ID'::uuid, 'published', 'sale', 'house', 'Propriedade rural Vila Real', 'propriedade rural vila real', 'Casa com terreno anexo, ideal para projeto', 95000, 'EUR', 2, 1, 0, 120, 'Vila Real', 'Vila Real', 'Folhadela', 'Folhadela', '5000-570', 41.2923, -7.7422, 'https://images.unsplash.com/photo-1484101403633-562f891dc89a?auto=format&fit=crop&w=1280&q=80', true, now(), now()),

  -- Extra Porto premium apartment for diversification
  (gen_random_uuid(), 'TEST-PORT-APT-003', :'ADV_ID'::uuid, 'published', 'sale', 'apartment', 'Apartamento Premium Porto', 'apartamento premium porto', 'T3 luxo, 130m², vista rio Douro', 320000, 'EUR', 3, 2, 1, 130, 'Porto', 'Porto', 'Francos', 'Francos', '4050-260', 41.1550, -8.6200, 'https://images.unsplash.com/photo-1493663284031-b7e3aefcae8e?auto=format&fit=crop&w=1280&q=80', true, now(), now());

-- 5) Buyer profiles with varied criteria for matching
INSERT INTO buyer_profiles (id, advertiser_id, assigned_to_user_id, name, email, phone, criteria, budget_bracket, budget_approval, urgency, situation, status, created_at, updated_at)
VALUES
  -- Buyer 1: Porto/Matosinhos, T2, 120-150k, ready to close
  (gen_random_uuid(), :'ADV_ID'::uuid, :'USER1_ID'::uuid, 'Comprador Porto 1', 'comp1@test.pt', '+351 910 000 101',
   '{"zones":["Porto","Matosinhos"],"propertyTypes":["apartment"],"minBedrooms":2,"maxBedrooms":2,"minArea":70,"maxArea":90}'::jsonb,
   '100_150k', 'approved', 'ready_to_close', 'buyer_only', 'active', now(), now()),

  -- Buyer 2: Braga, house T3+, 250-300k, actively looking
  (gen_random_uuid(), :'ADV_ID'::uuid, :'USER2_ID'::uuid, 'Comprador Braga', 'comp2@test.pt', '+351 910 000 102',
   '{"zones":["Braga"],"propertyTypes":["house"],"minBedrooms":3,"minArea":140}'::jsonb,
   '250_300k', 'in_progress', 'active', 'buyer_only', 'active', now(), now()),

  -- Buyer 3: Porto or Viana, flexible type, T3, 200-250k, exploring
  (gen_random_uuid(), :'ADV_ID'::uuid, :'USER3_ID'::uuid, 'Comprador Norte Aberto', 'comp3@test.pt', '+351 910 000 103',
   '{"zones":["Porto","Viana do Castelo"],"propertyTypes":["apartment","house"],"minBedrooms":3,"maxArea":150}'::jsonb,
   '200_250k', 'none', 'exploring', 'also_selling', 'active', now(), now()),

  -- Buyer 4: budget-conscious, Guarda/Vila Real, T1-T3, under 100k
  (gen_random_uuid(), :'ADV_ID'::uuid, :'USER1_ID'::uuid, 'Comprador Económico', 'comp4@test.pt', '+351 910 000 104',
   '{"zones":["Guarda","Vila Real"],"propertyTypes":["apartment","house"],"minBedrooms":1,"maxBedrooms":3,"maxArea":120}'::jsonb,
   'under_100k', 'approved', 'active', 'buyer_only', 'active', now(), now()),

  -- Buyer 5: premium, Braga/Porto, T4+, 400k+
  (gen_random_uuid(), :'ADV_ID'::uuid, :'USER2_ID'::uuid, 'Comprador Premium', 'comp5@test.pt', '+351 910 000 105',
   '{"zones":["Braga","Porto"],"propertyTypes":["house","villa"],"minBedrooms":4,"minArea":180}'::jsonb,
   '400_500k', 'approved', 'ready_to_close', 'buyer_only', 'active', now(), now());

SELECT 'Test seed for buyer-listing matching Phase 1 completed successfully.' as result;
