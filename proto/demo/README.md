# BLADE Demo Launcher (front-end mockup)

A rep-facing web app that launches each BLADE demo from a simple HTML form —
`index.html` *is* the demo matrix (5 pillars × 3 altitudes) and every box links
to a page with the form a sales rep (or SE) actually fills in.

**Status: visualization only.** These are static pages so we can *see* what the
demos look like to the rep before building anything. There is **no SIP plumbing
behind them yet** — submitting a form shows a storyboard of what *would* happen,
not a real call. The layout (`src/main/webapp/`) matches a future OCCAS WAR, so
the servlets/callflows drop in later without moving files.

## Look at it

Open `src/main/webapp/index.html` in a browser. Everything is relative-linked,
so it works straight off the filesystem — no server needed for the mockup.

## Layout

```
src/main/webapp/
  index.html            # the demo matrix / cheat sheet (5 pillars x 3 altitudes).
                        #   Each READY box highlights on hover and links to its demo page.
  assets/
    demo.css            # shared design system for the demo pages (light + dark)
    demo.js             # shared: intercepts submit, shows the storyboard + a "not wired yet" note
  <demo>.html           # one page per demo: description + the rep's form + "what the rep sees".
                        #   Their "All demos" link goes back to index.html (the cheat sheet).
```

`index.html` is self-contained (its own inline styles). The per-demo pages share
`assets/demo.css` / `demo.js`. Boxes without a page yet (Planned/roadmap) are not
linked — add a `<demo>.html` and wire its box to light it up.

## When we wire it up

Each `<demo>.html` form will POST to a servlet in the demo WAR that kicks off the
real call/scenario (e.g. click-to-call -> the `tpcc` service; call-blocking ->
an iRouter/FSMAR rule). The storyboard panel becomes the live result view.
