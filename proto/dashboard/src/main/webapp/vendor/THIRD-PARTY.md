# Third-party code in the Dashboard

| File | Project | Version | License |
| --- | --- | --- | --- |
| `d3.min.js` | [D3](https://d3js.org), Mike Bostock | 7.9.0 | ISC, in `LICENSE-d3.txt` |

`d3.min.js` is the unmodified `dist/d3.min.js` from the `d3@7.9.0` npm package
(SHA-256 `f2094bbf6141b359722c4fe454eb6c4b0f0e42cc10cc7af921fc158fceb86539`). It is
served from this WAR, never a CDN, so the dashboard works on a network with no
internet access.
