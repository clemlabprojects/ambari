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

## GitOps release status
- The Helm Charts table now supports GitOps deployments with an auto-refresh toggle right next to the global controls; enabling it polls `/helm/releases/{namespace}/{release}/status` every 30 seconds.
- Each release row exposes a **Details** link that opens a status modal with the latest conditions, reconcile timing, and GitHub/GitLab metadata (PR link, branch, path). The inline refresh button forces an ad-hoc status poll, and the info icon surfaces the repository/branch/path tooltip.
- Flux releases still show the GitOps tag, Repo health badge, and PR state ribbon inside the Status column so you can spot drift fast.

## Workloads explorer
- The workloads tabs now expose an **Auto refresh** switch in the tab bar (30 s interval) and dedicated refresh button to keep pods/services/events in sync with the cluster.
- Empty states explain whether you need to select a namespace or update a label filter, and the pods table is aware of the active namespace/selector.
- Every dialog (Logs, Describe, Events) continues to fetch the latest data on demand; use the toolbar refresh buttons when you need to drill deeper after a mutation.

## Command Status Polling
- The modal polls `/commands/{id}` until terminal. “Abort” sends `/commands/{id}/cancel` when available.
