-- ball_events is append-only. V55's row-level trigger refuses UPDATE and DELETE, but PostgreSQL does not fire row-level triggers for TRUNCATE.
-- This statement-level trigger closes that gap: TRUNCATE on the log (alone, or through TRUNCATE ... CASCADE from matches) is refused.
-- A database superuser can still disable triggers; that is outside what the application can prevent.

CREATE TRIGGER trg_ball_events_no_truncate
    BEFORE TRUNCATE ON ball_events
    FOR EACH STATEMENT EXECUTE FUNCTION refuse_ball_event_change();
