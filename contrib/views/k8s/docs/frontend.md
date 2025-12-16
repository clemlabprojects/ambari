# K8s View – Frontend Usage

## Installing a Service
1) Open “Helm Charts” → click “Install Service”.
2) Pick a service in the picker modal.
3) Fill the wizard:
   - Step 1: Release name, namespace, repo override if needed.
   - Step 2: Chart settings (dynamic form).
   - Step 3: Configuration (stack configs). Passwords must match to proceed.
   - Step 4: Review values.yaml preview; you can edit via the YAML tab.
4) Click Deploy. A toast appears and the Background operations modal is available (top-right button).

## Upgrading a Release
- On “Helm Charts”, click the ⋮ menu → “Upgrade / Config”. The wizard loads current values; edit and deploy.

## Background Operations
- Click “Background operations” (top-right). Lists recent commands from the backend.
- Click a row to see status/progress and any sub-commands; use Back to navigate.
- “Load more” fetches the next page of commands.

## Command Status Polling
- The modal polls `/commands/{id}` until terminal. “Abort” sends `/commands/{id}/cancel` when available.
