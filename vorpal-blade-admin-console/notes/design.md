Thoughts on configuration files...

./config/custom/vorpal
  global configs go here

./config/custom/vorpal/cluster/<clusterName>
  cluster configs go here
  
./config/custom/vorpal/server/<serverName>
  server configs go here
  
Config preference is:
1) server -- specific to an individual server
2) cluster -- specific to a whole cluster
3) global -- applies to all applications

It would be clever to merge the configs together, to reduce copy & pasting errors.
Could we use features in Jackson to merge JSON files?
I worry about lists or sets of data. Need to merge the lists and not just replace.

Let's see if it can be done...?