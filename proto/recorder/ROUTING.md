# Putting the recorder in the chain

The recorder records the calls the App Router routes through it, so it records
nothing until `fsmar.json` sends it something. Two additions, both yours to make:

Route the calls you want recorded to it, in whichever state they arrive at:

```json
{
  "id": "INV-recorder",
  "when": "${To.user} matches 'record.*'",
  "next": "recorder"
}
```

Then give the `recorder` state its downstream hop, because the recorder is a
B2BUA and the call carries on past it:

```json
"recorder": {
  "triggers": {
    "INVITE": {
      "transitions": [ { "next": "<wherever the call was going>" } ]
    }
  }
}
```

The `when` is the only policy decision here: a call the App Router does not send
to this application is a call it does not record, and there is no setting inside
the application that overrides that.

## Verifying it took

FSMAR logs the decision, and it routes **past** an undeployed application without
raising anything:

```
FSMAR INVITE sip:record100@... previous=null -> matched=INV-recorder -> recorder (app recorder)
```

If the recorder is not deployed you get `matched=null` and the next state
instead, with no error, so check `Application(s) deployed: [recorder]` in
`fsmar.0.log` before concluding the routing is wrong.
