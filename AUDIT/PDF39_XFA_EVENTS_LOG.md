# pdf-39: XFA validate and preSave / prePrint / preSubmit events

| Row | Status | Where |
|---|---|---|
| XFA `<validate>` script test | DONE | `XScripts.validate` / `validateMessage` (XfaTemplate), `XfaJs.runValidate`, `XfaEventRuntime.validate`. Run after leaving a field of a generated XFA form; a test returning false marks the field (`Issue.Script`) with the form's own message |
| XFA `validate` event | DONE | Event scripts with activity `validate` run before the test |
| XFA preSave | DONE | `FormFillState.flushOnSave` → `xfaFormEvent("preSave")`: values the scripts set are saved; `xfa.event.cancelAction` keeps the values pending and says so |
| XFA prePrint | DONE | `FormActions.print`: runs before printing; a cancel stops the print |
| XFA preSubmit | DONE | `FormActions.submit`: every field's validate test first (as Acrobat does before a submission), then the preSubmit scripts. A failing field gets the focus and its message |

- `XfaJs`: plans get a `Runner` (exec / setEvent / test); `xfa.event.cancelAction` and `xfa.host.messageBox` / `app.alert` texts are returned in `Outcome` (`cancelled`, `messages`, `invalid`). A script's message is shown in the form bar.
- A subform's scripts now resolve its own children by name (form events usually sit on the root subform).
- Scope: forms generated from XFA (the app's XFA fill path); FormCalc scripts are still not run (as before).
- Test: `XfaFormEventsTest`.
