# Putting the listener in the chain

The listener hears the calls the App Router routes through it, so it hears
nothing until `fsmar.json` sends it something. Two additions, both yours to make:

Route the calls you want heard to it, in whichever state they arrive at:

```json
{
  "id": "INV-listener",
  "when": "${To.user} matches 'record.*'",
  "next": "listener"
}
```

Then give the `listener` state its downstream hop, because the listener is a
B2BUA and the call carries on past it:

```json
"listener": {
  "triggers": {
    "INVITE": {
      "transitions": [ { "next": "<wherever the call was going>" } ]
    }
  }
}
```

The `when` is the only policy decision here: a call the App Router does not send
to this application is a call it does not record, transcribe or score, and there
is no setting inside the application that overrides that. What it does with a
call it is sent is its settings' business: `record`, `transcribe`,
`publishUtterances` and `scoreVoices` are independent.

## Verifying it took

FSMAR logs the decision, and it routes **past** an undeployed application without
raising anything:

```
FSMAR INVITE sip:record100@... previous=null -> matched=INV-listener -> listener (app listener)
```

If the listener is not deployed you get `matched=null` and the next state
instead, with no error, so check `Application(s) deployed: [listener]` in
`fsmar.0.log` before concluding the routing is wrong.
