-- ─────────────────────────────────────────────────────────────────────────────
-- Novo tipo de imóvel: andar de moradia.
--
-- Um andar de moradia é um piso autónomo de uma moradia, com entrada própria e
-- muitas vezes logradouro ou terraço. Não é um apartamento (não há prédio nem
-- condomínio no sentido corrente) nem uma moradia (não se compra a casa inteira).
-- É um tipo corrente no Grande Porto e vem identificado como tal nas fichas das
-- agências.
--
-- Sem tipo próprio, o anúncio teria de ser publicado como "Moradia" — prometendo
-- ao comprador uma casa inteira — ou como "Apartamento", escondendo o logradouro
-- e a entrada independente. Nenhuma das duas descreve o que está à venda.
--
-- ALTER TYPE ... ADD VALUE é aditivo: não altera nenhuma linha existente nem
-- invalida os valores já usados.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TYPE "properia"."property_type" ADD VALUE IF NOT EXISTS 'andar_moradia';
