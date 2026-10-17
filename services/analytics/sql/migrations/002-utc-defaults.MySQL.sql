-- The column defaults read the database's UTC clock (RELEASE.md, "Analytics:
-- UTC throughout"). Setting a default twice is harmless, so there is no
-- applied-if: an adopted database simply runs it. MySQL 8.0.13 or later.
ALTER TABLE applications ALTER created SET DEFAULT (UTC_TIMESTAMP(3));
ALTER TABLE sessions     ALTER created SET DEFAULT (UTC_TIMESTAMP(3));
ALTER TABLE events       ALTER created SET DEFAULT (UTC_TIMESTAMP(3));
