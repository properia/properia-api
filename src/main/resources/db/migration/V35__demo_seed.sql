-- ============================================================
-- PROPERIA — V35
--
-- Este ficheiro continha o seed de demonstração (utilizadores, imóveis, leads e
-- visitas fictícios). Esse conteúdo foi removido em 2026-08-12, quando a base de
-- produção foi limpa para o onboarding do primeiro cliente real: dados de demo
-- em migrações voltam a aparecer sempre que se cria um ambiente novo, e a partir
-- do momento em que existem clientes reais isso é ruído perigoso — mistura-se
-- com inventário verdadeiro e é indistinguível numa listagem.
--
-- O que NÃO foi removido é a criação da tabela `listing_images`, abaixo. É
-- schema real, usado por ListingImagesController/JpaListingImagesRepository, e
-- apagá-la impediria o arranque de qualquer base de dados nova (o Hibernate
-- corre com ddl-auto: validate).
--
-- Nota: a alteração do conteúdo muda o checksum desta migração. O projeto corre
-- com `spring.flyway.repair-on-migrate: true`, que realinha checksums no
-- arranque — foi verificado contra um Postgres 16 com o histórico já aplicado.
-- ============================================================

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
