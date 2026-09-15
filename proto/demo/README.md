# BLADE Demo Launcher

A static web app, `blade-demo.war` (context-root `blade/demo`), with one page per BLADE
demo. `index.html` lists the demos, and each entry links to a page that describes the demo
and holds its input form.

The pages are HTML, CSS and JavaScript with no server-side code. Submitting a demo form
does not place a call: it shows a storyboard of the call flow the demo exercises. The
guided demo (`guided.html`) runs in mock mode by default and simulates each REST response.
With `?mode=live` it calls the service the flow names instead (the default flow drives
`tpcc`), which needs that WAR deployed and CORS enabled (`-Dblade.cors.allowedOrigins`).

## Open it

Open `src/main/webapp/index.html` in a browser. All links are relative, so the pages work
straight off the filesystem.

To serve it from OCCAS, build with `./build.sh` and deploy `blade-demo.war` from
`dist/proto/` to the AdminServer (`./deploy.sh <env> blade-demo.war AdminServer`). The
`blade/` context-root gives it a card on the [Admin Portal](../../admin/portal/README.md).
As a `proto/` app it ships loose and is never bundled in an EAR.

## Layout

```
src/main/webapp/
  index.html            the demo index (self-contained, inline styles)
  <demo>.html           one page per demo: description, form, storyboard
  guided.html           the guided flow player
  assets/
    demo.css            shared styles for the demo pages (light and dark)
    demo.js             intercepts form submit and shows the storyboard
    guided.css          styles for the guided flow player
    guided.js           runs a flow step by step, in mock or live mode
    flows.js            the flow definitions the guided player runs
```
