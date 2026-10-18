# Third-party code in the Dashboard

| File | Project | Version | License |
| --- | --- | --- | --- |
| `d3.min.js` | [D3](https://d3js.org), Mike Bostock | 7.9.0 | ISC, in `LICENSE-d3.txt` |
| `topojson-client.min.js` | [topojson-client](https://github.com/topojson/topojson-client), Mike Bostock | 3.1.0 | ISC, in `LICENSE-topojson-client` |
| `land-110m.json` | [world-atlas](https://github.com/topojson/world-atlas), Mike Bostock, from Natural Earth | the Balancer's copy | ISC, in `LICENSE-world-atlas` |
| `states-albers-10m.json` | [us-atlas](https://github.com/topojson/us-atlas), Mike Bostock, from US Census Bureau cartographic boundaries | the Balancer's copy | ISC, in `LICENSE-us-atlas` |

`d3.min.js` is the unmodified `dist/d3.min.js` from the `d3@7.9.0` npm package
(SHA-256 `f2094bbf6141b359722c4fe454eb6c4b0f0e42cc10cc7af921fc158fceb86539`). It is
served from this WAR, never a CDN, so the dashboard works on a network with no
internet access.

The map files are the same copies the Balancer serves from `map/`. The atlas is
already projected (Albers USA, 975 × 610, Alaska and Hawaii inset), so the map
needs `d3.geoPath()` with no projection.
