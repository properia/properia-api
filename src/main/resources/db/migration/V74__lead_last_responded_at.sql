-- Bug: responder no Chat não limpava "Fora do prazo" na página de Leads.
--
-- O slaBucket (fresh/attention/late) era calculado a partir de leads.created_at —
-- a IDADE do lead. Nenhuma resposta (chat, chamada registada, visita) o podia
-- limpar: um lead com 3 dias ficava "Fora do prazo" para sempre, mesmo com 10
-- respostas enviadas. Só won/lost o tiravam de lá.
--
-- Passa a existir leads.last_responded_at = momento da última resposta REAL da
-- equipa ao comprador. O SLA passa a medir o tempo desde essa marca (ou desde a
-- criação, se ainda não houve resposta), que é a definição que a UI já promete
-- ("Sem resposta há X").
--
-- Mantida por TRIGGER e não por código de serviço: qualquer caminho de escrita
-- (chat, endpoint de respostas, importações, backoffice futuro) atualiza a marca
-- automaticamente. Manter isto à mão no ChatService é precisamente a classe de
-- bug que estamos a corrigir — bastava um novo endpoint esquecer-se.

ALTER TABLE "properia"."leads"
  ADD COLUMN IF NOT EXISTS "last_responded_at" timestamp with time zone;

COMMENT ON COLUMN "properia"."leads"."last_responded_at" IS
  'Última resposta da equipa ao comprador (mensagem de chat não-interna ou lead_responses). NULL = nunca respondido. Mantido por trigger.';

-- ── Backfill a partir do histórico já existente ─────────────────────────────
-- Notas internas (is_internal = true) NÃO contam: são para a equipa, o comprador
-- nunca as vê, logo não são resposta nenhuma.
WITH last_touch AS (
    SELECT lead_id, MAX(created_at) AS ts
    FROM (
        SELECT lead_id, created_at
        FROM "properia"."chat_messages"
        WHERE lead_id IS NOT NULL
          AND sender_type::text = 'advertiser_member'
          AND is_internal = false
        UNION ALL
        SELECT lead_id, created_at
        FROM "properia"."lead_responses"
    ) t
    GROUP BY lead_id
)
UPDATE "properia"."leads" l
SET last_responded_at = lt.ts
FROM last_touch lt
WHERE lt.lead_id = l.id
  AND (l.last_responded_at IS NULL OR l.last_responded_at < lt.ts);

-- ── Manutenção automática ───────────────────────────────────────────────────
-- Nunca recua a marca: uma inserção com created_at antigo (import/backfill) não
-- pode fazer um lead recente voltar a parecer por responder.

-- Chat: só mensagens da equipa para o comprador (não 'buyer', não 'system',
-- não notas internas).
CREATE OR REPLACE FUNCTION "properia"."touch_lead_on_chat_message"()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.lead_id IS NULL
       OR NEW.sender_type::text <> 'advertiser_member'
       OR NEW.is_internal THEN
        RETURN NEW;
    END IF;

    UPDATE "properia"."leads"
    SET last_responded_at = NEW.created_at,
        updated_at = now()
    WHERE id = NEW.lead_id
      AND (last_responded_at IS NULL OR last_responded_at < NEW.created_at);

    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION "properia"."touch_lead_on_response"()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.lead_id IS NULL THEN
        RETURN NEW;
    END IF;

    UPDATE "properia"."leads"
    SET last_responded_at = NEW.created_at,
        updated_at = now()
    WHERE id = NEW.lead_id
      AND (last_responded_at IS NULL OR last_responded_at < NEW.created_at);

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS "trg_chat_message_touches_lead" ON "properia"."chat_messages";
CREATE TRIGGER "trg_chat_message_touches_lead"
    AFTER INSERT ON "properia"."chat_messages"
    FOR EACH ROW
    EXECUTE FUNCTION "properia"."touch_lead_on_chat_message"();

-- Respostas comerciais registadas à mão (chamada, email, "marcar como respondida").
DROP TRIGGER IF EXISTS "trg_lead_response_touches_lead" ON "properia"."lead_responses";
CREATE TRIGGER "trg_lead_response_touches_lead"
    AFTER INSERT ON "properia"."lead_responses"
    FOR EACH ROW
    EXECUTE FUNCTION "properia"."touch_lead_on_response"();

-- Filtro de SLA percorre (advertiser, estado activo, marca de resposta).
CREATE INDEX IF NOT EXISTS "idx_leads_advertiser_last_responded"
  ON "properia"."leads" USING btree ("advertiser_id", "last_responded_at");
