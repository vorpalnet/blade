SELECT destination_uri, priority, carrier
FROM routing_table
WHERE called_number = '${user}'
  AND active = 1
ORDER BY priority
