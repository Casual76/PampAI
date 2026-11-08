# Aria — architettura dell'esperienza

User steering, 2026-11-07: implement substantial improvements to organization, placement, space and navigation so controls are intuitive and easy to find again. This extends first-delivery design acceptance; visual polish alone is insufficient. Implementation is authorized. Do not ask for another plan approval or start profile/background before the existing integrated checkpoint.

## Scope and integration order

Finish the in-flight I1 UI correctness fixes and their tests first. Then implement this information-architecture increment in app/feature-assistant with a fresh focused implementation task, preserving runtime/bridge work and the existing two-implementer limit. Re-review the integrated result after both increments. This document is a working implementation brief, not a claim of verified usability or an independent review.

## Stable conversation workspace

- Keep chat as the primary workspace. The compact header identifies the conversation; conversations/navigation at the left and new conversation at the right remain in stable positions.
- The drawer prioritizes new conversation, search and history. App-wide destinations stay in a fixed secondary region rather than consuming the top of the history. Search changes the history results, never removes or moves the persistent navigation. Usage belongs within AI settings; avoid giving occasional administration the same prominence as conversations.
- Keep existing memory/reminder functionality reachable with honest labels. Do not add nonfunctional profile/background destinations or placeholder features. The later profile increment must fit this hierarchy.
- Returning from settings, drawer or a model sheet preserves the current conversation, scroll and pending draft. Navigation must not submit or regenerate a request.

## Composer and conversation options

- The text field, attachment entry, model selector and send/microphone controls keep their order and placement between app and overlay. Response text gets the largest share of the workspace.
- The model selector owns primary model, effort and explicit image helper. Avoid a second reasoning setting hidden among attachments. Preserve the existing capability without an implicit model change: the selected identity remains fixed, effort is explicit and applies to subsequent sends.
- The attachment menu groups camera/photos/files together. Conversation options such as plugin context and a new temporary chat are clearly separated and labelled; they must not look like attachment types. If conversation options move to a header menu, preserve discoverability and current behavior.
- Temporary chat is an explicit new-chat choice, never a toggle that falsely suggests an existing stored conversation can become unstored.
- Keep status concise next to the response. Details and tool traces expand on demand and do not dominate the reading area. Cards use a clear title and concise verified outcome/preview; no raw implementation IDs or redundant large blocks.

## Model picker hierarchy

- Keep search visible, with clear favorite/recent/catalog organization rather than repeating a status label on every row. Use readable model names with exact provider/model identity available as secondary information.
- Show the selected primary model and requested effort clearly. The currently running attempt can be identified without making the picker appear to change that attempt.
- The primary-model choice, effort control and optional image helper have distinct labels. Effort remains reachable without scrolling through an entire catalog. The helper's purpose and image disclosure stay explicit; don't make users configure it for text-only work.
- All controls must remain reachable at large font and with the keyboard. Preserve explicit YES/NO/UNKNOWN image capability and recovery without clearing the draft.

## Settings as a navigable hub

Replace the monolithic appearance-first list with a compact hub and focused category pages. Use actual Fluid rows/materials and existing ViewModels; avoid duplicating state. Suggested final groups, names adjustable to fit actual functions:

1. **AI e modelli** — configured services/keys, defaults and provider policy, advanced routing/fallback options, usage. Choosing the model of the current conversation remains in the composer.
2. **Voce e assistente** — listening, reading replies, voice selection, start in text, system assistant role/overlay preferences.
3. **App e azioni** — connected apps, device permissions, action enablement and trusted actions.
4. **Aspetto** — theme, contrast/material preferences, motion settings already available.
5. **Informazioni** — version, updates, source and licenses, presented as occasional administration.

Existing memory/reminders may remain a dedicated destination with a clear link from the hub where appropriate. Future profile/background controls are not implemented in this increment.

Each page uses a clear title and a predictable back action to the hub. Preserve existing settings-section links from model chips and error recovery: keys should open the actual keys page, actions the actions page, and permissions their category. Replace the current repeated lazy-list scroll search with direct category routing plus a precise section anchor when needed. Deep links must not land first on appearance or sweep through unrelated content. All existing settings and controls remain available; changes are organizational, not feature removal.

## Verification targets

- A user can open keys from the composer warning directly, without scrolling through appearance.
- Changing model/effort uses one consistent control in app and overlay; the attachment menu doesn't contain a competing reasoning mode.
- History search preserves the positions of new-chat and permanent navigation controls, with the result list using the remaining space.
- Back from a settings category returns to the hub; back from the hub returns to the same conversation and draft. Direct links have a coherent back destination.
- Large text, narrow width and keyboard leave essential controls visible or predictably scrollable. No fixed large decorative header may crowd out the actual task.
- Existing I1 regressions remain passing: real overlay modal host/picker, suspended-send draft edits, atomic text+image handoff without duplicate dispatch, favorite semantics and honest structured cards.

Use meaningful route/state/UI regression fixtures and actual screenshots. Runtime checks and human learnability remain evidence to collect; don't claim them from static source inspection. No empirical usability percentages or unsupported cognition claims.
