-- S4: Append-only audit log for sensitive administrative operations, written atomically
-- inside the same database transaction as the underlying mutation.
CREATE TABLE audit_log (
    id          uuid        PRIMARY KEY,
    actor_id    uuid        NOT NULL CONSTRAINT fk_audit_log_actor REFERENCES account (id),
    action      varchar(64) NOT NULL CHECK (action IN (
        'ADMIN_CREATED',
        'ADMIN_PASSWORD_CHANGED',
        'SHOWTIME_CREATED',
        'SHOWTIME_CANCELLED',
        'SHOWTIME_DELETED',
        'CINEMA_CREATED',
        'CINEMA_UPDATED',
        'CINEMA_DELETED',
        'AUDITORIUM_CREATED',
        'AUDITORIUM_DELETED',
        'MOVIE_CREATED',
        'MOVIE_UPDATED',
        'MOVIE_DELETED'
    )),
    target_type varchar(32) NOT NULL CHECK (target_type IN ('ACCOUNT', 'SHOWTIME', 'CINEMA', 'AUDITORIUM', 'MOVIE')),
    target_id   uuid        NOT NULL,
    details     jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at  timestamptz NOT NULL
);

CREATE INDEX ix_audit_log_created_at_id ON audit_log (created_at DESC, id DESC);
CREATE INDEX ix_audit_log_target ON audit_log (target_type, target_id, created_at DESC);
CREATE INDEX ix_audit_log_actor_id ON audit_log (actor_id, created_at DESC);

-- Enforce append-only semantics at the database layer so audit entries cannot be altered or deleted.
CREATE OR REPLACE FUNCTION prevent_audit_log_mutation()
RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only; % operations are not permitted', TG_OP
        USING ERRCODE = '55000';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_log_append_only
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW
    EXECUTE FUNCTION prevent_audit_log_mutation();
