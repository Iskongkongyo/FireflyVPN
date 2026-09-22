ALTER TABLE subscription_sources
ADD COLUMN merge_mode TEXT NOT NULL DEFAULT 'external_first'
CHECK (merge_mode IN (
  'external_first',
  'managed_first',
  'interleave_external_first',
  'interleave_managed_first'
));
