-- The column defaults read the database's UTC clock (RELEASE.md, "Analytics:
-- UTC throughout"). Setting a default twice is harmless, so there is no
-- applied-if: an adopted database simply runs it.
ALTER TABLE applications MODIFY (created DEFAULT SYS_EXTRACT_UTC(SYSTIMESTAMP));
ALTER TABLE sessions     MODIFY (created DEFAULT SYS_EXTRACT_UTC(SYSTIMESTAMP));
ALTER TABLE events       MODIFY (created DEFAULT SYS_EXTRACT_UTC(SYSTIMESTAMP));
