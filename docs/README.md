# Application decks

One HTML slide deck per application, explaining how it works. Written for an engineer
reading it without the source open — the same audience as the application's README, in a
form you can present.

`deck-template.html` is the shell every deck shares: palette, slide chrome, keyboard and
swipe navigation, present mode, the light/dark toggle, and the Vorpal footer. A deck is
that file with its slides filled in.

## Building one

Copy the template, replace the placeholders, write the slides:

| Placeholder | Is |
|---|---|
| `{{TITLE}}` | browser tab and gallery name — a short noun phrase, not a sentence |
| `{{FOOTER}}` | the bottom-left label, e.g. `<b>BLADE</b> · proto/webrtc` |
| `{{YEAR}}` | copyright year |
| `{{EYEBROW}}` `{{HEADLINE}}` `{{STANDFIRST}}` | the title slide |

Then replace the two example `<section class="slide">` blocks. The first slide carries
`class="slide on"`; every other is `class="slide"`. **The slide counter is computed at
runtime**, so slides can be added or removed with nothing else to update.

Decks live beside the application they describe — `proto/webrtc/deck.html` — so a deck
versions with its code and staleness is a comparison against the README next to it.

## Writing the slides

**Build it from the application's README.** The README is the source of truth; the deck is
a rendering of it. Following the README's own section order keeps the deck honest — where
the deck reads oddly, the README reads oddly, and that is worth knowing. Anything that is
true of the app but absent from the README belongs in the README first.

**A heading is a statement, not a topic.** "Signaling is always SIP; only the media varies"
tells the reader something. "Media handling" does not.

**Both themes.** Colours come from the tokens at the top of the file; never write a literal
colour into a slide. Two accents carry meaning — `--sig` for signaling, `--med` for media —
and both are always labelled in words, never colour alone.

**Self-contained.** No external fonts, scripts, images or stylesheets: these are published
under a content policy that blocks every external host, and a linked asset fails silently.
The Vorpal mark is inlined in the shell for that reason.

## Classes worth knowing

| | |
|---|---|
| `.two` | two columns; `.two.l` / `.two.r` shift the weight |
| `.stack` | vertical group inside a column |
| `ul.facts` | bulleted points; add `.m` for the media accent |
| `.lede` `.sub` `.pull` | body text, deck under a heading, emphatic line |
| `.tag` `.tag.s` `.tag.m` | small labels; `<span class="dot"></span>` inside |
| `pre` + `.k .s .n .p .c .b` | code and protocol, with syntax spans |
| `figure` + `figcaption` | wrap diagrams and code that need a caption |

Diagrams are hand-authored inline `<svg>` with a `viewBox`, `role="img"` and an
`aria-label` stating what the picture shows. Label every arrow with the actual message.

## Editing the shell

Change `deck-template.html`, not a deck. A palette or logo change made here is one edit;
made in the decks it is one edit per application, and they drift.
