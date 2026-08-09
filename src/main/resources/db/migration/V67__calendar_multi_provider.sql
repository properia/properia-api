-- Normaliza o valor de provider em user_calendar_connections de 'google_calendar' (usado na
-- V66, antes de existir mais do que um provider) para 'google', e passa a aceitar 'microsoft'
-- também — suporte a Outlook/Hotmail/Microsoft 365 (MicrosoftCalendarService).
--
-- Nota: NÃO mexe em properia.advertiser_calendar_connections (ligação de agência, sala de
-- Meet) — essa continua Google-only, com o enum advertiser_calendar_provider próprio.
UPDATE properia.user_calendar_connections SET provider = 'google' WHERE provider = 'google_calendar';

ALTER TABLE properia.user_calendar_connections
    ADD CONSTRAINT user_calendar_connections_provider_check CHECK (provider IN ('google', 'microsoft'));
