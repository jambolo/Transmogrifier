# Product copy

Copy specification, not an applied UI change. “Replacement” rows fit existing concepts. “Proposed” rows need UI or state work before use. Preserve technical details behind a clearly labeled disclosure rather than hiding useful diagnostics.

## First use

| Placement | Copy | Status |
| --- | --- | --- |
| Welcome headline | Little apps. For right now. | Proposed welcome surface |
| Welcome body | A scorekeeper for tonight. A checklist for the weekend. Describe a little app you'd like to use. | Proposed |
| Current-build setup disclosure | This early version uses Claude to make apps. You'll need a Claude API key, and Claude charges your account for creating and changing apps. A Claude subscription doesn't include this API usage. | Proposed; show before setup |
| Setup heading | Connect your AI service | Proposed grouping |
| Key label | Claude API key | Replacement label |
| Key explanation | A connection key from your Claude Console account. Paste it here to let Transmogrifier make and change apps. | Proposed helper |
| Test action | Check connection | Replacement for Test |
| Successful test | Connected. Your key works. | Replacement; only after a passing test |
| More settings | Advanced settings | Proposed grouping; requires UI change |

Do not claim that connection testing is free unless verified. Avoid asking users to choose a model before explaining why it matters. The current build has no managed consumer billing flow; no price, free tier, or included credit balance is invented here.

## Create and use

| State or action | Suggested copy | Status |
| --- | --- | --- |
| Apps screen title | Your apps | Replacement |
| Empty title | What needs a little app? | Proposed split empty state |
| Empty body | Start with something small. A scorekeeper, a packing list, a lunch picker. | Replacement empty-state copy |
| Create action | New app | Retain |
| Name field | App name | Replacement |
| Name helper | Something you'll recognize later. | Proposed |
| First description label | What should your app do? | Proposed distinct first-request state |
| Existing shared input hint | Describe an app or a change | Retain |
| Send action | Send | Retain; works for both creation and changes |
| New-app action alternative | Make app | Proposed; only if the first-request state is distinguished |
| Progress | Making your app… | Proposed; do not replace detailed progress with fictional milestones |
| Generation finished | Ready to try. | Proposed; only after generation finishes, not a correctness claim |
| Open result | Open app | Replacement for Run |
| Follow-up label | What would you like to change? | Proposed distinct follow-up state |
| Follow-up example | Make the buttons bigger. | Proposed helper |
| Stop action | Stop | Retain |
| Stop notice | Stopped. You can continue when you're ready. | Replacement; only while resumption is available |
| Resume action | Continue | Retain |

Suggested example descriptions are copy content, not functioning templates:

1. “A scorekeeper for two teams, with big plus and minus buttons.”
2. “A packing checklist for a weekend away. Let me add and check off items.”
3. “A lunch picker. I'll enter the choices, and it picks one at random.”
4. “A counter for laps, with a big button and a reset button.”

## Recovery

| Condition | Message | Action |
| --- | --- | --- |
| Connection interrupted | The connection was interrupted. Check your internet connection, then continue. | Continue, when available |
| Missing or rejected key | Your AI service couldn't accept this key. Check it in Settings. | Open settings |
| Provider busy and scheduled retry confirmed | The AI service is busy. Trying again in {seconds} seconds. | Stop |
| Request step limit | This request reached its step limit. You can continue or ask for a smaller change. | Continue |
| Output limit | The reply was too long to finish. Ask for a smaller change or adjust the limit in Settings. | Open settings |
| App crashes | This app stopped unexpectedly. Reload to try opening it again. | Reload |
| Fix action | Try to fix it | Replacement for Fix errors; avoids guaranteeing success |
| Fix helper | Sends error details and relevant app context to your AI service. Usage charges apply. | Proposed helper before paid repair |
| Declined request | The AI service couldn't help with this request. Try a different description. | Return to input |
| Unclassified error | This request couldn't finish. | Show error details; show Continue only if resumable |

Detailed error codes may remain under “Error details.” Do not promise rollback, intact saved data, or a free retry without corresponding implementation and verification.

## Keep and delete

| Placement | Copy |
| --- | --- |
| Lifecycle explanation | Your apps stay until you delete them. |
| Rename action | Rename |
| Delete action | Delete app |
| Confirmation title | Delete “{appName}”? |
| Confirmation body | This removes the app, its files, and its conversation from this device. This can't be undone. |
| Confirm button | Delete app |
| Cancel button | Keep app |

The inspected delete method removes the project directory. It does not establish comprehensive erasure of WebView storage, backups, or provider-side records. Do not use “all traces erased,” “your data is gone everywhere,” or “securely wiped.” Do not add a fake undo action.

## Accessibility and localization

Keep a visible label above inputs; placeholders do not replace labels. Give icon buttons action names such as “Open app” and “Settings.” Announce progress changes without reading every streamed token. Errors must include text and an action, not color alone.

Allow text wrapping and at least 30% copy expansion. Use whole localized sentences with named or positional placeholders. Put user-chosen names in quotation marks, but never concatenate sentence fragments around them. Use Android plural resources for counts. The brand name stays unchanged.
