-- One row per phone: its push token, the alerts it watches, and what it's been told.
CREATE TABLE devices (
  token TEXT PRIMARY KEY,
  environment TEXT NOT NULL CHECK (environment IN ('sandbox', 'production')),
  time_zone TEXT NOT NULL,
  alerts TEXT NOT NULL,
  state TEXT NOT NULL,
  updated_at INTEGER NOT NULL
);

-- The APNs provider token, reused for 50 minutes (APNs refuses new ones more often than every 20).
CREATE TABLE meta (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL,
  updated_at INTEGER NOT NULL
);
