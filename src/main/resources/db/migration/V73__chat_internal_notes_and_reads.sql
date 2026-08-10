-- ============================================================
-- PROPERIA — V73: Chat — Notas Internas & Estado de Leitura por Utilizador
-- ============================================================

-- Notas internas: mensagens visíveis só à equipa (nunca ao comprador),
-- usadas para dar contexto entre owner/admin/sales sem sair da conversa.
ALTER TABLE "properia"."chat_messages"
  ADD COLUMN IF NOT EXISTS "is_internal" boolean NOT NULL DEFAULT false;

-- Estado de leitura por utilizador (era global por conversa — não dava para
-- cada membro da equipa ter o seu próprio badge de "não lidas"). Substitui a
-- heurística antiga (comparar com a última mensagem do próprio anunciante)
-- por um registo real de "até quando é que eu li".
CREATE TABLE IF NOT EXISTS "properia"."chat_conversation_reads" (
  "conversation_id" uuid NOT NULL REFERENCES "properia"."chat_conversations"("id") ON DELETE CASCADE,
  "user_id" uuid NOT NULL REFERENCES "properia"."app_users"("id") ON DELETE CASCADE,
  "last_read_at" timestamp with time zone NOT NULL DEFAULT now(),
  PRIMARY KEY ("conversation_id", "user_id")
);

CREATE INDEX IF NOT EXISTS "idx_chat_conversation_reads_user"
  ON "properia"."chat_conversation_reads" ("user_id");
