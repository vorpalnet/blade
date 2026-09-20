-- Numbers agents marked as spam, for as long as the mark lasts.
-- ${ani} is bound as a parameter. Oracle reports the columns as LISTED and
-- TREATMENT, MySQL and PostgreSQL in lower case; the selectors' attributes must match.
SELECT 'block' AS listed, treatment
FROM spam_numbers
WHERE tn = ${ani}
  AND expires_at > CURRENT_TIMESTAMP
