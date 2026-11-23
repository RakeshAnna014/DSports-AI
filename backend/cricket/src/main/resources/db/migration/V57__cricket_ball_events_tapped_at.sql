-- Offline scoring (step 2f-3): when the scorer says they tapped a ball.
--
-- A phone that scores with no signal sends its balls later; created_at is when the SERVER received each one (and stays the only time any rule, the 24-hour
-- correction window or the ordering uses: the seq orders events). tapped_at is the phone's own clock, kept so that the timeline and the commentary of an offline
-- ball show when it was really bowled. It is for display only. It is nullable (every existing row and every online ball has none) and the database itself
-- refuses a value later than the moment the server received the event, so a wrong phone clock can never place a ball in the future.
--
-- This is a plain ADD COLUMN: the append-only triggers (row-level for UPDATE and DELETE, statement-level for TRUNCATE) are untouched and keep refusing.
ALTER TABLE ball_events ADD COLUMN tapped_at TIMESTAMPTZ;
ALTER TABLE ball_events ADD CONSTRAINT ck_ball_events_tapped_at CHECK (tapped_at IS NULL OR tapped_at <= created_at);
