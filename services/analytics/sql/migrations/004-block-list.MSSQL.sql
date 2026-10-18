-- The block list, which the agent console used to create on its first mark
-- and proxy-block's spam-numbers.sql asked to be applied by hand.
-- applied-if: table spam_numbers
CREATE TABLE spam_numbers (
   tn         VARCHAR(15) NOT NULL PRIMARY KEY,
   treatment  VARCHAR(16) DEFAULT 'tarpit' NOT NULL,
   reason     NVARCHAR(64),
   marked_by  NVARCHAR(64),
   marked_at  DATETIME2(3) DEFAULT CURRENT_TIMESTAMP NOT NULL,
   expires_at DATETIME2(3) NOT NULL
);
CREATE INDEX spam_numbers_expires ON spam_numbers (expires_at);
GO
